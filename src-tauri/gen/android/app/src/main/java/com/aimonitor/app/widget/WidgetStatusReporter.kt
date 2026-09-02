package com.aimonitor.app.widget

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * v0.1.47+ 桌面小组件状态自检通道。
 *
 * 问题背景：v0.1.46 出现 "绿点 + 全空" 症状，根因怀疑是国产 ROM 后台冻结
 * UsageWidgetCarouselService 导致 widget 不刷新，但用户没 logcat、没法验证。
 *
 * 解决：widget 进程在以下关键节点写一个 JSON 文件到 app data 目录：
 *   1. onUpdate 触发时（无论成功失败都写）
 *   2. carousel service 启动时
 *   3. carousel service 每次 tick 时（覆盖 lastTickAt）
 *   4. widget 渲染失败时（带 lastError 字段）
 *
 * 主进程（Rust 端或前端 Tauri command）启动时读这个 JSON，能直接展示
 * 给用户「service 是否在跑、最后一次 tick 是多久前」之类的诊断信息，
 * 下次 "绿点 + 全空" 不用再让用户去 adb 抓 log。
 *
 * 路径：`{app_data_dir}/ai-model-monitor/widget_status.json`
 *   与 widget_snapshot.json 同目录，widget 进程和主进程都能读。
 *   写入用临时文件 + rename 保证原子性。
 */
object WidgetStatusReporter {

    private const val TAG = "WidgetStatusReporter"
    private const val FILE_NAME = "widget_status.json"
    private const val DIR_NAME = "ai-model-monitor"

    private val lock = ReentrantLock()

    enum class Event(val tag: String) {
        /** 首次 onUpdate 收到（widget 添加或系统 30min 强制更新） */
        ON_UPDATE("on_update"),
        /** UsageWidgetCarouselService.onCreate */
        SERVICE_START("service_start"),
        /** UsageWidgetCarouselService 每 5s tick */
        TICK("tick"),
        /** UsageWidgetProvider 同步渲染抛异常 */
        RENDER_ERROR("render_error"),
        /** UsageWidgetCarouselService 整体抛异常（读盘失败等） */
        SERVICE_ERROR("service_error"),
    }

    /**
     * 写一条状态记录。
     * @param event 事件类型
     * @param widgetCount widget 实例数（仅 service/tick 写）
     * @param snapshotCount 读盘返回的 provider 数量（仅 tick 写）
     * @param errorMessage 失败信息（仅 render_error / service_error 写）
     */
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

                // 读取现有记录，避免覆盖（保留 lastTickAt 等历史信息）
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

                // 原子写：临时文件 + rename
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(existing.toString())
                if (!tmp.renameTo(path)) {
                    // rename 失败（罕见），直接覆盖
                    path.writeText(existing.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "report(${event.tag}) failed", e)
            }
        }
    }

    /**
     * 主进程读 widget 状态。
     * 用于 app 内诊断页 / Tauri command 展示给用户。
     */
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
