package com.aimonitor.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.aimonitor.app.MainActivity
import com.aimonitor.app.R

/**
 * 1x2 widget 的 RemoteViews 构造器。
 *
 * 关键约束：RemoteViews 只支持有限视图类型与少量方法，
 * 所有 "setText / setImageViewResource / setViewVisibility / setOnClickPendingIntent"
 * 之外的操作一律不能使用。
 *
 * 早期版本（v0.1.18–v0.1.36）支持 SMALL/MEDIUM/LARGE 三种 size；v0.1.37 起
 * 仅发布 1x2 一种 widget，这里也简化为无 size 参数的 build。
 */
object WidgetLayoutBuilder {

  /** 主入口：填充数据并返回 RemoteViews。themeId 见 [WidgetTheme]。 */
  fun build(
    context: Context,
    snapshots: List<UsageSnapshot>,
    themeId: String? = null,
  ): RemoteViews {
    val rv = RemoteViews(context.packageName, R.layout.widget_1x2)
    if (snapshots.isEmpty()) {
      renderEmpty(context, rv)
    } else {
      renderOne(context, rv, snapshots.first())
    }
    WidgetTheme.apply(rv, themeId)
    return rv
  }

  /**
   * 1x2 渲染逻辑：单供应商横条，左中右依次为「状态点 / 供应商名 / 主数值 / 副标签」。
   * Coding Plan 显示周期百分比；余额型显示余额金额。
   */
  private fun renderOne(context: Context, rv: RemoteViews, top: UsageSnapshot) {
    rv.setTextViewText(R.id.widget_provider_name, top.providerName)
    rv.setTextViewText(R.id.widget_big_number, formatBigNumber(top))
    rv.setTextViewText(R.id.widget_sub_label, subLabelFor(context, top))
    rv.setImageViewResource(R.id.widget_status_dot, statusDotFor(top))
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, top.providerId))
  }

  /**
   * 主数值：
   * - Coding Plan：显示 "78%"（最小周期百分比）
   * - 余额型：显示 "¥128.50"
   */
  private fun formatBigNumber(s: UsageSnapshot): String {
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
   * 副标签：
   * - Coding Plan：显示周期 "5h" / "week" / "month"
   * - 余额型：显示 "余额"
   */
  private fun subLabelFor(context: Context, s: UsageSnapshot): String {
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

  private fun renderEmpty(context: Context, rv: RemoteViews) {
    rv.setTextViewText(R.id.widget_provider_name, context.getString(R.string.widget_no_provider))
    rv.setTextViewText(R.id.widget_big_number, "—")
    rv.setTextViewText(R.id.widget_sub_label, "")
    rv.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_status_dot_disabled)
  }

  private fun statusDotFor(s: UsageSnapshot): Int = when {
    s.lastError != null -> R.drawable.widget_status_dot_error
    !s.isEnabled -> R.drawable.widget_status_dot_disabled
    else -> R.drawable.widget_status_dot
  }

  private fun formatBalance(v: Double): String =
    if (v >= 1000) String.format("%.0f", v) else String.format("%.2f", v)

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
