package com.droidprobe.testapp.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Tests OkHttp URL detection patterns: Request.Builder.url() and HttpUrl.Companion extensions.
 */
class OkHttpCaller {

    fun fetchExport(): Request {
        return Request.Builder()
            .url("https://api.example.com/data/export")
            .build()
    }

    fun parseImageUrl() {
        val url = "https://cdn.example.com/assets/image.png".toHttpUrlOrNull()
        url.toString()
    }
}
