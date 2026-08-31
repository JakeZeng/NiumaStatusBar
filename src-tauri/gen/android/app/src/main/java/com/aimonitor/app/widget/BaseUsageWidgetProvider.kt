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
 * 三种尺寸共享的基类。子类（Small/Medium/Large）只是 manifest 区分用。
 *
 * onUpdate 由系统在 updatePeriodMillis=30min 时触发（Android 限制最小值，
 * 无前台服务无法更短）。
 *
 * 所有 manifest 都设了 resizeMode="none"（v0.1.36 起），用户长按 widget
 * 不会进入调整大小模式；onAppWidgetOptionsChanged 仅在 launcher
 * 主动调用 updateAppWidgetOptions 时才会触发，日常不会进。
 */
abstract class BaseUsageWidgetProvider : AppWidgetProvider() {

    protected abstract val size: WidgetLayoutBuilder.Size

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate size=$size ids=${appWidgetIds.toList()}")
        refreshAll(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // resizeMode="none" 下用户无法拖拽；该回调仅在 launcher 主动更新
        // options 时触发。仍按子类 hardcode 的 size 重建一次保证一致。
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
                    val rv = WidgetLayoutBuilder.build(context, id, size, snapshots, themeId)
                    appWidgetManager.updateAppWidget(id, rv)
                }
                // 大组件 ListView 数据需主动通知 RemoteViewsService 刷新
                if (size == WidgetLayoutBuilder.Size.LARGE) {
                    appWidgetManager.notifyAppWidgetViewDataChanged(
                        appWidgetIds,
                        android.R.id.list,
                    )
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