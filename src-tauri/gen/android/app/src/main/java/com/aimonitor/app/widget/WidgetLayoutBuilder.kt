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
 *   第 1 行：状态点 + 供应商名（左）+ 主数值（中）+ 页码（右，多 provider 时显示）
 *   第 2 行：quota 补充信息
 *           - Coding Plan：5h 剩余 % │ 周剩余
 *           - 余额型：余额 + 已用百分比
 *
 * v0.1.39 起支持轮播：[build] 增加 [index] 参数，由前台 service 按固定间隔递增，
 * 实现「在 1x2 横条上自动切换显示不同 provider」。单 provider 时不显示页码。
 */
object WidgetLayoutBuilder {

  /**
   * 数据陈旧阈值（秒）。距快照写入超过这么久，第 2 行就改显示
   * 「更新于 N 分钟前」而不是 quota 数字，避免把冻结值当成实时值。
   * 直接复用 [UsageWidgetCarouselService.STALE_THRESHOLD_SEC]，保证
   * 「判定时机」和「展示阈值」不会各自漂移。
   */
  private const val STALE_AFTER_SEC = UsageWidgetCarouselService.STALE_THRESHOLD_SEC

  /** 主入口：填充数据并返回 RemoteViews。themeId 见 [WidgetTheme]。
   *
   * @param hasAnyProvider snapshots 为空时，传 providers 表是否有 enabled 记录：
   *   - true：有 provider 但还没拉到数据 → 显示 "正在同步"
   *   - false：用户没添加任何 provider → 显示 "请打开 App 配置"
   *   - null：未查询（兜底，等同 false）
   *
   * v0.1.51：两个调用点（[UsageWidgetProvider] 首屏、[UsageWidgetCarouselService]
   * 轮播 tick）都会在 snapshots 为空时传真实查询结果，null 只是防御性兜底。
   */
  fun build(
    context: Context,
    snapshots: List<UsageSnapshot>,
    index: Int = 0,
    themeId: String? = null,
    hasAnyProvider: Boolean? = null,
  ): RemoteViews {
    val rv = RemoteViews(context.packageName, R.layout.widget_1x2)
    if (snapshots.isEmpty()) {
      renderEmpty(context, rv, hasAnyProvider)
    } else {
      val safeIndex = index.mod(snapshots.size.coerceAtLeast(1))
      val top = snapshots[safeIndex]
      renderOne(context, rv, top, safeIndex, snapshots.size)
    }
    WidgetTheme.apply(rv, themeId)
    return rv
  }

  /**
   * 1x2 渲染逻辑：
   * - 第 1 行：状态点 + provider 名 + 主数值（balance / Coding Plan 最小周期 %）
   * - 第 2 行：quota 补充（multi-period summary），数据陈旧时改显示「更新于 N 分钟前」
   * - 页码：仅当 total > 1 时显示 "(index+1)/total"，单 provider 时隐藏省空间
   *
   * v0.1.51 起：数据超过 [STALE_AFTER_SEC] 时，第 2 行不再显示 quota 数字。
   * 理由：widget 只是 SQLite 的只读渲染器，App 进程不在时数据会冻结；
   * 此时继续显示 "5H 78%" 会让人误以为是实时值。与其假装实时，
   * 不如把陈旧显式化（状态点同时降为 pending 灰点）。
   */
  private fun renderOne(
    context: Context,
    rv: RemoteViews,
    top: UsageSnapshot,
    index: Int,
    total: Int,
  ) {
    val stale = isStale(top)
    rv.setTextViewText(R.id.widget_provider_name, top.providerName)
    rv.setTextViewText(R.id.widget_big_number, formatBigNumber(top))
    rv.setTextViewText(
      R.id.widget_sub_label,
      if (stale) staleLabel(context, top) else subLabelFor(context, top),
    )
    rv.setImageViewResource(R.id.widget_status_dot, staleDot(top, stale))
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, top.providerId))

    if (total > 1) {
      rv.setTextViewText(R.id.widget_page_index, "${index + 1}/$total")
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.VISIBLE)
    } else {
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.GONE)
    }
  }

  /** 快照是否陈旧（超过 [STALE_AFTER_SEC] 没有新数据写入）。 */
  private fun isStale(s: UsageSnapshot): Boolean =
    (System.currentTimeMillis() / 1000 - s.timestamp) > STALE_AFTER_SEC

  /** 陈旧时的第 2 行文案：「更新于 N 分钟 / 小时 / 天前」。 */
  private fun staleLabel(context: Context, s: UsageSnapshot): String {
    val minutes = ((System.currentTimeMillis() / 1000 - s.timestamp) / 60).coerceAtLeast(0L)
    return when {
      minutes < 60 -> context.getString(R.string.widget_stale_minutes, minutes)
      minutes < 60 * 24 -> context.getString(R.string.widget_stale_hours, minutes / 60)
      else -> context.getString(R.string.widget_stale_days, minutes / (60 * 24))
    }
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

  private fun renderEmpty(
    context: Context,
    rv: RemoteViews,
    hasAnyProvider: Boolean? = null,
  ) {
    // 三种空态文案：
    //   - hasAnyProvider == false：用户没添加任何 provider → 提示配置
    //   - hasAnyProvider == true：有 provider 但 DB 暂无 history → 提示等待同步
    //     （poller 没跑 / 第一次拉取中）
    //   - null：未查过 providers 表（调用方没传），回退到 v0.1.47 默认文案
    val nameRes = when (hasAnyProvider) {
      false -> R.string.widget_no_provider
      true -> R.string.widget_syncing
      null -> R.string.widget_no_provider
    }
    val subRes = when (hasAnyProvider) {
      false -> R.string.widget_no_provider_hint
      true -> R.string.widget_sub_loading
      null -> R.string.widget_sub_loading
    }
    rv.setTextViewText(R.id.widget_provider_name, context.getString(nameRes))
    rv.setTextViewText(R.id.widget_big_number, "—")
    rv.setTextViewText(R.id.widget_sub_label, context.getString(subRes))
    rv.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_status_dot_pending)
  }

  private fun statusDotFor(s: UsageSnapshot): Int = when {
    s.lastError != null -> R.drawable.widget_status_dot_error
    !s.isEnabled -> R.drawable.widget_status_dot_disabled
    else -> R.drawable.widget_status_dot
  }

  /** 陈旧时降为 pending 灰点；轮询失败（lastError）优先保持红点。 */
  private fun staleDot(s: UsageSnapshot, stale: Boolean): Int = when {
    s.lastError != null -> R.drawable.widget_status_dot_error
    stale -> R.drawable.widget_status_dot_pending
    else -> statusDotFor(s)
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
