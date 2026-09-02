package com.aimonitor.app.widget

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * v0.1.47+ 桌面小组件状态自检通道（widget-patches 同步副本）。
 * 见 gen/android 同名文件的详细说明。
 */
object WidgetStatusReporter {

    private const val TAG = "WidgetStatusReporter"
    private const val FILE_NAME = "widget_status.json"
    private const val DIR_NAME = "ai-model-monitor"

    private val lock = ReentrantLock()

    enum class Event(val tag: String) {
        ON_UPDATE("on_update"),
        SERVICE_START("service_start"),
        TICK("tick"),
        RENDER_ERROR("render_error"),
        SERVICE_ERROR("service_error"),
    }

    fun report(
        context: Context,
        event: Event,
        widgetCount: Int? = null,
        snapshotCount: Int? = null,
        errorMessage: String? = null,
    ) {
        lock.withLock {
            try {
                val dir = File(context.dataDir, DIR_NAME)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create $dir")
                    return
                }
                val path = File(dir, FILE_NAME)
                val now = System.currentTimeMillis()
                val existing = if (path.exists()) {
                    try { JSONObject(path.readText()) } catch (_: Exception) { JSONObject() }
                } else {
                    JSONObject()
                }
                existing.put("lastEventAt", now)
                existing.put("lastEventTag", event.tag)
                widgetCount?.let { existing.put("lastWidgetCount", it) }
                snapshotCount?.let { existing.put("lastSnapshotCount", it) }
                errorMessage?.let { existing.put("lastError", it) }
                when (event) {
                    Event.ON_UPDATE -> existing.put("lastOnUpdateAt", now)
                    Event.SERVICE_START -> {
                        existing.put("lastServiceStartAt", now)
                        existing.put("serviceStartCount",
                            (existing.optInt("serviceStartCount", 0)) + 1)
                    }
                    Event.TICK -> existing.put("lastTickAt", now)
                    Event.RENDER_ERROR, Event.SERVICE_ERROR ->
                        existing.put("lastErrorAt", now)
                }
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(existing.toString())
                if (!tmp.renameTo(path)) {
                    path.writeText(existing.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "report(${event.tag}) failed", e)
            }
        }
    }

    fun read(context: Context): JSONObject? {
        return try {
            val path = File(File(context.dataDir, DIR_NAME), FILE_NAME)
            if (!path.exists()) null
            else JSONObject(path.readText())
        } catch (e: Exception) {
            Log.w(TAG, "read failed", e)
            null
        }
    }
}
