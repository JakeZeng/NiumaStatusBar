package com.aimonitor.app.widget

import android.graphics.Color
import android.widget.RemoteViews
import com.aimonitor.app.R

/**
 * Widget 配色与 App 主题同步。
 *
 * 主题 id（cyberpunk / wuxia / guoman）由前端 ThemeManager 通过
 * `set_app_theme` IPC 写入 SQLite settings 表（key = app_theme），
 * widget 进程经 WidgetDataReader 直读。取值与 src/index.css 里
 * [data-theme="..."] 的 CSS 变量（--bg-card / --text-* / --border-color）对齐。
 *
 * themeId 为 null 或未知时不做任何覆盖，回退到 res 里的系统明暗配色
 * （values/widget_colors.xml + values-night/）。
 *
 * v0.1.37 起 widget 只有 1x2 一种 size，apply() 不再需要 size 参数。
 */
object WidgetTheme {

    data class Palette(
        val backgroundDrawable: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accent: Int,
    )

    fun paletteFor(themeId: String?): Palette? = when (themeId) {
        "cyberpunk" -> Palette(
            backgroundDrawable = R.drawable.widget_background_cyberpunk,
            textPrimary = Color.parseColor("#E0E6FF"),
            textSecondary = Color.parseColor("#8A9BCF"),
            textMuted = Color.parseColor("#5A6B9C"),
            accent = Color.parseColor("#00F0FF"),
        )
        "wuxia" -> Palette(
            backgroundDrawable = R.drawable.widget_background_wuxia,
            textPrimary = Color.parseColor("#F4E4BC"),
            textSecondary = Color.parseColor("#C9A876"),
            textMuted = Color.parseColor("#8A7858"),
            accent = Color.parseColor("#E05633"),
        )
        "guoman" -> Palette(
            backgroundDrawable = R.drawable.widget_background_guoman,
            textPrimary = Color.parseColor("#2D3748"),
            textSecondary = Color.parseColor("#4A5568"),
            textMuted = Color.parseColor("#718096"),
            accent = Color.parseColor("#FF6B9D"),
        )
        else -> null
    }

    /** 给整卡 RemoteViews 着色（背景 + 1x2 布局的文本 id）。 */
    fun apply(rv: RemoteViews, themeId: String?) {
        val p = paletteFor(themeId) ?: return
        rv.setInt(R.id.widget_root, "setBackgroundResource", p.backgroundDrawable)
        rv.setInt(R.id.widget_provider_name, "setTextColor", p.textPrimary)
        rv.setInt(R.id.widget_big_number, "setTextColor", p.accent)
        rv.setInt(R.id.widget_sub_label, "setTextColor", p.textSecondary)
    }
}
