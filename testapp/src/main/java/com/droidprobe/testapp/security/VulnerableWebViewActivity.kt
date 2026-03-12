package com.droidprobe.testapp.security

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/**
 * Test pattern: WebView with JavaScript enabled in an exported activity.
 * SecurityPatternDetector should flag WEBVIEW_JS_ENABLED.
 */
class VulnerableWebViewActivity : Activity() {

    @JvmField val TAG = "VulnerableWebView"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        val url = intent.getStringExtra("url") ?: "about:blank"
        webView.loadUrl(url)
    }
}
