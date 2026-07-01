/* THIS FILE IS VENDORED FROM wry v0.55.1.
   Source: https://github.com/tauri-apps/wry/blob/wry-v0.55.1/src/android/kotlin/Ipc.kt
   See RustWebChromeClient.kt for context. */
@file:Suppress("unused")
package com.aimonitor.app
import android.webkit.*
class Ipc(val webView: RustWebView, val webViewClient: RustWebViewClient) {
    @JavascriptInterface
    fun postMessage(message: String?) {
        message?.let {m ->
            Rust.ipc(webView.id, webViewClient.currentUrl, m)
        }
    }
}
