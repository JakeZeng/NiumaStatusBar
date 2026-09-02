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
 *   系统强制最小 30 分钟（无前台服务情况下），无法满足「5 秒切换 provider」的需求，
 *   故用前台服务持有轮播循环。
 * - 每次 tick 直读主进程 SQLite（已开 WAL + busy_timeout=5000，跨进程并发安全），
 *   不再额外走 Tauri IPC 或 widget_snapshot.json。
 * - onUpdate / onEnabled 由 [UsageWidgetProvider] 触发，service 启动后维护自己的
 *   [index] 计数，循环渲染所有已添加的 widget 实例。
 * - onDisabled 触发 ACTION_STOP；widget 全部移除时 service 自退出。
 *
 * 为什么不用 WorkManager / AlarmManager：
 *   这两个最低粒度是 15 分钟级，且 5 秒轮播太频繁会触发系统节流（Doze/Standby）。
 *   前台服务是 Android 唯一允许 ~秒级稳定循环的方式，与 StatusWidgetService 同款
 *   方案，但本 service 只服务 1x2 widget 的 UsageWidgetProvider。
 */
class UsageWidgetCarouselService : Service() {

    companion object {
        private const val TAG = "UsageWidgetCarousel"

        const val ACTION_START = "com.aimonitor.app.widget.CAROUSEL_START"
        const val ACTION_STOP = "com.aimonitor.app.widget.CAROUSEL_STOP"

        /** 轮播间隔：5 秒。改这里要同步 README/CLAUDE.md 描述。 */
        const val INTERVAL_MS = 5000L

        const val CHANNEL_ID = "niuma_widget_carousel"
        const val NOTIF_ID = 2001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var index: Int = 0
    private val ioScope = CoroutineScope(Dispatchers.IO)

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
        WidgetStatusReporter.report(this, WidgetStatusReporter.Event.SERVICE_START)
        // 首次延迟一个 INTERVAL 再开始，让 onUpdate 先把 index 校准为 0
        handler.postDelayed(tickRunnable, INTERVAL_MS)
        Log.d(TAG, "onCreate, first tick in ${INTERVAL_MS}ms")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        // 重启场景：系统可能已把我们 kill 后再次拉起，需要重新走前台通知
        startForegroundCompat()
        WidgetStatusReporter.report(this, WidgetStatusReporter.Event.SERVICE_START)
        // 重新调度（避免 onCreate 之后 handler 已被 post 的 runnable 在某些重启路径上 lost）
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, INTERVAL_MS)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        Log.d(TAG, "onDestroy")
        super.onDestroy()
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
                WidgetStatusReporter.report(this@UsageWidgetCarouselService,
                    WidgetStatusReporter.Event.SERVICE_ERROR,
                    widgetCount = ids.size,
                    errorMessage = "read DB failed: " + (t.message ?: t.javaClass.simpleName))
                emptyList()
            }
            val themeId = WidgetDataReader.appTheme(this@UsageWidgetCarouselService)
            // 状态上报：每 5s 一次，主进程 app 启动时能直接读
            WidgetStatusReporter.report(this@UsageWidgetCarouselService,
                WidgetStatusReporter.Event.TICK,
                widgetCount = ids.size,
                snapshotCount = snapshots.size)
            val total = snapshots.size
            val (currentIdx, nextIdx) = if (total == 0) {
                // 没数据：index 保持 0，每 tick 都渲染空态。避免在轮播时无意义递增。
                0 to 0
            } else {
                val current = index % total
                current to (current + 1) % total
            }
            val previews = snapshots.map { snap ->
                WidgetLayoutBuilder.build(
                    this@UsageWidgetCarouselService,
                    listOf(snap),
                    0,
                    themeId,
                )
            }
            // 切回主线程推送给 AppWidgetManager
            handler.post {
                try {
                    if (total == 0) {
                        ids.forEach { id ->
                            mgr.updateAppWidget(
                                id,
                                WidgetLayoutBuilder.build(
                                    this@UsageWidgetCarouselService,
                                    emptyList(),
                                    0,
                                    themeId,
                                ),
                            )
                        }
                    } else {
                        ids.forEach { id ->
                            mgr.updateAppWidget(id, previews[currentIdx])
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "updateAppWidget failed", t)
                }
            }
            // 在主线程派发完成后再递增 index，避免读盘慢时主线程用了过期的 current
            if (total > 0) {
                index = nextIdx
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
