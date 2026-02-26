package com.droidprobe.app.data.model

data class ContentProviderInfo(
    val authority: String?,
    val uriPattern: String,
    val matchCode: Int?,
    val associatedColumns: List<String>,
    val queryParameters: List<String> = emptyList(),
    val queryParameterValues: Map<String, List<String>> = emptyMap(),
    val sourceClass: String,
    val sourceMethod: String?
)
