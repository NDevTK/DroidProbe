package com.droidprobe.testapp.providers

import android.net.Uri
import android.util.Log

/**
 * Deep link URI handler that validates scheme/host/path components.
 * Tests UriPatternExtractor's deep link URI detection via getScheme()+getHost()+getPath().
 */
class DeepLinkHandler {

    fun handleDeepLink(uri: Uri): Boolean {
        // Strategy 7: Deep link URI detection via scheme/host/path validation
        if (uri.scheme == "myapp" && uri.host == "deeplink") {
            if (uri.path == "/home") {
                val ref = uri.getQueryParameter("ref")
                Log.d("DeepLink", "Home deep link, ref=$ref")
                return true
            }
        }

        if (uri.scheme == "customscheme" && uri.host == "action") {
            if (uri.path == "/open") {
                Log.d("DeepLink", "Custom scheme action/open")
                return true
            }
        }

        return false
    }
}
