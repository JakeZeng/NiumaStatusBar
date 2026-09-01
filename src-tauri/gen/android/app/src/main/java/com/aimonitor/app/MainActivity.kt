package com.aimonitor.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import com.aimonitor.app.widget.WidgetLayoutBuilder

class MainActivity : TauriActivity() {
  private var webViewRef: WebView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    handleWidgetIntent(intent)
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
   */
  private fun handleWidgetIntent(intent: Intent?) {
    val providerId = intent?.getStringExtra(WidgetLayoutBuilder.EXTRA_PROVIDER_ID) ?: return
    pendingProviderId = providerId
    dispatchPendingProviderId()
  }

  private fun dispatchPendingProviderId() {
    val pid = pendingProviderId ?: return
    val wv = webViewRef ?: return
    val js = "window.__NIUMA_SELECT_PROVIDER__ && window.__NIUMA_SELECT_PROVIDER__(${pidJs(pid)});"
    wv.post { wv.evaluateJavascript(js, null) }
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
    @Volatile private var pendingProviderId: String? = null
  }
}
