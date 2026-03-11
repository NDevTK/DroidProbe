package com.droidprobe.testapp.network

import android.net.Uri
import android.util.Log

/**
 * Tests wrapper method detection for URI query parameter extraction.
 * The getParam() method wraps Uri.getQueryParameter() with a literal key passed from call site.
 */
class WrapperMethod {

    private fun getParam(uri: Uri, key: String): String? {
        return uri.getQueryParameter(key)
    }

    fun processUri(uri: Uri) {
        val name = getParam(uri, "name")
        val value = getParam(uri, "value")
        val token = getParam(uri, "token")
        Log.d("Wrapper", "name=$name value=$value token=$token")
    }
}
