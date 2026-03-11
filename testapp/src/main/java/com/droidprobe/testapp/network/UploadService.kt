package com.droidprobe.testapp.network

import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * Tests @Part, @FieldMap, and @QueryMap Retrofit annotations.
 * UrlExtractor should detect:
 * - @Part names ("file", "description") as named parameters
 * - @FieldMap presence (dynamic form fields, no individual keys)
 * - @QueryMap presence (dynamic query params, no individual keys)
 * - @Query("sort") alongside @QueryMap
 */
interface UploadService {

    @Multipart
    @POST("files/upload")
    suspend fun uploadFile(
        @Part("file") filePart: Any,
        @Part("description") description: String
    ): Any

    @FormUrlEncoded
    @POST("forms/submit")
    suspend fun submitForm(
        @FieldMap fields: Map<String, String>
    ): Any

    @GET("items/filter")
    suspend fun filterItems(
        @QueryMap filters: Map<String, String>,
        @Query("sort") sort: String
    ): Any
}
