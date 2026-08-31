package com.aimonitor.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 唯一的桌面组件 provider：1x2 横条（1 行高 × 2 列宽）。
 *
 * v0.1.37 起只发布一个 widget。早期版本（v0.1.18–v0.1.36）有三个 size
 * （2x2 / 2x3 / 2x4），用户反馈 widget picker 里选项太多、实际只需要
 * 一个紧凑横条，故统一为一个。
 *
 * onUpdate 由系统在 updatePeriodMillis=30min 时触发（Android 限制最小值，
 * 无前台服务无法更短）。resizeMode="none" 下用户无法拖拽，widget
 * 永远是 manifest 规定的 1x2 cell 尺寸；onAppWidgetOptionsChanged
 * 回调日常不会触发，保留仅作 defense-in-depth。
 */
class UsageWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    Log.d(TAG, "onUpdate ids=${appWidgetIds.toList()}")
    refreshAll(context, appWidgetManager, appWidgetIds)
  }

  override fun onAppWidgetOptionsChanged(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
    newOptions: Bundle,
  ) {
    super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    Log.d(TAG, "onAppWidgetOptionsChanged id=$appWidgetId (resizeMode=none; refresh by manifest size)")
    refreshAll(context, appWidgetManager, intArrayOf(appWidgetId))
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    super.onDeleted(context, appWidgetIds)
    // 预留：清理 WidgetPrefs。当前实现未做 per-widget 配置，无需清理。
  }

  private fun refreshAll(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val snapshots = WidgetDataReader.latestForEnabledProviders(context)
        val themeId = WidgetDataReader.appTheme(context)
        Log.d(TAG, "refreshAll read ${snapshots.size} snapshots, theme=$themeId")
        appWidgetIds.forEach { id ->
          val rv = WidgetLayoutBuilder.build(context, snapshots, themeId)
          appWidgetManager.updateAppWidget(id, rv)
        }
      } catch (e: Exception) {
        Log.e(TAG, "refreshAll failed", e)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    private const val TAG = "UsageWidgetProvider"
  }
}
