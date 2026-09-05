package com.aimonitor.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.LruCache

/**
 * 2x2 环图 widget 的位图渲染器。
 *
 * 设计约束：RemoteViews 不支持自定义 View / Canvas 绘制，
 * 仅支持 setImageViewBitmap。所以环图必须预渲染成 [Bitmap] 才能塞进
 * ImageView。本类就是负责画 Bitmap 的纯工具。
 *
 * v0.1.52 起的 2x2 widget 用法：
 *  - Coding Plan：3 环并排，每环中心 "5H" / "1W" / "1M" 标签，
 *    环下文字另起 TextView 渲染（不在本类里）。
 *  - 余额型：单大环，中心显示余额金额。
 *
 * 性能：
 *  - 每个 widget 实例一次 draw 调用，2x2 一次性构造（3 环 + 文字），
 *    不跨帧持有 Canvas。
 *  - 内部 LRU 缓存按 (规格 hash) 复用 Bitmap，30 秒轮播时
 *    相同 (size / 量化 progress / 颜色 / 中心文字) 直接命中。
 *  - 缓存容量 200 entry（覆盖 3 主题 × 100 量化档 × 几组中心文字）。
 */
object RingRenderer {

  /**
   * 渲染规格。所有 px 单位都是最终位图像素。
   *
   * 起始角度 -90°（12 点钟方向），顺时针。中心文字用
   * [Paint.Align.CENTER] 居中，[TextPaint] 自动抗锯齿。
   */
  data class Spec(
    val sizePx: Int,                // 输出正方形位图边长（像素）
    val strokePx: Float,            // 环线宽（像素）
    val progress: Float,            // 0..1 填充比例；>1 截 1，<0 截 0
    val trackColor: Int,            // 环底色 ARGB
    val progressColor: Int,         // 前景色 ARGB
    val centerText: String? = null, // null = 不画中心文字
    val centerTextSizePx: Float = 0f,
    val centerTextColor: Int = 0,
    val capRound: Boolean = true,   // true=Coding Plan 小环；false=余额型大环
  )

  /**
   * 渲染环图 Bitmap。
   *
   * 异常：sizePx<=0 时抛 [IllegalArgumentException]（开发者错误，调用方
   * 应保证 dp 转换正确）；其它运行时异常（OOM 等）由调用方捕获。
   */
  fun draw(spec: Spec): Bitmap {
    require(spec.sizePx > 0) { "sizePx must be > 0, got ${spec.sizePx}" }
    val key = cacheKey(spec)
    cache.get(key)?.let { return it }

    val bmp = Bitmap.createBitmap(spec.sizePx, spec.sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cx = spec.sizePx / 2f
    val cy = spec.sizePx / 2f
    val radius = (spec.sizePx - spec.strokePx) / 2f

    // 1) 底环（轨道）
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = spec.strokePx
      color = spec.trackColor
      strokeCap = if (spec.capRound) Paint.Cap.ROUND else Paint.Cap.BUTT
    }
    canvas.drawCircle(cx, cy, radius, trackPaint)

    // 2) 前景环（按 progress 扫弧）
    val sweep = (spec.progress.coerceIn(0f, 1f)) * 360f
    if (sweep > 0f) {
      val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = spec.strokePx
        color = spec.progressColor
        strokeCap = if (spec.capRound) Paint.Cap.ROUND else Paint.Cap.BUTT
      }
      val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
      // 起始 -90°（12 点钟），顺时针 sweep 度
      canvas.drawArc(arcRect, START_ANGLE, sweep, false, fgPaint)
    }

    // 3) 中心文字（可选）
    if (!spec.centerText.isNullOrEmpty() && spec.centerTextSizePx > 0f) {
      val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spec.centerTextSizePx
        color = spec.centerTextColor
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
      }
      val fm = textPaint.fontMetrics
      // 基线 y = 中心 + (ascent+descent)/2，使视觉居中（不靠 baseline 居中）
      val baselineY = cy - (fm.ascent + fm.descent) / 2f
      // 截断过宽文本：用 TextPaint.measureText + ellipsize，简化处理只画原样
      val text = spec.centerText
      canvas.drawText(text, cx, baselineY, textPaint)
    }

    cache.put(key, bmp)
    return bmp
  }

  /** 起始角度：12 点钟方向 = -90°（Canvas 0° 是 3 点钟） */
  private const val START_ANGLE = -90f

  // ===== LRU 缓存 =====
  //
  // key 形如 "size|stroke|progQ|capR|track|fg|text"，其中 progQ
  // 是 progress 量化到 0..100（1% 粒度，足够视觉无差）。
  // 颜色用 ARGB hex（保证正负不歧义）。
  // centerText 不参与 hash 时区分不了"78%"和"40%"，所以参与。
  private val cache = object : LruCache<String, Bitmap>(200) {
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
      if (evicted) oldValue.recycle()
    }
  }

  private fun cacheKey(s: Spec): String {
    val progQ = (s.progress.coerceIn(0f, 1f) * 100f).toInt()
    val cap = if (s.capRound) 'R' else 'B'
    val text = s.centerText ?: "_"
    return "${s.sizePx}|${s.strokePx.toInt()}|$progQ|$cap|${s.trackColor.toUInt().toString(16)}|${s.progressColor.toUInt().toString(16)}|$text"
  }

  /** 清空缓存（主题切换等场景下让旧位图及时回收）。 */
  fun clearCache() {
    cache.evictAll()
  }
}
