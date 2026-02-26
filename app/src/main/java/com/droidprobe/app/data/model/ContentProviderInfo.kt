package com.droidprobe.app.data.model

data class ContentProviderInfo(
    val authority: String?,
    val uriPattern: String,
    val matchCode: Int?,
    val associatedColumns: List<String>,
    val queryParameters: List<String> = emptyList(),
    val queryParameterValues: Map<String, List<String>> = emptyMap(),
    /** Known default values for query parameters. Presence means the param is optional. */
    val queryParameterDefaults: Map<String, String> = emptyMap(),
    val sourceClass: String,
    val sourceMethod: String?
)
