/* THIS FILE IS VENDORED FROM wry v0.55.1.
   Source: https://github.com/tauri-apps/wry/blob/wry-v0.55.1/src/android/kotlin/RustWebViewClient.kt
   See RustWebChromeClient.kt for context. */
package com.aimonitor.app
import android.net.Uri
import android.webkit.*
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.webkit.WebViewAssetLoader
class RustWebViewClient(webView: RustWebView, context: Context): WebViewClient() {
    private val interceptedState = mutableMapOf<String, Boolean>()
    var currentUrl: String = "about:blank"
    private var lastInterceptedUrl: Uri? = null
    private var pendingUrlRedirect: String? = null
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(Rust.assetLoaderDomain(webView.id))
        .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        pendingUrlRedirect?.let {
            Handler(Looper.getMainLooper()).post {
              view.loadUrl(it)
            }
            pendingUrlRedirect = null
            return null
        }
        lastInterceptedUrl = request.url
        return if (Rust.withAssetLoader((view as RustWebView).id)) {
            assetLoader.shouldInterceptRequest(request.url)
        } else {
            val response = Rust.handleRequest(view.id, request, view.isDocumentStartScriptEnabled)
            if (response != null) {
                if (response.responseHeaders != null) {
                    response.responseHeaders["Cache-Control"] = "no-store"
                } else {
                    response.responseHeaders = mapOf("Cache-Control" to "no-store")
                }
            }
            interceptedState[request.url.toString()] = response != null
            return response
        }
    }
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        return Rust.shouldOverride((view as RustWebView).id, request.url.toString())
    }
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        currentUrl = url
        if (interceptedState[url] == false) {
            val webView = view as RustWebView
            for (script in webView.initScripts) {
                view.evaluateJavascript(script, null)
            }
        }
        return Rust.onPageLoading((view as RustWebView).id, url)
    }
    override fun onPageFinished(view: WebView, url: String) {
        Rust.onPageLoaded((view as RustWebView).id, url)
    }
    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (error.errorCode == ERROR_CONNECT && request.isForMainFrame && request.url != lastInterceptedUrl) {
            view.stopLoading()
            view.loadUrl(request.url.toString())
            pendingUrlRedirect = request.url.toString()
        } else {
            super.onReceivedError(view, request, error)
        }
    }
}
