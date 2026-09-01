package com.aimonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 桌面小组件后台刷新服务。
 *
 * 以「前台服务」形式常驻，保证 Android 8+ 后台限制下仍能持续轮播供应商数据。
 * 每隔 [INTERVAL_MS] 从 app 数据目录读取 [WIDGET_SNAPSHOT]（由 Rust 侧轮询后写入），
 * 依次把每个供应商的余额 / 额度渲染到所有已添加的小组件实例上。
 */
class StatusWidgetService : Service() {

    companion object {
        const val CHANNEL_ID = "niuma_widget_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.aimonitor.app.WIDGET_START"
        const val ACTION_STOP = "com.aimonitor.app.WIDGET_STOP"
        const val INTERVAL_MS = 3000L

        /** 与 Rust 侧 widget_snapshot.rs 中写出的文件名保持一致 */
        private const val SNAPSHOT_NAME = "widget_snapshot.json"
        private const val SNAPSHOT_DIR = "ai-model-monitor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var index = 0

    private val runnable = object : Runnable {
        override fun run() {
            try {
                updateWidgets()
            } catch (e: Throwable) {
                // 单帧渲染异常不应杀死循环
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        handler.postDelayed(runnable, INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 重新触发循环（服务可能已被系统拉起）
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, INTERVAL_MS)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(runnable)
        super.onDestroy()
    }

    // ===== 通知（前台服务必须）=====

    private fun buildNotification(): Notification {
        createChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("粮草用量")
            .setContentText("小组件后台刷新中")
            .setSmallIcon(R.drawable.ic_widget_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "小组件更新", NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    // ===== 快照读取 =====

    /**
     * 定位 Rust 侧写出的快照文件。
     * Tauri 的 app_data_dir 在 Android 具体映射到 getFilesDir() 还是外部存储，
     * 不同版本可能不同；这里同时探测两者，并且若同目录下存在 Tauri 写出的
     * data.db，则认定该目录为真实落盘目录（即使快照尚未生成也能正确指向）。
     */
    private fun snapshotFile(): File? {
        val dirs = mutableListOf<File>()
        dirs.add(File(filesDir, SNAPSHOT_DIR))
        getExternalFilesDir(null)?.let { dirs.add(File(it, SNAPSHOT_DIR)) }

        for (dir in dirs) {
            val snap = File(dir, SNAPSHOT_NAME)
            if (snap.exists()) return snap
        }
        // 目录正确但快照尚未生成（应用刚启动、首次轮询还未完成）
        for (dir in dirs) {
            if (File(dir, "data.db").exists()) return File(dir, SNAPSHOT_NAME)
        }
        return null
    }

    private fun loadSnapshot(): Pair<Long, JSONArray> {
        val file = snapshotFile() ?: return 0L to JSONArray()
        return try {
            val text = file.readText()
            val obj = JSONObject(text)
            val updated = obj.optLong("updatedAt", 0L)
            val arr = obj.optJSONArray("providers") ?: JSONArray()
            updated to arr
        } catch (e: Exception) {
            0L to JSONArray()
        }
    }

    // ===== 渲染 =====

    private fun updateWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, StatusWidgetProvider::class.java))
        if (ids.isEmpty()) {
            // 没有已添加的小组件，服务可以退出
            stopSelf()
            return
        }

        val (updatedAt, providers) = loadSnapshot()
        val n = providers.length()

        for (appWidgetId in ids) {
            val rv = RemoteViews(packageName, R.layout.widget_status)

            // 点击整个小组件打开 App
            val open = PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            rv.setOnClickPendingIntent(R.id.widget_root, open)

            if (n == 0) {
                rv.setTextViewText(R.id.tv_name, "暂无供应商")
                rv.setTextViewText(R.id.tv_balance, "—")
                rv.setTextViewText(
                    R.id.tv_balance_sub,
                    if (updatedAt == 0L) "等待应用启动…" else "请在应用内添加供应商",
                )
                rv.setTextViewText(R.id.tv_updated, "")
                rv.setTextViewText(R.id.tv_footer, "")
                rv.removeAllViews(R.id.quota_container)
            } else {
                val shown = index % n
                index = (shown + 1) % n
                val p = providers.optJSONObject(shown) ?: JSONObject()

                rv.setTextViewText(R.id.tv_name, p.optString("name", "—"))
                rv.setTextViewText(R.id.tv_balance, formatBalance(p))
                rv.setTextViewText(R.id.tv_balance_sub, formatBalanceSub(p))
                rv.setTextViewText(R.id.tv_updated, formatAgo(updatedAt))
                rv.setTextViewText(
                    R.id.tv_footer,
                    "供应商 ${shown + 1}/$n · 点击查看详情",
                )

                rv.removeAllViews(R.id.quota_container)
                addQuotaRow(rv, "5H 额度", p,
                    "quota5hRemaining", "quota5hTotal", "quota5hRemainingPercent")
                addQuotaRow(rv, "周额度", p,
                    "quotaWeekRemaining", "quotaWeekTotal", "quotaWeekRemainingPercent")
                addQuotaRow(rv, "月额度", p,
                    "quotaMonthRemaining", "quotaMonthTotal", "quotaMonthRemainingPercent")
            }

            mgr.updateAppWidget(appWidgetId, rv)
        }
    }

    private fun addQuotaRow(
        rv: RemoteViews,
        label: String,
        p: JSONObject,
        remKey: String,
        totalKey: String,
        pctKey: String,
    ) {
        val remaining = p.optDouble(remKey, Double.NaN).takeIf { !it.isNaN() }
        val total = p.optDouble(totalKey, Double.NaN).takeIf { !it.isNaN() }
        val pct = p.optDouble(pctKey, Double.NaN).takeIf { !it.isNaN() }

        // 三项都为空则跳过这一行
        if (remaining == null && total == null && pct == null) return

        val computedPct = when {
            pct != null -> pct
            remaining != null && total != null && total > 0 -> (remaining / total) * 100.0
            else -> null
        }

        val valueText = when {
            remaining != null && total != null -> "${fmt(remaining)} / ${fmt(total)}"
            pct != null -> "${pct.toInt()}%"
            remaining != null -> fmt(remaining)
            total != null -> fmt(total)
            else -> "—"
        }

        val item = RemoteViews(packageName, R.layout.widget_quota_item)
        item.setTextViewText(R.id.tv_label, label)
        item.setTextViewText(R.id.tv_value, valueText)
        val progress = computedPct?.coerceIn(0.0, 100.0)?.toInt() ?: 0
        item.setProgressBar(R.id.pb, 100, progress, false)
        rv.addView(R.id.quota_container, item)
    }

    // ===== 格式化辅助 =====

    private fun currencySymbol(cur: String?): String = when (cur?.uppercase()) {
        "CNY" -> "¥"
        "USD" -> "$"
        else -> ""
    }

    private fun fmt(v: Double?): String {
        if (v == null) return "—"
        return if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.2f", v)
    }

    private fun formatBalance(p: JSONObject): String {
        if (p.optBoolean("hasError", false)) return "获取失败"
        val bal = p.optDouble("balance", Double.NaN).takeIf { !it.isNaN() } ?: return "—"
        return currencySymbol(p.optString("currency", "")) + fmt(bal)
    }

    private fun formatBalanceSub(p: JSONObject): String {
        if (p.optBoolean("hasError", false)) {
            val err = p.optString("lastError", "")
            return if (err.isNotEmpty()) err else "请求失败"
        }
        val used = p.optDouble("balanceUsed", Double.NaN).takeIf { !it.isNaN() }
        val limit = p.optDouble("balanceLimit", Double.NaN).takeIf { !it.isNaN() }
        val sym = currencySymbol(p.optString("currency", ""))
        return when {
            used != null && limit != null -> "已用 $sym${fmt(used)} / 总额 $sym${fmt(limit)}"
            limit != null -> "总额 $sym${fmt(limit)}"
            used != null -> "已用 $sym${fmt(used)}"
            else -> ""
        }
    }

    private fun formatAgo(updatedAt: Long): String {
        if (updatedAt == 0L) return ""
        val diff = (System.currentTimeMillis() / 1000) - updatedAt
        return when {
            diff < 0 -> "刚刚"
            diff < 60 -> "${diff}秒前"
            diff < 3600 -> "${diff / 60}分钟前"
            else -> "${diff / 3600}小时前"
        }
    }
}
