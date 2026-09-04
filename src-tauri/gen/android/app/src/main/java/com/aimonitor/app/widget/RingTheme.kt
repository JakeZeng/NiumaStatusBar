package com.aimonitor.app.widget

import android.graphics.Color
import com.aimonitor.app.R

/**
 * 2x2 环图 widget 的主题与阈值色。
 *
 * 与 1x2 共享的 [WidgetTheme.paletteFor] 来源（前端 ThemeManager 写入
 * SQLite settings 表 key=app_theme），但 2x2 环图需要的"按剩余%
 * 阈值选色"逻辑不能简单复用 [WidgetTheme]——它只给一套固定调色板。
 *
 * 设计：
 *  - 主题色（accent）随 [WidgetTheme] 3 套主题各自的强调色，环图前景用它。
 *  - 阈值色黄/红跨主题统一，确保"危险"语义一致。
 *  - 底环（track）始终是主题色 25% alpha。
 *  - 中心文字颜色与前景色一致（视觉统一，环内颜色有「外延感」）。
 *
 * v0.1.52 起生效，与 1x2 widget 并存；不破坏 1x2 任何行为。
 */
object RingTheme {

  /** 阈值档（基于剩余 %，用于 Coding Plan）。 */
  enum class Threshold {
    /** 剩余 > 50%：绿色安全，主题 accent 担当。 */
    SAFE,
    /** 剩余 20%–50%：警告黄。 */
    WARNING,
    /** 剩余 ≤ 20%：危险红。 */
    DANGER;
    companion object {
      fun forRemaining(remainingPercent: Double?): Threshold = when {
        remainingPercent == null -> SAFE  // 无数据默认走主题色，不吓用户
        remainingPercent > 50.0 -> SAFE
        remainingPercent > 20.0 -> WARNING
        else -> DANGER
      }
    }
  }

  /**
   * 余额型"已用%"阈值（与 Coding Plan 语义镜像）。
   * 余额型不存 remaining%，直接用 used/limit 算 usedPercent 后传入。
   */
  enum class UsedThreshold {
    /** 已用 < 50%：安全主题色。 */
    SAFE,
    /** 已用 50%–80%：警告黄。 */
    WARNING,
    /** 已用 ≥ 80%：危险红。 */
    DANGER;
    companion object {
      fun forUsed(usedPercent: Double?): UsedThreshold = when {
        usedPercent == null -> SAFE
        usedPercent < 50.0 -> SAFE
        usedPercent < 80.0 -> WARNING
        else -> DANGER
      }
    }
  }

  /** 跨主题固定的告警色，与 1x2 widget 共用同色相。 */
  private const val WARNING_AMBER = 0xFFFFB020.toInt()
  private const val DANGER_RED = 0xFFF04438.toInt()

  /** 主题 accent（与 WidgetTheme 保持同步，新增主题时三处一起改）。 */
  private fun accentFor(themeId: String?): Int = when (themeId) {
    "cyberpunk" -> 0xFF00F0FF.toInt()
    "wuxia" -> 0xFFE05633.toInt()
    "guoman" -> 0xFFFF6B9D.toInt()
    else -> 0xFF00F0FF.toInt()  // 默认 cyberpunk
  }

  /** 主题 text_primary（用于底环 alpha 派生）。 */
  private fun textPrimaryFor(themeId: String?): Int = when (themeId) {
    "cyberpunk" -> 0xFFE0E6FF.toInt()
    "wuxia" -> 0xFFF4E4BC.toInt()
    "guoman" -> 0xFF2D3748.toInt()
    else -> 0xFFE0E6FF.toInt()
  }

  /**
   * Coding Plan 环图前景色。
   * @param remainingPercent 0..100（null 时落到 SAFE 主题色，不吓用户）
   */
  fun ringForeground(themeId: String?, remainingPercent: Double?): Int =
    when (Threshold.forRemaining(remainingPercent)) {
      Threshold.SAFE -> accentFor(themeId)
      Threshold.WARNING -> WARNING_AMBER
      Threshold.DANGER -> DANGER_RED
    }

  /**
   * 余额型环图前景色（已用% 阈值镜像）。
   * @param usedPercent 0..100（null 时落到 SAFE 主题色）
   */
  fun balanceRingForeground(themeId: String?, usedPercent: Double?): Int =
    when (UsedThreshold.forUsed(usedPercent)) {
      UsedThreshold.SAFE -> accentFor(themeId)
      UsedThreshold.WARNING -> WARNING_AMBER
      UsedThreshold.DANGER -> DANGER_RED
    }

  /**
   * 底环（track）颜色：主题色 25% alpha。
   * 用 [Color.argb] 而非直接 0x66XXXXXX，方便适配 3 套主题。
   */
  fun ringTrack(themeId: String?): Int {
    val base = textPrimaryFor(themeId)
    val a = 0x40  // 25% alpha（0x40 / 0xFF = 0.25）
    return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
  }

  /** 环内中心文字颜色：与前景色一致（视觉外延）。 */
  fun ringCenterText(themeId: String?, remainingPercent: Double?): Int =
    ringForeground(themeId, remainingPercent)

  /**
   * 余额型单大环中心文字色。
   *
   * 余额型中心显示金额，需要在 SAFE/WARNING/DANGER 三档下都清晰可读。
   * SAFE 走主题 accent；WARNING/DANGER 走固定深色（DANGER_RED 在浅底
   * 上对比足够，但 DARK 文字可读性更好，故统一用深灰黑）。
   *
   * 注意：guoman 主题背景较浅，深色文字本身能很好对比；cyberpunk /
   * wuxia 背景深，深色文字看不见——所以中心文字仍用主题色。
   * 简化为：始终用主题色（与前景色一致）。
   */
  fun balanceCenterText(themeId: String?, usedPercent: Double?): Int =
    balanceRingForeground(themeId, usedPercent)

  /** 给整张 RemoteViews 着色（背景 + 文本色），与 1x2 WidgetTheme.apply 同款。 */
  fun applyToRemoteViews(rv: android.widget.RemoteViews, themeId: String?) {
    val palette = WidgetTheme.paletteFor(themeId) ?: return
    // 2x2 ring layout 的 root id 是 widget_root（与 1x2 同名）
    rv.setInt(R.id.widget_root, "setBackgroundResource", palette.backgroundDrawable)
    // 各 TextView 颜色在 builder 里按 widget 内部 TextView id 设；
    // 这里只负责背景，文本色由 layout builder 调用 colorForXxx 决定
  }
}
