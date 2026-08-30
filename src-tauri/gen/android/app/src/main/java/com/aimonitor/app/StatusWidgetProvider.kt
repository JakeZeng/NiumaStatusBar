package com.aimonitor.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 桌面小组件（App Widget）入口。
 *
 * 组件本身不轮询数据，只负责在「被放置 / 系统定时刷新」时拉起前台服务
 * [StatusWidgetService]，由该服务每几秒轮播一次供应商并刷新 RemoteViews。
 */
class StatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 确保轮播服务在运行；服务内部会自行刷新所有实例
        context.startWidgetService(StatusWidgetService.ACTION_START)
    }

    override fun onEnabled(context: Context) {
        // 第一个小组件被添加到桌面
        context.startWidgetService(StatusWidgetService.ACTION_START)
    }

    override fun onDisabled(context: Context) {
        // 最后一个小组件被移除，停止后台服务
        context.startWidgetService(StatusWidgetService.ACTION_STOP)
    }

    companion object {
        fun Context.startWidgetService(action: String) {
            val intent = Intent(this, StatusWidgetService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this@startWidgetService.startForegroundService(intent)
            } else {
                this@startWidgetService.startService(intent)
            }
        }
    }
}
