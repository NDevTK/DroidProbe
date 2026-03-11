package com.droidprobe.testapp.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Tests HttpUrl URL detection as an OkHttp endpoint.
 */
class HttpUrlCaller {

    private val client = OkHttpClient()

    fun fetchConfig() {
        val url = "https://static.example.com/v2/config.json".toHttpUrl()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute()
    }
}
