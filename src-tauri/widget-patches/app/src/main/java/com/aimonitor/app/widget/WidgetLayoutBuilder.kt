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
 * v0.1.38 起 layout 为 vertical 两行：
 *   第 1 行：状态点 + 供应商名（左）+ 主数值（右）
 *   第 2 行：quota 补充信息
 *           - Coding Plan：5h 剩余 % │ 周剩余
 *           - 余额型：余额 + 已用百分比
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
   * 1x2 渲染逻辑：
   * - 第 1 行：状态点 + provider 名 + 主数值（balance / Coding Plan 最小周期 %）
   * - 第 2 行：quota 补充（multi-period summary）
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
   * 第 2 行 quota 补充信息：
   * - Coding Plan：拼接 "5H X% │ 周 X"（取 5h 百分比和周剩余数）
   * - 余额型：拼接 "余额 ¥128.50 │ 已用 35%"
   * - 任意一方缺失则省略
   */
  private fun subLabelFor(context: Context, s: UsageSnapshot): String {
    if (s.isCodingPlan) {
      val parts = mutableListOf<String>()
      // 5h 剩余百分比
      s.quota5hRemainingPercent?.let { p ->
        parts += "5H ${p.toInt()}%"
      }
      // 周剩余（绝对值）
      s.quotaWeekRemaining?.let { v ->
        parts += "${context.getString(R.string.widget_week)} ${formatBalance(v)}"
      }
      return parts.joinToString("  │  ")
    }
    // 余额型
    val parts = mutableListOf<String>()
    s.balance?.let { v ->
      parts += "${context.getString(R.string.widget_balance)} ${s.currencySymbol()}${formatBalance(v)}"
    }
    s.balanceLimit?.let { limit ->
      s.balanceUsed?.let { used ->
        if (limit > 0) {
          val pct = (used / limit * 100).toInt()
          parts += "已用 $pct%"
        }
      }
    }
    return parts.joinToString("  │  ")
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
