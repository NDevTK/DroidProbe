package com.droidprobe.testapp.network

import android.util.Log

/**
 * Tests StringBuilder URL concatenation detection.
 */
class StringBuilderUrl {

    fun buildTrackingUrl(): String {
        val url = StringBuilder("https://events.example.com")
            .append("/api")
            .append("/v3")
            .append("/track")
            .toString()
        Log.d("StringBuilderUrl", "Built URL: $url")
        return url
    }
}
