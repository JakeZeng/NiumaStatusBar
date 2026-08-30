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
    // MuMu 模拟器 GPU 兼容性差，强制软件渲染避免 tile memory limits exceeded 导致白屏
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    // 父类 WryActivity 没有暴露 webView 访问器；此处缓存供 widget 跳转注入 JS 用
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
