package com.aimonitor.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 三种尺寸共享的基类。子类（Small/Medium/Large）只是 manifest 区分用。
 *
 * onUpdate 由系统在 updatePeriodMillis=30min 时触发（Android 限制最小值，
 * 无前台服务无法更短）。同时响应 onAppWidgetOptionsChanged（用户拖拽调整大小）。
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
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 大小调整时强制刷新一次以保证 layout 与新尺寸匹配
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
                Log.d(TAG, "refreshAll read ${snapshots.size} snapshots")
                appWidgetIds.forEach { id ->
                    val rv = WidgetLayoutBuilder.build(context, id, size, snapshots)
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