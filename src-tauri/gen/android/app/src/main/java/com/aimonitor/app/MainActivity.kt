package com.aimonitor.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import com.aimonitor.app.widget.RingWidgetCarouselService
import com.aimonitor.app.widget.RingWidgetProvider
import com.aimonitor.app.widget.UsageWidgetCarouselService
import com.aimonitor.app.widget.UsageWidgetProvider
import com.aimonitor.app.widget.WidgetLayoutBuilder

class MainActivity : TauriActivity() {
  private var webViewRef: WebView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    handleWidgetIntent(intent)
  }

  override fun onResume() {
    super.onResume()
    // v0.1.52：从「前台窗口」兜底启动 widget 轮播前台服务。
    // 桌面组件只能在 AppWidgetProvider 的 broadcast 回调里尝试启动 service，
    // 而 App 不在前台时 Android 12+ 会拦截后台 startForegroundService
    // （ForegroundServiceStartNotAllowedException），导致轮播 service 起不来、
    // 多 provider 永远停在首屏那一帧。这里 App 已在前台，启动合法，
    // 启动后 service 常驻（带常驻通知），即使 App 退到后台 widget 仍持续轮播。
    startCarouselIfWidgetExists()
  }

  /**
   * 仅当确实存在已添加的 1x2 / 2x2 widget 实例时才启动对应轮播服务，
   * 避免用户没用 widget 时凭空出现常驻通知。
   *
   * 1x2 与 2x2 各有独立 FGS：UsageWidgetCarouselService 走 UsageWidgetProvider，
   * RingWidgetCarouselService 走 RingWidgetProvider。两边数据读取共享
   * WidgetDataReader（同进程同 SQLite 直读），但 service 实例、SharedPreferences
   * 持久化 index、通知渠道各自分离，所以必须分别检查 + 分别启动。
   */
  private fun startCarouselIfWidgetExists() {
    val mgr = AppWidgetManager.getInstance(this)
    // 1x2 横条轮播
    try {
      val ids1x2 = mgr.getAppWidgetIds(ComponentName(this, UsageWidgetProvider::class.java))
      if (ids1x2 != null && ids1x2.isNotEmpty()) {
        UsageWidgetCarouselService.start(this)
      }
    } catch (t: Throwable) {
      Log.w("MainActivity", "start 1x2 carousel failed", t)
    }
    // 2x2 环图轮播（v0.1.52+）
    try {
      val ids2x2 = mgr.getAppWidgetIds(ComponentName(this, RingWidgetProvider::class.java))
      if (ids2x2 != null && ids2x2.isNotEmpty()) {
        RingWidgetCarouselService.start(this)
      }
    } catch (t: Throwable) {
      Log.w("MainActivity", "start 2x2 ring carousel failed", t)
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleWidgetIntent(intent)
  }

  override fun onWebViewCreate(webView: WebView) {
    // 走默认硬件加速（View.LAYER_TYPE_HARDWARE）。原先为 MuMu 模拟器兼容性
    // 强制软件渲染，但软件渲染下 WebView 所有 CSS（动画/transform/box-shadow/
    // backdrop-filter）都走 CPU 合成，实机点击/滚动卡顿严重。MuMu 兼容性问题
    // 由 v0.1.35 起不再纳入考量。
    //
    // Android 系统会按需自动降级为软件渲染（无 GPU 设备）；无需在 Kotlin 侧
    // 主动 setLayerType。
    webViewRef = webView
    // WebView 就绪后补一次派发
    dispatchPendingProviderId()
  }

  /**
   * 把桌面组件带来的 provider_id extra 注入到前端 window.__NIUMA_SELECT_PROVIDER__。
   * WebView 尚未初始化时，存入待派发队列，等 onWebViewCreate 后再补发一次。
   *
   * 同时处理 widget 唤起信号（EXTRA_FROM_WIDGET = true）：
   * 派发 window.__NIUMA_WIDGET_WAKE__() 触发前端立即 fetch 一次。
   */
  private fun handleWidgetIntent(intent: Intent?) {
    val providerId = intent?.getStringExtra(WidgetLayoutBuilder.EXTRA_PROVIDER_ID)
    if (providerId != null) {
      pendingProviderId = providerId
      dispatchPendingProviderId()
    }
    if (intent?.getBooleanExtra(EXTRA_FROM_WIDGET, false) == true) {
      pendingWidgetWake = true
      dispatchPendingWidgetWake()
    }
  }

  private fun dispatchPendingProviderId() {
    val pid = pendingProviderId ?: return
    val wv = webViewRef ?: return
    val js = "window.__NIUMA_SELECT_PROVIDER__ && window.__NIUMA_SELECT_PROVIDER__(${pidJs(pid)});"
    wv.post { wv.evaluateJavascript(js, null) }
  }

  /**
   * 把 widget wake 信号补发给前端。
   * 前端注册 window.__NIUMA_WIDGET_WAKE__() 后，收到调用会立即 fetch 所有
   * enabled provider 的 status（绕过 poller 的 interval 节流），DB 写入新数据
   * 后 widget 5s tick 时能读到。
   */
  private fun dispatchPendingWidgetWake() {
    val wv = webViewRef ?: return
    val js = "window.__NIUMA_WIDGET_WAKE__ && window.__NIUMA_WIDGET_WAKE__();"
    wv.post { wv.evaluateJavascript(js, null) }
    pendingWidgetWake = false
  }

  private fun pidJs(id: String): String {
    val sb = StringBuilder("\"")
    for (c in id) {
      when (c) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        else -> sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }

  companion object {
    /**
     * widget 唤起 App 时携带的 extra key（boolean）。
     * MainActivity 收到后会通过 WebView 调 window.__NIUMA_WIDGET_WAKE__()，
     * 触发前端立即 fetch 一次 enabled providers。
     */
    const val EXTRA_FROM_WIDGET = "niuma_from_widget"

    @Volatile private var pendingProviderId: String? = null
    @Volatile private var pendingWidgetWake: Boolean = false
  }
}
