package com.aimonitor.app.widget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * 直接读取主进程写入的 SQLite 数据库。
 *
 * 主进程 (Rust) 写入路径：
 *   app.path().app_data_dir() = /data/data/com.aimonitor.app/
 *   + ai-model-monitor/data.db
 *
 * 主进程已开启 WAL + busy_timeout=5000（storage.rs），
 * widget 进程用 OPEN_READONLY 打开即可，无需任何权限。
 */
object WidgetDataReader {
    private const val TAG = "WidgetDataReader"
    private const val DB_RELATIVE = "ai-model-monitor/data.db"

    /**
     * 读取 App 当前主题（settings 表 key=app_theme，由前端 ThemeManager
     * 经 set_app_theme 写入）。未设置/读取失败时返回 null，widget 回退
     * 到系统明暗配色。
     */
    fun appTheme(context: Context): String? {
        val dbPath = File(context.dataDir, DB_RELATIVE)
        if (!dbPath.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                /* factory = */ null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT value FROM settings WHERE key = 'app_theme'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "read app_theme failed", e)
            null
        }
    }

    /**
     * 取所有 enabled providers 的最新 usage_history 行。
     * 返回按 providerName 升序。
     */
    fun latestForEnabledProviders(context: Context): List<UsageSnapshot> {
        val dbPath = File(context.dataDir, DB_RELATIVE)
        if (!dbPath.exists()) {
            Log.w(TAG, "DB not found at ${dbPath.absolutePath}")
            return emptyList()
        }
        return try {
            SQLiteDatabase.openDatabase(
                dbPath.absolutePath,
                /* factory = */ null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                queryLatest(db)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openDatabase failed", e)
            emptyList()
        }
    }

    private fun queryLatest(db: SQLiteDatabase): List<UsageSnapshot> {
        // 复用 Rust 侧 latest_usage_per_provider 的 SQL 结构：
        // 取每个 provider_id 最新的 timestamp 行，JOIN providers 元数据，仅 enabled。
        val sql = """
            SELECT h.provider_id, h.timestamp, h.balance, h.balance_used, h.balance_limit,
                   h.error_rate, h.avg_latency, h.last_error,
                   h.quota_5h_remaining, h.quota_5h_remaining_percent, h.quota_5h_total, h.quota_5h_used,
                   h.quota_week_remaining, h.quota_week_remaining_percent, h.quota_week_total, h.quota_week_used,
                   h.quota_month_remaining, h.quota_month_total, h.quota_month_used,
                   p.name, p.provider, p.is_enabled, p.status
              FROM usage_history h
              JOIN (
                SELECT provider_id, MAX(timestamp) AS max_ts
                FROM usage_history
                GROUP BY provider_id
              ) latest
                ON latest.provider_id = h.provider_id AND latest.max_ts = h.timestamp
              JOIN providers p ON p.id = h.provider_id
             WHERE p.is_enabled = 1
             ORDER BY p.name COLLATE NOCASE
        """.trimIndent()

        val out = mutableListOf<UsageSnapshot>()
        db.rawQuery(sql, null).use { c ->
            val iProviderId = c.getColumnIndexOrThrow("provider_id")
            val iTimestamp = c.getColumnIndexOrThrow("timestamp")
            val iBalance = c.getColumnIndexOrThrow("balance")
            val iBalanceUsed = c.getColumnIndexOrThrow("balance_used")
            val iBalanceLimit = c.getColumnIndexOrThrow("balance_limit")
            val iErrRate = c.getColumnIndexOrThrow("error_rate")
            val iLatency = c.getColumnIndexOrThrow("avg_latency")
            val iLastError = c.getColumnIndexOrThrow("last_error")
            val iQ5hR = c.getColumnIndexOrThrow("quota_5h_remaining")
            val iQ5hP = c.getColumnIndexOrThrow("quota_5h_remaining_percent")
            val iQ5hT = c.getColumnIndexOrThrow("quota_5h_total")
            val iQ5hU = c.getColumnIndexOrThrow("quota_5h_used")
            val iQwR = c.getColumnIndexOrThrow("quota_week_remaining")
            val iQwP = c.getColumnIndexOrThrow("quota_week_remaining_percent")
            val iQwT = c.getColumnIndexOrThrow("quota_week_total")
            val iQwU = c.getColumnIndexOrThrow("quota_week_used")
            val iQmR = c.getColumnIndexOrThrow("quota_month_remaining")
            val iQmT = c.getColumnIndexOrThrow("quota_month_total")
            val iQmU = c.getColumnIndexOrThrow("quota_month_used")
            val iName = c.getColumnIndexOrThrow("name")
            val iType = c.getColumnIndexOrThrow("provider")
            val iEnabled = c.getColumnIndexOrThrow("is_enabled")

            while (c.moveToNext()) {
                out += UsageSnapshot(
                    providerId = c.getString(iProviderId),
                    providerName = c.getString(iName) ?: "—",
                    providerType = c.getString(iType) ?: "custom",
                    isEnabled = c.getInt(iEnabled) != 0,
                    timestamp = c.getLong(iTimestamp),
                    balance = c.getDoubleOrNull(iBalance),
                    balanceUsed = c.getDoubleOrNull(iBalanceUsed),
                    balanceLimit = c.getDoubleOrNull(iBalanceLimit),
                    // currency 列 usage_history 表里没存，Rust 端 UsageStatus 有 currency；
                    // 历史表没存它时只能为 null。组件按 provider 类型兜底（人民币）。
                    currency = null,
                    requestsToday = null,
                    errorRate = c.getDoubleOrNull(iErrRate),
                    avgLatency = c.getLongOrNull(iLatency),
                    lastError = c.getStringOrNull(iLastError),
                    quota5hRemaining = c.getDoubleOrNull(iQ5hR),
                    quota5hRemainingPercent = c.getDoubleOrNull(iQ5hP),
                    quota5hTotal = c.getDoubleOrNull(iQ5hT),
                    quota5hUsed = c.getDoubleOrNull(iQ5hU),
                    quota5hResetAt = null,
                    quotaWeekRemaining = c.getDoubleOrNull(iQwR),
                    quotaWeekRemainingPercent = c.getDoubleOrNull(iQwP),
                    quotaWeekTotal = c.getDoubleOrNull(iQwT),
                    quotaWeekUsed = c.getDoubleOrNull(iQwU),
                    quotaWeekResetAt = null,
                    quotaMonthRemaining = c.getDoubleOrNull(iQmR),
                    quotaMonthTotal = c.getDoubleOrNull(iQmT),
                    quotaMonthUsed = c.getDoubleOrNull(iQmU),
                )
            }
        }
        return out
    }
}

private fun android.database.Cursor.getDoubleOrNull(idx: Int): Double? =
    if (isNull(idx)) null else getDouble(idx)

private fun android.database.Cursor.getLongOrNull(idx: Int): Long? =
    if (isNull(idx)) null else getLong(idx)

private fun android.database.Cursor.getStringOrNull(idx: Int): String? =
    if (isNull(idx)) null else getString(idx)