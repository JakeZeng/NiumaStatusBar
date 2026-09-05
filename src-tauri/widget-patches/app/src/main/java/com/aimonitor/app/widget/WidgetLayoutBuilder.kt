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
 * v0.1.56 起 layout 改为 vertical 三行：
 *   第 1 行（~14dp）：状态点 + 供应商名 + 页码
 *   第 2 行（weight=1，14sp bold，center）：主信息
 *              - Coding Plan（minimax_* / volcengine_*）："5H 78%  │  Week 12.5"
 *              - 余额型（deepseek 等）："¥128.50"
 *   第 3 行（~10dp，8sp muted，右对齐）：相对更新时间（"刚刚" / "X 分钟前" / ...）
 *
 * v0.1.56 起去掉 widget_sub_label 和 row-2 陈旧降级文案（"更新于 N 分钟前"）。
 * 理由：陈旧状态现在由第 3 行的相对时间天然表达，row 2 继续展示主数值更直观；
 * status_dot 仍按 v0.1.51 逻辑着色（红=error / 灰=stale / 绿=正常）。
 *
 * v0.1.39 起支持轮播：[build] 增加 [index] 参数，由前台 service 按固定间隔递增，
 * 实现「在 1x2 横条上自动切换显示不同 provider」。单 provider 时不显示页码。
 */
object WidgetLayoutBuilder {

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
   * 1x2 渲染逻辑（v0.1.56 三行版）：
   * - Row 1：状态点 + provider 名 + 页码
   * - Row 2：主信息（Coding Plan 显示 5h%+周用量，余额型显示余额），14sp bold
   * - Row 3：相对更新时间，"刚刚" / "X 分钟前" / ...
   *
   * v0.1.56 起 row 2 不再做陈旧降级（"更新于 N 分钟前"），陈旧状态由 row 3 + status_dot 共同承担。
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
    rv.setTextViewText(R.id.widget_big_number, formatMainRow(top))
    rv.setTextViewText(R.id.widget_updated_at, updatedAtLabel(context, top))
    rv.setImageViewResource(R.id.widget_status_dot, staleDot(top, stale))
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, top.providerId))

    if (total > 1) {
      rv.setTextViewText(R.id.widget_page_index, "${index + 1}/$total")
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.VISIBLE)
    } else {
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.GONE)
    }
  }

  /** 数据陈旧阈值（秒）。距快照写入超过这么久，状态点降为 pending 灰。
   * 直接复用 [UsageWidgetCarouselService.STALE_THRESHOLD_SEC]，保证
   * 「判定时机」和「展示阈值」不会各自漂移。
   *
   * 注意必须是 `val` 而非 `const val`：Kotlin 限制 const val 必须是
   * 编译期字面量，跨 object 引用其他 const val 会被编译器拒绝
   * （"Const val initializer should be a constant value"）。
   * 运行时再 resolve 一次无开销。
   */
  private val STALE_AFTER_SEC = UsageWidgetCarouselService.STALE_THRESHOLD_SEC

  /** 快照是否陈旧（超过 [STALE_AFTER_SEC] 没有新数据写入）。 */
  private fun isStale(s: UsageSnapshot): Boolean =
    (System.currentTimeMillis() / 1000 - s.timestamp) > STALE_AFTER_SEC

  /**
   * 第 2 行主信息（v0.1.56 起合并旧 big_number + sub_label 两行内容）：
   * - Coding Plan："5H 78%  │  Week 12.5"（5h 剩余百分比 + 周剩余绝对值）
   * - 余额型："¥128.50"
   * 任意一方缺失则降级（缺 5h% → 只显示周剩余；缺周剩余 → 只显示 5h%；都缺 → "—"）。
   */
  private fun formatMainRow(s: UsageSnapshot): String {
    return if (s.isCodingPlan) {
      val parts = mutableListOf<String>()
      s.quota5hRemainingPercent?.let { p ->
        parts += "5H ${p.toInt()}%"
      }
      s.quotaWeekRemaining?.let { v ->
        parts += "Week ${formatBalance(v)}"
      }
      when {
        parts.isEmpty() -> "—"
        parts.size == 1 -> parts[0]
        else -> parts.joinToString("  │  ")
      }
    } else {
      val v = s.balance ?: s.balanceLimit ?: return "—"
      "${s.currencySymbol()}${formatBalance(v)}"
    }
  }

  /**
   * 第 3 行相对更新时间文案：
   * - < 1 分钟："刚刚"
   * - < 1 小时："X 分钟前"
   * - < 1 天  ："X 小时前"
   * - 否则     ："X 天前"
   *
   * v0.1.56 新增。区别于 [UsageWidgetCarouselService.staleLabel]：本函数始终显示时间，
   * 没有"更新于"前缀（row 3 本身即表示时间），且分桶粒度更细（含"刚刚"和"天"两档）。
   */
  private fun updatedAtLabel(context: Context, s: UsageSnapshot): String {
    val seconds = (System.currentTimeMillis() / 1000 - s.timestamp).coerceAtLeast(0L)
    val minutes = seconds / 60
    return when {
      seconds < 60 -> context.getString(R.string.widget_just_now)
      minutes < 60 -> context.getString(R.string.widget_updated_minutes, minutes.toInt())
      minutes < 60 * 24 -> context.getString(R.string.widget_updated_hours, (minutes / 60).toInt())
      else -> context.getString(R.string.widget_updated_days, (minutes / (60 * 24)).toInt())
    }
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
    rv.setTextViewText(R.id.widget_provider_name, context.getString(nameRes))
    rv.setTextViewText(R.id.widget_big_number, "—")
    rv.setTextViewText(R.id.widget_updated_at, context.getString(R.string.widget_sub_loading))
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
