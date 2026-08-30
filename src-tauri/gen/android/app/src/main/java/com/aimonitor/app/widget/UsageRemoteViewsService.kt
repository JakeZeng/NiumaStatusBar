package com.aimonitor.app.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * 为大尺寸组件（ListView）提供 RemoteViews 数据。
 *
 * 当前实现策略：因为跨进程传递自定义数据类比较繁琐，
 * 我们在 WidgetLayoutBuilder.renderLarge 中通过 Intent extras 把数据
 * 序列化进 Service，这里反序列化读出。后续可改为 AIDL 或 Messenger。
 */
class UsageRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return UsageRemoteViewsFactory(applicationContext, intent)
    }
}