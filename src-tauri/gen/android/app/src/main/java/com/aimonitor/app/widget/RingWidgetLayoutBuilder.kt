package com.aimonitor.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.aimonitor.app.MainActivity
import com.aimonitor.app.R
import kotlin.math.roundToInt

/**
 * 2x2 环图 widget 的 RemoteViews 构造器（v0.1.52+）。
 *
 * 与 [WidgetLayoutBuilder] 平行存在，互不依赖。
 * WidgetLayoutBuilder 服务 1x2 横条；本类服务 2x2 卡片。
 *
  * 形态分支（按 [UsageSnapshot.isCodingPlan]）：
  *  - Coding Plan：2 或 3 空心环并排（5H / 1W / [1M]），
  *    1M 环在无月剩余数据时隐藏；环下剩余%，第 4 行
  *    2 或 3 段相对重置时间。
  *  - 余额型：单大环，环心余额金额，环下已用%，第 4 行余额/总额。
 *
 * 复用既有约定：
 *  - 状态点语义（OK / error / disabled / pending）由 [statusDotFor] 计算
 *  - 主题色由 [RingTheme] 提供阈值色 + 主题 accent
 *  - 陈旧阈值（15 分钟）由 [UsageWidgetCarouselService.STALE_THRESHOLD_SEC]
 *    提供，保证「判定时机」与「1x2 既有逻辑」不会漂移
 *  - 点击行为与 1x2 一致：start MainActivity + extra provider_id
 */
object RingWidgetLayoutBuilder {

  private const val STALE_AFTER_SEC = UsageWidgetCarouselService.STALE_THRESHOLD_SEC

  // 物理 dp → 像素（widget host 用 density 缩放 RemoteViews；环图 Bitmap
  // 必须在调用方按 density 算好 px 再喂给 RingRenderer，避免 launcher 之间
  // 尺寸差异造成糊字 / 锯齿）
  private fun dpToPx(context: Context, dp: Float): Float =
    dp * context.resources.displayMetrics.density

  /**
   * 主入口。返回已填充数据的 RemoteViews。
   *
   * @param themeId 与 1x2 共用：null 时回退系统配色。
   * @param hasAnyProvider 见 [WidgetLayoutBuilder.build] 注释。
   */
  fun build(
    context: Context,
    snapshots: List<UsageSnapshot>,
    index: Int = 0,
    themeId: String? = null,
    hasAnyProvider: Boolean? = null,
  ): RemoteViews {
    // 1) snapshots 为空：用 coding layout 做"无内容"占位（initialLayout
    //    也是 coding，省一次 layout 切换）；renderEmpty 内部对两种 layout
    //    id 都做 setTextViewText，coding layout 上多余的 id 会被忽略。
    if (snapshots.isEmpty()) {
      val rv = RemoteViews(context.packageName, R.layout.widget_2x2_coding)
      renderEmpty(context, rv, hasAnyProvider)
      RingTheme.applyToRemoteViews(rv, themeId)
      return rv
    }

    val safeIndex = index.mod(snapshots.size.coerceAtLeast(1))
    val top = snapshots[safeIndex]
    val isCoding = top.isCodingPlan
    val layout = if (isCoding) R.layout.widget_2x2_coding else R.layout.widget_2x2_balance
    val rv = RemoteViews(context.packageName, layout)

    val stale = isStale(top)
    if (isCoding) {
      renderCoding(context, rv, top, safeIndex, snapshots.size, themeId, stale)
    } else {
      renderBalance(context, rv, top, safeIndex, snapshots.size, themeId, stale)
    }
    RingTheme.applyToRemoteViews(rv, themeId)
    return rv
  }

  // ===== Coding Plan 三环 =====

  private fun renderCoding(
    context: Context,
    rv: RemoteViews,
    s: UsageSnapshot,
    index: Int,
    total: Int,
    themeId: String?,
    stale: Boolean,
  ) {
    // 第 1 行
    rv.setTextViewText(R.id.widget_provider_name, s.providerName)
    rv.setImageViewResource(R.id.widget_status_dot, staleDot(s, stale))
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, s.providerId))
    if (total > 1) {
      rv.setTextViewText(R.id.widget_page_index, "${index + 1}/$total")
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.VISIBLE)
    } else {
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.GONE)
    }

    // 5H / 1W / 1M 三个环（Bitmap 渲染）
    val sizePx = dpToPx(context, 44f).roundToInt().coerceAtLeast(8)
    val strokePx = dpToPx(context, 4f)

    val pct5h = if (stale) null else s.quota5hRemainingPercent
    val pct1w = if (stale) null else s.quotaWeekRemainingPercent
    val pct1m = if (stale) null else s.monthRemainingPercent()
    val hasMonth = pct1m != null

    renderOneRing(
      rv,
      ringId = R.id.widget_ring_5h,
      pctId = R.id.widget_pct_5h,
      sizePx = sizePx, strokePx = strokePx,
      centerText = context.getString(R.string.widget_5h),
      centerTextSizePx = dpToPx(context, 11f),
      remainingPercent = pct5h,
      themeId = themeId,
      stale = stale,
    )
    renderOneRing(
      rv,
      ringId = R.id.widget_ring_1w,
      pctId = R.id.widget_pct_1w,
      sizePx = sizePx, strokePx = strokePx,
      centerText = context.getString(R.string.widget_week),
      centerTextSizePx = dpToPx(context, 11f),
      remainingPercent = pct1w,
      themeId = themeId,
      stale = stale,
    )
    if (hasMonth) {
      renderOneRing(
        rv,
        ringId = R.id.widget_ring_1m,
        pctId = R.id.widget_pct_1m,
        sizePx = sizePx, strokePx = strokePx,
        centerText = context.getString(R.string.widget_month),
        centerTextSizePx = dpToPx(context, 11f),
        remainingPercent = pct1m,
        themeId = themeId,
        stale = stale,
      )
    } else {
      rv.setViewVisibility(R.id.widget_col_1m, android.view.View.GONE)
    }

    // 第 4 行：重置时间（根据月数据是否存在决定 2 段或 3 段）
    rv.setTextViewText(
      R.id.widget_reset_row,
      if (stale) staleLabel(context, s)
      else if (hasMonth) resetRowFor(context, s)
      else resetRow2Segment(context, s),
    )
  }

  /**
   * 单环渲染：画 Bitmap 到 ImageView + 设置环下百分比 TextView。
   * 环颜色按 [remainingPercent] 阈值选取（与 RingTheme.ringForeground 对齐）。
   *
   * 注：[centerText] 和 [centerTextSizePx] 已在调用方解析好（避免
   * 在本函数里再访问 Context），所以本函数不收 context 参数。
   */
  private fun renderOneRing(
    rv: RemoteViews,
    ringId: Int,
    pctId: Int,
    sizePx: Int,
    strokePx: Float,
    centerText: String,
    centerTextSizePx: Float,
    remainingPercent: Double?,
    themeId: String?,
    stale: Boolean,
  ) {
    // progress: Coding Plan 用"已用"方向 = 1 - remaining/100，让"已用越多环越长"
    val progress = when {
      stale -> 0f
      remainingPercent == null -> 0f
      else -> (1.0 - remainingPercent.coerceIn(0.0, 100.0) / 100.0).toFloat()
    }
    val fg = if (stale) {
      // 陈旧：灰前景
      0xFF9E9E9E.toInt()
    } else {
      RingTheme.ringForeground(themeId, remainingPercent)
    }
    val track = if (stale) {
      0x339E9E9E
    } else {
      RingTheme.ringTrack(themeId)
    }
    val centerColor = if (stale) 0xFF9E9E9E.toInt() else fg

    val bmp = RingRenderer.draw(
      RingRenderer.Spec(
        sizePx = sizePx,
        strokePx = strokePx,
        progress = progress,
        trackColor = track,
        progressColor = fg,
        centerText = centerText,
        centerTextSizePx = centerTextSizePx,
        centerTextColor = centerColor,
        capRound = true,
      )
    )
    rv.setImageViewBitmap(ringId, bmp)
    rv.setTextViewText(pctId, formatPct(remainingPercent))
    rv.setTextColor(pctId, centerColor)
  }

  /** Coding Plan 第 4 行（有月数据）："5H 0:23 后  │  周 5d 后  │  月 —"。 */
  private fun resetRowFor(context: Context, s: UsageSnapshot): String {
    val r5h = s.relativeResetLabel("5h") ?: "—"
    val rw = s.relativeResetLabel("week") ?: "—"
    val rm = s.relativeResetLabel("month") ?: "—"
    return "${context.getString(R.string.widget_5h)} $r5h  │  ${context.getString(R.string.widget_week)} $rw  │  ${context.getString(R.string.widget_month)} $rm"
  }

  /** Coding Plan 第 4 行（无月数据）："5H 0:23 后  │  周 5d 后"。 */
  private fun resetRow2Segment(context: Context, s: UsageSnapshot): String {
    val r5h = s.relativeResetLabel("5h") ?: "—"
    val rw = s.relativeResetLabel("week") ?: "—"
    return "${context.getString(R.string.widget_5h)} $r5h  │  ${context.getString(R.string.widget_week)} $rw"
  }

  // ===== 余额型 单大环 =====

  private fun renderBalance(
    context: Context,
    rv: RemoteViews,
    s: UsageSnapshot,
    index: Int,
    total: Int,
    themeId: String?,
    stale: Boolean,
  ) {
    // 第 1 行
    rv.setTextViewText(R.id.widget_provider_name, s.providerName)
    rv.setImageViewResource(R.id.widget_status_dot, staleDot(s, stale))
    rv.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context, s.providerId))
    if (total > 1) {
      rv.setTextViewText(R.id.widget_page_index, "${index + 1}/$total")
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.VISIBLE)
    } else {
      rv.setViewVisibility(R.id.widget_page_index, android.view.View.GONE)
    }

    val sizePx = dpToPx(context, 96f).roundToInt().coerceAtLeast(8)
    val strokePx = dpToPx(context, 6f)
    val balance = s.balance
    val limit = s.balanceLimit
    val used = s.balanceUsed
    // 余额型"已用%"：优先 used/limit，缺数据回退 0
    val usedPercent: Double? = if (stale) null
      else if (limit != null && used != null && limit > 0) {
        (used / limit * 100.0).coerceIn(0.0, 100.0)
      } else null

    val progress = usedPercent?.let { (it / 100.0).toFloat() } ?: 0f
    val fg = if (stale) 0xFF9E9E9E.toInt()
      else RingTheme.balanceRingForeground(themeId, usedPercent)
    val track = if (stale) 0x339E9E9E
      else RingTheme.ringTrack(themeId)
    val centerText = balance?.let { "${s.currencySymbol()}${formatBalance(it)}" } ?: "—"
    val centerSize = dpToPx(context, 16f)
    val centerColor = if (stale) 0xFF9E9E9E.toInt() else fg

    val bmp = RingRenderer.draw(
      RingRenderer.Spec(
        sizePx = sizePx,
        strokePx = strokePx,
        progress = progress,
        trackColor = track,
        progressColor = fg,
        centerText = centerText,
        centerTextSizePx = centerSize,
        centerTextColor = centerColor,
        capRound = false,  // 大环用 BUTT，弧端对齐更精准
      )
    )
    rv.setImageViewBitmap(R.id.widget_ring_balance, bmp)
    rv.setTextViewText(
      R.id.widget_pct_balance,
      if (stale) staleLabel(context, s)
      else context.getString(R.string.widget_balance_used, usedPercent?.roundToInt() ?: 0)
    )
    rv.setTextColor(R.id.widget_pct_balance, centerColor)

    // 第 4 行：余额 / 总额
    rv.setTextViewText(
      R.id.widget_balance_row,
      if (stale) staleLabel(context, s)
      else context.getString(
        R.string.widget_balance_row,
        balance?.let { "${s.currencySymbol()}${formatBalance(it)}" } ?: "—",
        limit?.let { "${s.currencySymbol()}${formatBalance(it)}" } ?: "—",
      )
    )
  }

  // ===== 空态 / 异常 =====

  private fun renderEmpty(
    context: Context,
    rv: RemoteViews,
    hasAnyProvider: Boolean? = null,
  ) {
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
    rv.setTextViewText(R.id.widget_reset_row, context.getString(subRes))
    rv.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_status_dot_pending)
  }

  // ===== 工具 =====

  private fun isStale(s: UsageSnapshot): Boolean =
    (System.currentTimeMillis() / 1000 - s.timestamp) > STALE_AFTER_SEC

  private fun staleLabel(context: Context, s: UsageSnapshot): String {
    val minutes = ((System.currentTimeMillis() / 1000 - s.timestamp) / 60).coerceAtLeast(0L)
    return when {
      minutes < 60 -> context.getString(R.string.widget_stale_minutes, minutes)
      minutes < 60 * 24 -> context.getString(R.string.widget_stale_hours, minutes / 60)
      else -> context.getString(R.string.widget_stale_days, minutes / (60 * 24))
    }
  }

  private fun statusDotFor(s: UsageSnapshot): Int = when {
    s.lastError != null -> R.drawable.widget_status_dot_error
    !s.isEnabled -> R.drawable.widget_status_dot_disabled
    else -> R.drawable.widget_status_dot
  }

  private fun staleDot(s: UsageSnapshot, stale: Boolean): Int = when {
    s.lastError != null -> R.drawable.widget_status_dot_error
    stale -> R.drawable.widget_status_dot_pending
    else -> statusDotFor(s)
  }

  /** "78%" / "—"（null / 0 视为"—"，与 1x2 兜底保持一致） */
  private fun formatPct(p: Double?): String = when {
    p == null -> "—"
    p <= 0.0 -> "0%"
    p >= 100.0 -> "100%"
    else -> "${p.roundToInt()}%"
  }

  private fun formatBalance(v: Double): String =
    if (v >= 1000) String.format("%.0f", v) else String.format("%.2f", v)

  private fun openAppIntent(context: Context, providerId: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      if (providerId != null) putExtra(WidgetLayoutBuilder.EXTRA_PROVIDER_ID, providerId)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    return PendingIntent.getActivity(context, providerId?.hashCode() ?: 0, intent, flags)
  }
}
