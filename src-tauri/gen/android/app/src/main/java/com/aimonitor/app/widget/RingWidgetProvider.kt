package com.aimonitor.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 2x2 环图 widget 的 AppWidgetProvider（v0.1.52+）。
 *
 * 与 1x2 的 [UsageWidgetProvider] 平行存在：
 *  - 各自 manifest 注册独立 receiver，launcher 长按菜单显示为
 *    "AI Monitor · 2x2 Ring"。
 *  - 各自独立的轮播 FGS（[RingWidgetCarouselService]），30 秒节奏相同，
 *    但 service 实例、SharedPreferences、index 持久化各自分离。
 *  - 数据读取共享 [WidgetDataReader]（同进程同 SQLite 直读）。
 *
 * 设计要点（沿用 1x2 既有经验）：
 *  - onUpdate / onAppWidgetOptionsChanged 必须 goAsync() 持有 receiver
 *    lock，再 launch coroutine 读 SQLite + updateAppWidget。否则
 *    AppWidgetProvider 进程在 onUpdate 返回后会被系统立即 kill，
 *    coroutine 还没读完 SQLite 就被 SIGKILL 一起杀掉，widget 永远
 *    停在 initialLayout 默认文案。
 *  - 同步首屏渲染（refreshAllBlocking）让 onUpdate 立即给用户看到内容，
 *    不必等下一个 30 秒 tick（service 启动后接管持续刷新）。
 *  - 拉起 carousel service 放在 goAsync() 之后。Android 12+（API 31，
 *    本项目 targetSdk=36）禁止后台启动前台服务，App 不在前台时
 *    startForegroundService 会抛 ForegroundServiceStartNotAllowedException；
 *    runCatching 已吞，但 widget 渲染不依赖 service 是否起来。
 *  - resizeMode="none"：用户无法拖拽，widget 永远是 manifest 规定的
 *    2x2 cell 尺寸。
 */
class RingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate ids=${appWidgetIds.toList()}")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 拉起 carousel：service 启动后按 30 秒间隔循环渲染所有 2x2 widget。
                // 后台 broadcast 上下文中启动 FGS 可能被系统拦截（runCatching 已吞），
                // 真正可靠的启动在 MainActivity 前台窗口。
                RingWidgetCarouselService.start(context)
                // 立即跑一次首屏渲染，避免等 30 秒首屏空白
                refreshAllBlocking(context, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        Log.d(TAG, "onAppWidgetOptionsChanged id=$appWidgetId (resizeMode=none; refresh by manifest size)")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshAllBlocking(context, appWidgetManager, intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "onEnabled: starting ring carousel service")
        RingWidgetCarouselService.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "onDisabled: stopping ring carousel service")
        RingWidgetCarouselService.stop(context)
    }

    /**
     * 同步单次渲染：用于 onUpdate / onAppWidgetOptionsChanged 立即给用户看到内容。
     * 必须在 goAsync() 持有 receiver lock 的协程里调用。
     */
    private suspend fun refreshAllBlocking(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        try {
            val snapshots = WidgetDataReader.latestForEnabledProviders(context)
            val themeId = WidgetDataReader.appTheme(context)
            val hasAnyProvider =
                if (snapshots.isEmpty()) WidgetDataReader.hasEnabledProviders(context) else true
            Log.d(TAG, "refreshAll read ${snapshots.size} snapshots, theme=$themeId, hasAnyProvider=$hasAnyProvider")
            appWidgetIds.forEach { id ->
                val rv = RingWidgetLayoutBuilder.build(context, snapshots, 0, themeId, hasAnyProvider)
                appWidgetManager.updateAppWidget(id, rv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll failed", e)
        }
    }

    companion object {
        private const val TAG = "RingWidgetProvider"
    }
}
