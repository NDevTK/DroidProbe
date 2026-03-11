package com.droidprobe.testapp.network

import retrofit2.Retrofit

/**
 * Tests Retrofit.Builder.baseUrl() detection for base URL discovery.
 */
class RetrofitClient {

    fun createService(): ApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.example.com/v1/")
            .build()
        return retrofit.create(ApiService::class.java)
    }
}
