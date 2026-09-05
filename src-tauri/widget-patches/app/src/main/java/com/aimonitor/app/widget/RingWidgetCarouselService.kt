package com.aimonitor.app.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aimonitor.app.MainActivity
import com.aimonitor.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 2x2 环图 widget 的轮播前台服务（v0.1.52+）。
 *
 * 与 [UsageWidgetCarouselService] 平行存在：
 *  - 独立 FGS 实例（Android 12+ 前台服务不能共享 binder）
 *  - 独立 SharedPreferences（niuma_widget_ring_carousel）持久化轮播 index
 *  - 独立 NotificationChannel（widget_ring_channel_*）
 *  - 独立 ComponentName（RingWidgetProvider）遍历 widget
 *  - 共用 [UsageWidgetCarouselService.STALE_THRESHOLD_SEC]（15 分钟）保证
 *    "陈旧判定"和"陈旧展示"在 1x2 / 2x2 之间不漂移
 *
 * 设计取舍：原本可考虑把 1x2 和 2x2 的轮播合并到同一个 service（一次
 * 遍历两组 ComponentName），但 1x2 / 2x2 未来可能各自调节奏（用户
 * 想要 2x2 切慢点 / 干脆不切），拆分便于独立演化。
 *
 * FGS subtype property 解释（manifest 注册时填）：
 *   "Periodic home-screen widget refresh for 2x2 ring widget: read locally
 *    cached usage_history and rotate the displayed provider every 30 seconds.
 *    No network transfer, no user data leaves the device."
 */
class RingWidgetCarouselService : Service() {

    companion object {
        private const val TAG = "RingWidgetCarousel"

        const val ACTION_START = "com.aimonitor.app.widget.RING_CAROUSEL_START"
        const val ACTION_STOP = "com.aimonitor.app.widget.RING_CAROUSEL_STOP"

        /** 轮播间隔：30 秒。改这里要同步 README/CLAUDE.md 描述。 */
        const val INTERVAL_MS = UsageWidgetCarouselService.INTERVAL_MS

        /** 首次 tick 提前到 3 秒，与 1x2 service 同节奏。 */
        const val FIRST_TICK_DELAY_MS = UsageWidgetCarouselService.FIRST_TICK_DELAY_MS

        const val CHANNEL_ID = "niuma_widget_ring_carousel"
        const val NOTIF_ID = 2002  // 1x2 carousel 占用 2001

        /** 轮播 index 持久化。 */
        private const val PREFS_NAME = "niuma_widget_ring_carousel"
        private const val KEY_INDEX = "ring_carousel_index"

        /** 唤起 MainActivity 的最小间隔（毫秒）。与 1x2 共用同一个常量。 */
        private const val WAKE_THROTTLE_MS = UsageWidgetCarouselService.STALE_THRESHOLD_SEC  // 实际不引用，避免循环依赖

        /**
         * 启动 carousel 前台服务。所有异常在内部吞掉并记日志，绝不外抛。
         * 重要：从 AppWidgetProvider 的 broadcast 回调里调用本方法时，若 App
         * 不在前台，Android 12+（API 31）会抛
         * ForegroundServiceStartNotAllowedException 并静默吞掉。
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, RingWidgetCarouselService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { t ->
                Log.w(TAG, "start() ring carousel service failed (ignored)", t)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, RingWidgetCarouselService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }.onFailure { t ->
                Log.w(TAG, "stop() ring carousel service failed (ignored)", t)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var index: Int = 0
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private var lastWakeAtMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                refreshTick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed, will retry next interval", t)
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
        index = loadIndex()
        handler.postDelayed(tickRunnable, FIRST_TICK_DELAY_MS)
        Log.d(TAG, "onCreate, first tick in ${FIRST_TICK_DELAY_MS}ms, restored index=$index")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, FIRST_TICK_DELAY_MS)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    // ===== index 持久化 =====

    private fun loadIndex(): Int =
        runCatching { prefs.getInt(KEY_INDEX, 0).coerceAtLeast(0) }.getOrDefault(0)

    private fun saveIndex(v: Int) {
        runCatching { prefs.edit().putInt(KEY_INDEX, v).apply() }
    }

    // ===== 核心轮播逻辑 =====

    private fun refreshTick() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, RingWidgetProvider::class.java))
        if (ids.isEmpty()) {
            Log.d(TAG, "no ring widgets placed, stopSelf")
            stopSelf()
            return
        }

        ioScope.launch {
            val snapshots = try {
                WidgetDataReader.latestForEnabledProviders(this@RingWidgetCarouselService)
            } catch (t: Throwable) {
                Log.e(TAG, "refreshTick read DB failed", t)
                emptyList()
            }
            val themeId = WidgetDataReader.appTheme(this@RingWidgetCarouselService)
            val total = snapshots.size
            val hasAnyProvider =
                if (total == 0) WidgetDataReader.hasEnabledProviders(this@RingWidgetCarouselService)
                else true

            val latestTs = try {
                WidgetDataReader.latestTimestamp(this@RingWidgetCarouselService)
            } catch (t: Throwable) {
                Log.w(TAG, "latestTimestamp read failed", t)
                null
            }
            maybeWakeApp(snapshots, latestTs)

            if (total == 0) {
                val empty = RingWidgetLayoutBuilder.build(
                    this@RingWidgetCarouselService,
                    emptyList(),
                    0,
                    themeId,
                    hasAnyProvider,
                )
                handler.post {
                    try {
                        ids.forEach { id -> mgr.updateAppWidget(id, empty) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "updateAppWidget failed", t)
                    }
                }
                return@launch
            }

            val current = index % total
            val next = (current + 1) % total
            val currentView = RingWidgetLayoutBuilder.build(
                this@RingWidgetCarouselService,
                snapshots,
                current,
                themeId,
                hasAnyProvider,
            )
            handler.post {
                try {
                    ids.forEach { id -> mgr.updateAppWidget(id, currentView) }
                } catch (t: Throwable) {
                    Log.e(TAG, "updateAppWidget failed", t)
                }
                index = next
                saveIndex(index)
            }
        }
    }

    /**
     * DB 完全空 / 数据陈旧时，startActivity 唤起 MainActivity。
     * 必须在主线程跑（Android 14+ 严格收紧 Service.startActivity）。
     * throttle 5 分钟与 1x2 service 保持一致：避免 poller 第一轮还没 tick
     * 出数据前，每 30s 都 startActivity。
     */
    private fun maybeWakeApp(snapshots: List<UsageSnapshot>, latestTs: Long?) {
        val nowSec = System.currentTimeMillis() / 1000
        val stale = latestTs == null || (nowSec - latestTs) > UsageWidgetCarouselService.STALE_THRESHOLD_SEC
        val empty = snapshots.isEmpty()
        if (!stale && !empty) return

        val nowMs = System.currentTimeMillis()
        // 5 分钟节流
        if (nowMs - lastWakeAtMs < 5 * 60_000L) {
            Log.d(TAG, "maybeWakeApp throttled (last=${nowMs - lastWakeAtMs}ms ago, stale=$stale, empty=$empty)")
            return
        }
        lastWakeAtMs = nowMs

        Log.d(TAG, "maybeWakeApp firing (stale=$stale, empty=$empty, latestTs=$latestTs, snapshots=${snapshots.size})")

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FROM_WIDGET, true)
        }
        handler.post {
            try {
                startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startActivity(MainActivity) failed", t)
            }
        }
    }

    // ===== 前台通知 =====

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.widget_ring_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.widget_ring_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.widget_ring_notif_title))
            .setContentText(getString(R.string.widget_ring_notif_text))
            .setSmallIcon(R.drawable.ic_widget_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
