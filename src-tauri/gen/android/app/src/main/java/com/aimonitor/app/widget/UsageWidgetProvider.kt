package com.aimonitor.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * v0.1.39 起：横条本身轮播所有 enabled provider，由前台 service
 * [UsageWidgetCarouselService] 持有 30 秒循环。Provider 自身不再做实际渲染
 * （onUpdate 第一次触发时也只负责「拉起 service」），保留同步首屏渲染
 * 是为了 onUpdate（系统 30 分钟强制回调）能立即刷新一次当前内容。
 *
 * v0.1.45 起：onUpdate / onAppWidgetOptionsChanged 同步首屏渲染改用
 * goAsync() 持有 broadcast receiver lock，再 launch coroutine 读 SQLite +
 * updateAppWidget。否则 AppWidgetProvider 进程在 onUpdate 返回后会被
 * 系统立即 kill，coroutine 还没读完 SQLite 就被 SIGKILL 一起杀掉，
 * widget 永远停在初始空状态。
 *
 * updatePeriodMillis=30min 仍写在 manifest 里，但被 service 的高频循环覆盖。
 * resizeMode="none" 下用户无法拖拽，widget 永远是 manifest 规定的 1x2 cell 尺寸。
 */
class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Log.d(TAG, "onUpdate ids=${appWidgetIds.toList()}")
        // 必须先 goAsync() 拿到 receiver lock，再去做任何可能抛异常的事。
        //
        // v0.1.51 修复：原先 startCarouselService() 排在 goAsync() 之前。
        // Android 12+（API 31，本项目 targetSdk=36）禁止后台启动前台服务，
        // App 不在前台时这行抛 ForegroundServiceStartNotAllowedException，
        // 异常从 BroadcastReceiver.onReceive 冒泡直接搞崩进程，goAsync()
        // 那行根本执行不到 —— 首屏渲染一次都没发生，widget 就永远停在
        // initialLayout 的默认文案 widget_loading / widget_sub_loading。
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 尝试拉起 carousel service：service 启动后按 30 秒间隔循环渲染所有 widget。
                // 后台 broadcast 上下文中启动 FGS 可能被系统拦截（runCatching 已吞），
                // 真正可靠的启动在 MainActivity 前台窗口。无论 service 是否起来，
                // 本次首屏渲染照常进行，退化到 manifest 的 updatePeriodMillis=30min 保底。
                UsageWidgetCarouselService.start(context)
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
        // 第一个小组件被添加到桌面：确保 service 在运行。
        // 后台 broadcast 启动 FGS 可能被系统拦截，真正可靠启动点在 MainActivity；
        // 这里尽力拉起作为兜底，失败被 start() 内部吞掉，不影响首屏。
        Log.d(TAG, "onEnabled: starting carousel service")
        UsageWidgetCarouselService.start(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一个小组件被移除：让 service 退出循环
        Log.d(TAG, "onDisabled: stopping carousel service")
        UsageWidgetCarouselService.stop(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // 预留：清理 WidgetPrefs。当前实现未做 per-widget 配置，无需清理。
    }

    /**
     * 同步单次渲染：用于 onUpdate / onAppWidgetOptionsChanged 立即给用户看到内容，
     * 不必等下一个 30 秒 tick。注意：这只覆盖单帧内容，service 的循环会接管后续。
     *
     * 必须在 goAsync() 持有 receiver lock 的协程里调用，否则 AppWidgetProvider
     * 进程在 onUpdate 返回后会被系统立即 kill，调用方进程被杀时这个函数就
     * 跟着 SIGKILL 了。
     */
    private suspend fun refreshAllBlocking(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        try {
            val snapshots = WidgetDataReader.latestForEnabledProviders(context)
            val themeId = WidgetDataReader.appTheme(context)
            // v0.1.51：snapshots 为空时补查 providers 表，让空态能区分
            // 「用户没添加供应商」和「有供应商但还没拉到数据」。
            // 有数据时不用查，省一次 SQLite 开关。
            val hasAnyProvider =
                if (snapshots.isEmpty()) WidgetDataReader.hasEnabledProviders(context) else true
            Log.d(TAG, "refreshAll read ${snapshots.size} snapshots, theme=$themeId, hasAnyProvider=$hasAnyProvider")
            appWidgetIds.forEach { id ->
                val rv = WidgetLayoutBuilder.build(context, snapshots, 0, themeId, hasAnyProvider)
                appWidgetManager.updateAppWidget(id, rv)
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll failed", e)
        }
    }

    companion object {
        private const val TAG = "UsageWidgetProvider"
    }
}
