package com.droidprobe.testapp.network

import retrofit2.Retrofit

/**
 * Tests Retrofit.Builder.baseUrl() detection for base URL discovery.
 */
class RetrofitClient {

    // Literal URL that matches a Retrofit endpoint — tests dedup (Retrofit should win)
    val usersUrl = "https://api.example.com/v1/users"

    fun createApiService(): ApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.example.com/v1/")
            .build()
        return retrofit.create(ApiService::class.java)
    }

    fun createCdnService(): CdnService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://cdn.example.com/api/")
            .build()
        return retrofit.create(CdnService::class.java)
    }
}
