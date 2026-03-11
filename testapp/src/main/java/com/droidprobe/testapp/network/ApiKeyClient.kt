package com.droidprobe.testapp.network

import com.droidprobe.testapp.strings.FakeSecrets
import okhttp3.Request

/**
 * Class that uses a FakeSecrets API key AND makes an HTTP request.
 * Tests that DexAnalyzer correctly associates sensitive strings
 * with API endpoints found in the same source class.
 */
class ApiKeyClient {

    fun fetchWithApiKey(): Request {
        val key = FakeSecrets.GOOGLE_KEY
        return Request.Builder()
            .url("https://maps.example.com/api/geocode")
            .addHeader("X-Api-Key", key)
            .build()
    }
}
