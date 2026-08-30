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