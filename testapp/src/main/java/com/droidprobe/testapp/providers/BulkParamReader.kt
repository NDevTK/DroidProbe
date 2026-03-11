package com.droidprobe.testapp.providers

import android.net.Uri
import android.util.Log

/**
 * Bulk param reader pattern: getQueryParameterNames() → Map → Map.get("key").
 * Tests UriPatternExtractor's Strategy 6 bulk param reader detection.
 */
class BulkParamReader {

    fun readParams(uri: Uri) {
        // Validate URI scheme and host
        if (uri.scheme != "myapp") return
        if (uri.host != "profile") return

        // Bulk read: getQueryParameterNames → collect into map → get specific keys
        val paramNames = uri.queryParameterNames
        val paramMap = mutableMapOf<String, String>()
        for (name in paramNames) {
            val value = uri.getQueryParameter(name)
            if (value != null) {
                paramMap[name] = value
            }
        }

        // Access specific known keys from the map
        val userId = paramMap.get("user_id")
        val action = paramMap.get("action")
        val referrer = paramMap.get("referrer")

        Log.d("BulkParam", "userId=$userId action=$action referrer=$referrer")
    }
}
