package com.aimonitor.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.aimonitor.app.MainActivity
import com.aimonitor.app.R

/**
 * 大尺寸 ListView 数据工厂。
 * 数据来自 WidgetLayoutBuilder.renderLarge 写入的 Intent extras。
 *
 * 每次系统请求数据时（如用户滚动 / notifyAppWidgetViewDataChanged）会调用
 * onDataSetChanged()，此处直接重读 extras（extras 由 Provider 在 onUpdate 时
 * 通过 setRemoteAdapter 传入同一个 intent）。
 */
class UsageRemoteViewsFactory(
    private val context: Context,
    private val seedIntent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val providerId: String,
        val name: String,
        val value: String,
        val status: String,  // "ok" | "error"
    )

    private var rows: List<Row> = emptyList()
    private var themeId: String = ""

    override fun onCreate() {
        // nothing
    }

    override fun onDataSetChanged() {
        themeId = seedIntent.getStringExtra("theme") ?: ""
        val count = seedIntent.getIntExtra("snapshots_count", 0)
        val out = ArrayList<Row>(count)
        for (i in 0 until count) {
            val name = seedIntent.getStringExtra("name_$i") ?: continue
            val type = seedIntent.getStringExtra("type_$i") ?: "custom"
            val value = seedIntent.getStringExtra("value_$i") ?: "—"
            val status = seedIntent.getStringExtra("status_$i") ?: "ok"
            val id = seedIntent.getStringExtra("id_$i") ?: continue
            // type 当前没用到，但保留以便后续按 Coding Plan vs Balance 差异化渲染
            @Suppress("UNUSED_VARIABLE")
            val unused = type
            out += Row(id, name, value, status)
        }
        rows = out
    }

    override fun onDestroy() { rows = emptyList() }
    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position !in rows.indices) {
            return loadingView()
        }
        val row = rows[position]
        val rv = RemoteViews(context.packageName, R.layout.widget_large_list_item)
        WidgetTheme.applyRow(rv, themeId)
        rv.setTextViewText(R.id.widget_row_name, row.name)
        rv.setTextViewText(R.id.widget_row_value, row.value)
        rv.setImageViewResource(
            R.id.widget_row_status,
            if (row.status == "error") R.drawable.widget_status_dot_error else R.drawable.widget_status_dot,
        )
        // 设置 fillInIntent 让 PendingIntent 模板带 providerId
        val fillIn = Intent().putExtra(WidgetLayoutBuilder.EXTRA_PROVIDER_ID, row.providerId)
        rv.setOnClickFillInIntent(R.id.widget_list_row, fillIn)
        return rv
    }

    override fun getLoadingView(): RemoteViews = loadingView()

    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun loadingView(): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_loading)
        return rv
    }
}

/**
 * 把 PendingIntent 模板构造放在工厂外辅助函数，让 setPendingIntentTemplate 调用方
 * 不用关心 intent flag 细节。
 */
fun widgetListClickTemplateIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    // 使用 stable requestCode 让所有 list 行的模板指向同一个 PendingIntent，
    // 真正的 providerId 由 RemoteViews.setOnClickFillInIntent 注入。
    return PendingIntent.getActivity(context, AppWidgetManager.EXTRA_APPWIDGET_IDS.hashCode(), intent, flags)
}