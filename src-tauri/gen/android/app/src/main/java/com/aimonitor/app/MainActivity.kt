package com.aimonitor.app

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
  }

  override fun onWebViewCreate(webView: WebView) {
    // MuMu 模拟器 GPU 兼容性差，强制软件渲染避免 tile memory limits exceeded 导致白屏
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
  }
}
