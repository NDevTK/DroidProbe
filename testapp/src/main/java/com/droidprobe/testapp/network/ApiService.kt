package com.droidprobe.testapp.network

import retrofit2.http.*

/**
 * Retrofit interface with full annotations for testing UrlExtractor.
 * Tests @GET/@POST/@PUT/@DELETE with @Path/@Query/@Header/@Body.
 */
interface ApiService {

    @GET("users/{id}")
    suspend fun getUser(
        @Path("id") id: String,
        @Query("fields") fields: String,
        @Header("Authorization") auth: String
    ): Any

    @POST("users")
    suspend fun createUser(@Body user: Any): Any

    @PUT("users/{id}")
    suspend fun updateUser(
        @Path("id") id: String,
        @Body user: Any
    ): Any

    @DELETE("users/{id}")
    suspend fun deleteUser(@Path("id") id: String): Any

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Any

    @PATCH("users/{id}/settings")
    suspend fun patchSettings(
        @Path("id") id: String,
        @Body settings: Any
    ): Any
}
