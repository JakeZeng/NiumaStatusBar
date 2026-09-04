package com.aimonitor.app.widget

/**
 * 桌面组件读取的快照数据：与 Rust 端 UsageStatus 镜像，
 * 加上来自 providers 表的展示用元数据（name/provider/is_enabled）。
 *
 * 注意：所有数值字段都允许为 null（数据库中可空），
 * 渲染层必须按 null 做"未就绪"占位。
 */
data class UsageSnapshot(
    val providerId: String,
    val providerName: String,
    val providerType: String,           // minimax_coding / deepseek / volcengine_coding ...
    val isEnabled: Boolean,
    val timestamp: Long,
    val balance: Double?,
    val balanceUsed: Double?,
    val balanceLimit: Double?,
    val currency: String?,             // ISO 4217 (CNY/USD)
    val requestsToday: Long?,
    val errorRate: Double?,
    val avgLatency: Long?,
    val lastError: String?,
    // === Coding Plan 多档额度 ===
    val quota5hRemaining: Double?,
    val quota5hRemainingPercent: Double?,
    val quota5hTotal: Double?,
    val quota5hUsed: Double?,
    val quota5hResetAt: Long?,
    val quotaWeekRemaining: Double?,
    val quotaWeekRemainingPercent: Double?,
    val quotaWeekTotal: Double?,
    val quotaWeekUsed: Double?,
    val quotaWeekResetAt: Long?,
    val quotaMonthRemaining: Double?,
    val quotaMonthTotal: Double?,
    val quotaMonthUsed: Double?,
) {
    /**
     * 是否为 Coding Plan 类型（前端分支渲染）。
     * 与 Rust 端 fetch_usage 的判定保持一致。
     */
    val isCodingPlan: Boolean
        get() = providerType == "minimax_coding" ||
            providerType == "minimax_token" ||
            providerType == "volcengine_coding" ||
            providerType == "volcengine_token"

    /** 当前主数字：余额型取 balance；Coding Plan 优先用 5h remaining percent */
    fun primaryValue(): Double? {
        if (isCodingPlan) {
            quota5hRemainingPercent?.let { return it }    // 已折算成百分比（0-100）
            quota5hRemaining?.let { return it }
            quota5hTotal?.let { return it }
            return null
        }
        return balance ?: balanceLimit
    }

    /** 主数字展示用的单位/前缀：余额用 currency 符号；Coding Plan 用百分号 */
    fun primarySuffix(): String = when {
        isCodingPlan -> "%"
        else -> currencySymbol()
    }

    fun currencySymbol(): String = when (currency?.uppercase()) {
        "USD" -> "$"
        "EUR" -> "€"
        "JPY" -> "¥"
        "HKD" -> "HK$"
        "CNY", null -> "¥"
        else -> "$currency "
    }

    /**
     * 1M（月度）剩余百分比反算。
     *
     * 背景：`usage_history` 表里有 `quota_month_total / quota_month_used`，
     * 但没有 `quota_month_remaining_percent`（5h / week 都有）。
     * 为让 2x2 环图三档（5H / 1W / 1M）数据形状一致，这里从 total/used 反算。
     *
     * @return 0..100 的剩余百分比，null 表示 total/used 缺失或 total<=0
     */
    fun monthRemainingPercent(): Double? {
        val total = quotaMonthTotal ?: return null
        val used = quotaMonthUsed ?: return null
        if (total <= 0) return null
        return ((total - used) / total * 100).coerceIn(0.0, 100.0)
    }

    /**
     * 相对重置时间标签（环图 2x2 第 4 行专用）。
     *
     * 输入是后端 UsageStatus 推过来的 *_reset_at 秒级时间戳，
     * 输出形如 "5H 0:23 后" / "周 5d 12h 后" / "月 12d 后"。
     *
     * 复用 1x2 widget 既有 [subLabelFor] 的语义（来自前端
     * src/lib/format.ts::formatRelativeReset），但 widget 端在
     * Kotlin 实现以避免跨语言依赖。
     *
     * @param period "5h" | "week" | "month"
     * @param nowSec 当前时间（秒），便于单测注入
     * @return null 表示该周期没有 reset_at 数据（usage_history 表里
     *         也没存 1M 的 reset_at，所以 1M 必然返回 null）
     */
    fun relativeResetLabel(period: String, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val resetAt = when (period) {
            "5h" -> quota5hResetAt
            "week" -> quotaWeekResetAt
            "month" -> null  // 1M reset_at 暂未持久化
            else -> return null
        } ?: return null
        val diffSec = (resetAt - nowSec).coerceAtLeast(0L)
        return when (period) {
            "5h" -> formatShortRelative(diffSec, hours = true, withDays = false)
            "week" -> formatShortRelative(diffSec, hours = true, withDays = true)
            "month" -> formatShortRelative(diffSec, hours = false, withDays = true)
            else -> null
        }
    }

    /**
     * 短相对时间格式器。"Xh Ym 后" / "Xd Yh 后"。
     *
     * @param diffSec 距目标时间的秒数（已截负）
     * @param hours 是否显示"小时"单位（5h / week 周期剩余通常 < 24h）
     * @param withDays 是否升级到"天"粒度（week / month 周期会跨天）
     */
    private fun formatShortRelative(diffSec: Long, hours: Boolean, withDays: Boolean): String {
        if (diffSec < 60) return "< 1m"
        val totalMin = diffSec / 60
        return if (withDays) {
            val d = totalMin / (60 * 24)
            val h = (totalMin % (60 * 24)) / 60
            when {
                d > 0 -> "${d}d ${h}h 后"
                h > 0 -> "${h}h 后"
                else -> "${totalMin}m 后"
            }
        } else if (hours) {
            val h = totalMin / 60
            val m = totalMin % 60
            when {
                h > 0 -> "${h}h ${m}m 后"
                else -> "${m}m 后"
            }
        } else {
            val m = totalMin
            "${m}m 后"
        }
    }

    /**
     * Coding Plan 的最小周期及其剩余值/百分比。
     * 优先级：5h > week > month
     */
    fun smallestPeriod(): PeriodInfo? {
        if (!isCodingPlan) return null
        // 优先用百分比
        quota5hRemainingPercent?.let {
            return PeriodInfo(it, "5h")
        }
        quota5hRemaining?.let {
            return PeriodInfo(it, "5h")
        }
        quotaWeekRemainingPercent?.let {
            return PeriodInfo(it, "week")
        }
        quotaWeekRemaining?.let {
            return PeriodInfo(it, "week")
        }
        quotaMonthRemaining?.let {
            return PeriodInfo(it, "month")
        }
        return null
    }

    data class PeriodInfo(
        val value: Double,
        val period: String,  // "5h" | "week" | "month"
        val isPercent: Boolean = period == "5h" && value <= 100
    )

    /** 进度条百分比（0-100）。返回 null 表示无数据 */
    fun progressPercent(): Double? {
        if (isCodingPlan) {
            // Coding Plan 用 5h 剩余百分比
            quota5hRemainingPercent?.let { return it.coerceIn(0.0, 100.0) }
            // 否则用 used/total
            val total = quota5hTotal
            val used = quota5hUsed
            if (total != null && used != null && total > 0) {
                return ((used / total) * 100).coerceIn(0.0, 100.0)
            }
            return null
        }
        // 余额型：用 used/limit
        val limit = balanceLimit
        val used = balanceUsed
        if (limit != null && used != null && limit > 0) {
            return ((used / limit) * 100).coerceIn(0.0, 100.0)
        }
        return null
    }
}