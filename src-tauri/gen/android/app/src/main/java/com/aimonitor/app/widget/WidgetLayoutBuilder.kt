package com.aimonitor.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.aimonitor.app.MainActivity
import com.aimonitor.app.R
import java.util.Date

/**
 * 集中构造三种尺寸的 RemoteViews。
 * 关键约束：RemoteViews 只支持有限视图类型与少量方法，
 * 所有 "setText / setImageViewResource / setViewVisibility / setOnClickPendingIntent"
 * 之外的操作一律不能使用。
 */
object WidgetLayoutBuilder {

    enum class Size { SMALL, MEDIUM, LARGE }

    /** 主入口：根据 size 选择对应 layout 并填充数据。themeId 见 [WidgetTheme]。 */
    fun build(
        context: Context,
        appWidgetId: Int,
        size: Size,
        snapshots: List<UsageSnapshot>,
        themeId: String? = null,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, layoutId(size))
        if (snapshots.isEmpty()) {
            renderEmpty(context, rv, size)
            WidgetTheme.apply(rv, themeId, size)
            return rv
        }
        when (size) {
            Size.SMALL -> renderSmall(context, rv, snapshots)
            Size.MEDIUM -> renderMedium(context, appWidgetId, rv, snapshots)
            Size.LARGE -> renderLarge(context, appWidgetId, rv, snapshots, themeId)
        }
        WidgetTheme.apply(rv, themeId, size)
        return rv
    }

    // ============== small (2x2) ==============
    private fun renderSmall(context: Context, rv: RemoteViews, data: List<UsageSnapshot>) {
        val top = data.first()
        rv.setTextViewText(R.id.widget_provider_name, top.providerName)
        rv.setTextViewText(R.id.widget_big_number, formatSmallNumber(top))
        rv.setTextViewText(R.id.widget_sub_label, smallSubLabelFor(context, top))
        rv.setImageViewResource(R.id.widget_status_dot, statusDotFor(top))
        rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, top.providerId))
    }

    /**
     * Small 尺寸的主数字：
     * - Coding Plan：显示 "78%"（最小周期的百分比）
     * - 余额型：显示 "¥128.50"
     */
    private fun formatSmallNumber(s: UsageSnapshot): String {
        return if (s.isCodingPlan) {
            val period = s.smallestPeriod()
            if (period != null && period.isPercent) {
                "${period.value.toInt()}%"
            } else {
                period?.let { "${formatBalance(it.value)}" } ?: "—"
            }
        } else {
            val v = s.balance ?: s.balanceLimit ?: return "—"
            "${s.currencySymbol()}${formatBalance(v)}"
        }
    }

    /**
     * Small 尺寸的副标签：
     * - Coding Plan：显示周期 "5小时" / "本周" / "本月"
     * - 余额型：显示 "余额"
     */
    private fun smallSubLabelFor(context: Context, s: UsageSnapshot): String {
        if (s.isCodingPlan) {
            val period = s.smallestPeriod()
            return when (period?.period) {
                "5h" -> context.getString(R.string.widget_5h)
                "week" -> context.getString(R.string.widget_week)
                "month" -> context.getString(R.string.widget_month)
                else -> ""
            }
        }
        return context.getString(R.string.widget_balance)
    }

    // ============== medium (2x3) ==============
    private fun renderMedium(
        context: Context,
        @Suppress("UNUSED_PARAMETER") appWidgetId: Int,
        rv: RemoteViews,
        data: List<UsageSnapshot>,
    ) {
        val top3 = data.take(3)
        // 头部
        rv.setTextViewText(R.id.widget_header, context.getString(R.string.widget_desc_medium))
        // 三行（直接使用编译期 R.id，避免运行时 getIdentifier 失败）
        bindMediumRow(context, rv, R.id.widget_row_1, R.id.widget_row_1_name, R.id.widget_row_1_value, top3.getOrNull(0))
        bindMediumRow(context, rv, R.id.widget_row_2, R.id.widget_row_2_name, R.id.widget_row_2_value, top3.getOrNull(1))
        bindMediumRow(context, rv, R.id.widget_row_3, R.id.widget_row_3_name, R.id.widget_row_3_value, top3.getOrNull(2))
        // 更新时间戳（用列表中最新的一行）
        val latestTs = data.maxOfOrNull { it.timestamp } ?: 0L
        rv.setTextViewText(R.id.widget_updated_at, updatedAtText(context, latestTs))
        // 整体可点：跳转到第一个 provider
        val firstId = top3.firstOrNull()?.providerId
        rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, firstId))
    }

    private fun bindMediumRow(
        context: Context,
        rv: RemoteViews,
        rowId: Int,
        nameId: Int,
        valueId: Int,
        s: UsageSnapshot?,
    ) {
        if (s == null) {
            rv.setViewVisibility(rowId, android.view.View.GONE)
            return
        }
        rv.setViewVisibility(rowId, android.view.View.VISIBLE)
        rv.setTextViewText(nameId, s.providerName)
        rv.setTextViewText(valueId, formatRowValue(s))
        rv.setOnClickPendingIntent(rowId, openAppIntent(context, s.providerId))
    }

    // ============== large (2x4) ==============
    private fun renderLarge(
        context: Context,
        appWidgetId: Int,
        rv: RemoteViews,
        data: List<UsageSnapshot>,
        themeId: String?,
    ) {
        rv.setTextViewText(R.id.widget_header, context.getString(R.string.widget_desc_large))
        rv.setTextViewText(
            R.id.widget_updated_at,
            updatedAtText(context, data.maxOfOrNull { it.timestamp } ?: 0L),
        )
        // ListView 绑定 RemoteViewsService
        val serviceIntent = Intent(context, UsageRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // 主题 id 传给 Factory 给列表行着色
            putExtra("theme", themeId ?: "")
            // 把数据通过 Intent 传给 Service（data 是 Parcelable 不行，直接传 List<String>）
            putExtra("snapshots_count", data.size)
            data.forEachIndexed { idx, s ->
                putExtra("name_$idx", s.providerName)
                putExtra("type_$idx", s.providerType)
                putExtra("value_$idx", formatRowValue(s))
                putExtra("status_$idx", if (s.lastError != null) "error" else "ok")
                putExtra("id_$idx", s.providerId)
            }
        }
        rv.setRemoteAdapter(android.R.id.list, serviceIntent)
        // empty 占位：ListView 为空时显示 empty TextView
        rv.setEmptyView(android.R.id.list, android.R.id.empty)
        // 点击模板（ListView 子项点击会覆盖；fillInIntent 由 RemoteViewsFactory 注入）
        rv.setPendingIntentTemplate(
            android.R.id.list,
            widgetListClickTemplateIntent(context),
        )
        // 整 ListView 区域也可点击作为兜底（打开 App 默认页）
        rv.setOnClickPendingIntent(android.R.id.list, openAppIntent(context, null))
    }

    // ============== helpers ==============
    private fun renderEmpty(context: Context, rv: RemoteViews, size: Size) {
        when (size) {
            Size.SMALL -> {
                rv.setTextViewText(R.id.widget_provider_name, context.getString(R.string.widget_no_provider))
                rv.setTextViewText(R.id.widget_big_number, "—")
                rv.setTextViewText(R.id.widget_sub_label, "")
                rv.setTextViewText(R.id.widget_updated_at, "")
                rv.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_status_dot_disabled)
            }
            Size.MEDIUM -> {
                rv.setTextViewText(R.id.widget_header, context.getString(R.string.widget_no_provider))
                rv.setViewVisibility(R.id.widget_row_1, android.view.View.GONE)
                rv.setViewVisibility(R.id.widget_row_2, android.view.View.GONE)
                rv.setViewVisibility(R.id.widget_row_3, android.view.View.GONE)
            }
            Size.LARGE -> {
                rv.setTextViewText(R.id.widget_header, context.getString(R.string.widget_no_provider))
            }
        }
    }

    private fun layoutId(size: Size): Int = when (size) {
        Size.SMALL -> R.layout.widget_small
        Size.MEDIUM -> R.layout.widget_medium
        Size.LARGE -> R.layout.widget_large
    }

    private fun statusDotFor(s: UsageSnapshot): Int = when {
        s.lastError != null -> R.drawable.widget_status_dot_error
        !s.isEnabled -> R.drawable.widget_status_dot_disabled
        else -> R.drawable.widget_status_dot
    }

    private fun formatRowValue(s: UsageSnapshot): String {
        val v = s.primaryValue() ?: return "—"
        return if (s.isCodingPlan) {
            "5H ${v.toInt()}%"
        } else {
            "${s.primarySuffix()}${formatBalance(v)}"
        }
    }

    private fun formatBalance(v: Double): String =
        if (v >= 1000) String.format("%.0f", v) else String.format("%.2f", v)

    private fun updatedAtText(context: Context, ts: Long): String {
        if (ts <= 0L) return ""
        val date = Date(ts * 1000L)
        val fmt = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val time = DateFormat.format(fmt, date).toString()
        return context.getString(R.string.widget_updated_at, time)
    }

    private fun openAppIntent(context: Context, providerId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (providerId != null) putExtra(EXTRA_PROVIDER_ID, providerId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, providerId?.hashCode() ?: 0, intent, flags)
    }

    const val EXTRA_PROVIDER_ID = "niuma_provider_id"
}