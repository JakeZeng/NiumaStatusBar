package com.aimonitor.app.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
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
 * 1x2 桌面组件的轮播后台服务。
 *
 * 设计要点：
 * - AppWidgetProvider 的 [android.appwidget.AppWidgetProviderInfo.updatePeriodMillis]
 *   系统强制最小 30 分钟（无前台服务情况下），无法满足「30 秒切换 provider」的需求，
 *   故用前台服务持有轮播循环。
 * - 每次 tick 直读主进程 SQLite（已开 WAL + busy_timeout=5000，跨进程并发安全），
 *   不再额外走 Tauri IPC 或 widget_snapshot.json。
 * - onUpdate / onEnabled 由 [UsageWidgetProvider] 触发，service 启动后维护自己的
 *   [index] 计数，循环渲染所有已添加的 widget 实例。
 * - onDisabled 触发 ACTION_STOP；widget 全部移除时 service 自退出。
 *
 * 为什么不用 WorkManager / AlarmManager：
 *   这两个最低粒度是 15 分钟级，且 30 秒轮播太频繁会触发系统节流（Doze/Standby）。
 *   前台服务是 Android 唯一允许 ~秒级稳定循环的方式，与 StatusWidgetService 同款
 *   方案，但本 service 只服务 1x2 widget 的 UsageWidgetProvider。
 */
class UsageWidgetCarouselService : Service() {

    companion object {
        private const val TAG = "UsageWidgetCarousel"

        const val ACTION_START = "com.aimonitor.app.widget.CAROUSEL_START"
        const val ACTION_STOP = "com.aimonitor.app.widget.CAROUSEL_STOP"

        /** 轮播间隔：30 秒。改这里要同步 README/CLAUDE.md 描述。 */
        const val INTERVAL_MS = 30_000L

        /**
         * 首次 tick 提前到 3 秒：service 常被系统/OEM 杀掉又重启，index 是实例
         * 成员变量，每次重启都会归零 → 多 provider 永远停在首屏那一帧。提前首
         * tick 让 index 尽快推进并落盘（见 [saveIndex]），被杀重启后能接着轮。
         */
        const val FIRST_TICK_DELAY_MS = 3_000L

        const val CHANNEL_ID = "niuma_widget_carousel"
        const val NOTIF_ID = 2001

        /** 轮播 index 持久化，避免 service 被杀重启后 index 归零卡在首供应商。 */
        private const val PREFS_NAME = "niuma_widget_carousel"
        private const val KEY_INDEX = "carousel_index"

        /**
         * DB 最新数据陈旧阈值（秒）。超过这个时间没有新 usage_history 写入，
         * 就认为 poller 没在跑（app 进程被杀 / 没启动 / 网络失败），
         * carousel service 主动 startActivity 唤起 MainActivity 拉一次。
         *
         * v0.1.51：5 分钟 → 15 分钟。App 进程活着时 poller 默认 60 秒一轮
         * （`refresh_interval.max(10)`，见 poller.rs），根本不会触发陈旧；
         * 真正陈旧只发生在 App 进程已经不在的时候，那时 5 分钟还是 15 分钟
         * 对「能否唤醒」没有区别，放宽只是省掉 App 正常运行时的无效判定。
         *
         * 同时被 [WidgetLayoutBuilder] 用作「第 2 行改显示更新于 N 分钟前」的阈值，
         * 两处必须是同一个值，所以这里不做 private。
         */
        const val STALE_THRESHOLD_SEC = 15 * 60L

        /**
         * 唤起 MainActivity 的最小间隔（毫秒）。避免在 poller 还没跑出第一行
         * usage_history 时反复 startActivity（每个 tick 30s，不节流会刷屏）。
         */
        private const val WAKE_THROTTLE_MS = 5 * 60_000L

        /**
         * 启动 carousel 前台服务。所有异常在内部吞掉并记日志，绝不外抛——
         * 调用方可能是 BroadcastReceiver（onUpdate / onEnabled）或 MainActivity。
         *
         * 重要：从 AppWidgetProvider 的 broadcast 回调里调用本方法时，若 App
         * 不在前台，Android 12+（API 31）会抛 ForegroundServiceStartNotAllowedException
         * 并静默吞掉：service 起不来但首屏渲染（refreshAllBlocking）照常。
         * 真正可靠的前台启动窗口是 MainActivity（App 在前台），所以 MainActivity
         * 的 onResume 也会调本方法作为兜底。
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, UsageWidgetCarouselService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { t ->
                Log.w(TAG, "start() carousel service failed (ignored)", t)
            }
        }

        /** 停止 carousel 前台服务（最后一个 widget 被移除时调用）。 */
        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, UsageWidgetCarouselService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            }.onFailure { t ->
                Log.w(TAG, "stop() carousel service failed (ignored)", t)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var index: Int = 0
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    /**
     * 上一次 startActivity 唤起 MainActivity 的时间戳（ms）。
     * 用 throttle 避免 poller 还没跑出第一行 usage_history 时每 30s 刷一次
     * startActivity。MainActivity 是 singleTask，唤起时复用现有实例，
     * 但仍要避免在数据库写入前反复触发 WebView 加载 / 前台 Activity 切换。
     */
    private var lastWakeAtMs: Long = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                refreshTick()
            } catch (t: Throwable) {
                // 单次 tick 失败不退出循环；典型错误：DB 临时锁、跨进程 SQLITE_BUSY
                Log.w(TAG, "tick failed, will retry next interval", t)
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
        // 从持久化恢复 index：service 被系统/OEM 杀掉重启后，index 不会归零
        // 卡在首供应商，而是接着上次的进度轮。
        index = loadIndex()
        // 首次 tick 提前到 FIRST_TICK_DELAY_MS（见常量注释），尽快推进并落盘。
        handler.postDelayed(tickRunnable, FIRST_TICK_DELAY_MS)
        Log.d(TAG, "onCreate, first tick in ${FIRST_TICK_DELAY_MS}ms, restored index=$index")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        // 重启场景：系统可能已把我们 kill 后再次拉起，需要重新走前台通知
        startForegroundCompat()
        // 重新调度（避免 onCreate 之后 handler 已被 post 的 runnable 在某些重启路径上 lost）
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

    // ===== 轮播 index 持久化（防止 service 被杀重启后 index 归零卡首帧）=====

    private fun loadIndex(): Int {
        return runCatching { prefs.getInt(KEY_INDEX, 0).coerceAtLeast(0) }
            .getOrDefault(0)
    }

    private fun saveIndex(v: Int) {
        runCatching { prefs.edit().putInt(KEY_INDEX, v).apply() }
    }

    // ===== 核心轮播逻辑 =====

    private fun refreshTick() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) {
            // 没有已添加的 widget，service 可以退出（UsageWidgetProvider.onDisabled 也
            // 会发 ACTION_STOP，这里是 defense-in-depth）
            Log.d(TAG, "no widgets placed, stopSelf")
            stopSelf()
            return
        }

        // 读盘 + 构造 RemoteViews 放 IO；updateAppWidget 跨 Binder，
        // 必须回主线程调用以避免 StrictMode 警告与偶发 ANR。
        ioScope.launch {
            val snapshots = try {
                WidgetDataReader.latestForEnabledProviders(this@UsageWidgetCarouselService)
            } catch (t: Throwable) {
                Log.e(TAG, "refreshTick read DB failed", t)
                emptyList()
            }
            val themeId = WidgetDataReader.appTheme(this@UsageWidgetCarouselService)
            val total = snapshots.size
            // v0.1.51：snapshots 为空时补查 providers 表，让空态区分
            // 「没添加供应商」与「有供应商但还没拉到数据」。有数据时不查。
            val hasAnyProvider =
                if (total == 0) WidgetDataReader.hasEnabledProviders(this@UsageWidgetCarouselService)
                else true
            // 检测 DB 是否需要唤醒 App：
            //   - snapshots 为空：DB 完全没数据（app 没启动过 / poller 没跑过）
            //   - latestTimestamp 陈旧：poller 停了（app 进程被杀 / 网络挂）
            // 任一命中就 startActivity(MainActivity) 唤起，throttle 5 分钟。
            val latestTs = try {
                WidgetDataReader.latestTimestamp(this@UsageWidgetCarouselService)
            } catch (t: Throwable) {
                Log.w(TAG, "latestTimestamp read failed", t)
                null
            }
            maybeWakeApp(snapshots, latestTs)
            if (total == 0) {
                // 空态 RemoteViews 只跟 hasAnyProvider 有关，提前构造一次复用
                val empty = WidgetLayoutBuilder.build(
                    this@UsageWidgetCarouselService,
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
            // 传整个列表 + current，让 WidgetLayoutBuilder 内部算页码（"2/3"）。
            // 之前传的是 listOf(snapshots[current]) + index=0，列表长度恒为 1，
            // 页码分支永远走 GONE —— 多 provider 轮播时看不到自己在看第几个。
            val currentView = WidgetLayoutBuilder.build(
                this@UsageWidgetCarouselService,
                snapshots,
                current,
                themeId,
                hasAnyProvider,
            )
            // updateAppWidget 跨 Binder，回主线程调用避免 StrictMode 警告与偶发 ANR
            handler.post {
                try {
                    ids.forEach { id -> mgr.updateAppWidget(id, currentView) }
                } catch (t: Throwable) {
                    Log.e(TAG, "updateAppWidget failed", t)
                }
                // 在主线程派发完成后再递增 index，避免读盘慢时主线程用了过期的 current
                index = next
                // 落盘：service 被杀重启后能从这里恢复，接着轮而不是卡在首供应商
                saveIndex(index)
            }
        }
    }

    /**
     * DB 完全空 / 数据陈旧时，startActivity 唤起 MainActivity。
     *
     * 调用方是 ioScope.launch 的协程，但 startActivity 必须在主线程跑（Android 14+
     * 严格收紧 Service.startActivity）。这里发到 mainHandler 调一次。
     *
     * throttle 5 分钟：避免 poller 第一轮还没 tick 出数据前，每 30s 都 startActivity。
     * MainActivity 是 singleTask，复用现有实例，但每次仍会触发 onNewIntent → WebView
     * evaluateJavascript，节流才能让主线程别被反复打断。
     */
    private fun maybeWakeApp(snapshots: List<UsageSnapshot>, latestTs: Long?) {
        val nowSec = System.currentTimeMillis() / 1000
        val stale = latestTs == null || (nowSec - latestTs) > STALE_THRESHOLD_SEC
        val empty = snapshots.isEmpty()
        if (!stale && !empty) return

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastWakeAtMs < WAKE_THROTTLE_MS) {
            Log.d(TAG, "maybeWakeApp throttled (last=${nowMs - lastWakeAtMs}ms ago, stale=$stale, empty=$empty)")
            return
        }
        lastWakeAtMs = nowMs

        Log.d(TAG, "maybeWakeApp firing (stale=$stale, empty=$empty, latestTs=$latestTs, snapshots=${snapshots.size})")

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // 标记这次是 widget 唤起的；MainActivity 会通过 WebView 调
            // window.__NIUMA_WIDGET_WAKE__() 触发前端 widget_refresh_all。
            putExtra(MainActivity.EXTRA_FROM_WIDGET, true)
        }
        // v0.1.51：真的发到主线程调。之前注释写了「发到 mainHandler」但实现是
        // 直接在 ioScope 协程里 startActivity —— 非主线程启动 Activity 在部分
        // ROM 上行为未定义，且和注释对不上。
        handler.post {
            try {
                startActivity(intent)
            } catch (t: Throwable) {
                // Android 10+ 后台启动 Activity 受限；foreground service 不在豁免
                // 清单里，多数 ROM 会静默拦截。失败只记日志，不影响 widget 渲染。
                Log.w(TAG, "startActivity(MainActivity) failed", t)
            }
        }
    }

    // ===== 前台通知（Android 8+ 必需）=====

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.widget_carousel_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.widget_carousel_channel_desc)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+：传 type 更明确。本服务是 widget 轮播（特殊用途），
            // 用 specialUse；property 解释写在 manifest 里。
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
            .setContentTitle(getString(R.string.widget_carousel_notif_title))
            .setContentText(getString(R.string.widget_carousel_notif_text))
            .setSmallIcon(R.drawable.ic_widget_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
