package com.droidprobe.testapp.network

import retrofit2.http.*

/**
 * Second Retrofit interface with a different base URL for testing
 * multiple base URL resolution.
 */
interface CdnService {

    @GET("images/{id}")
    suspend fun getImage(
        @Path("id") id: String,
        @Query("width") width: Int,
        @Query("height") height: Int
    ): Any

    @GET("videos/{id}/stream")
    suspend fun streamVideo(
        @Path("id") id: String,
        @Header("Range") range: String
    ): Any
}
