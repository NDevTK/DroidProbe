package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SensitiveString(
    val value: String,
    val category: String,
    val sourceClass: String = "",
    val associatedUrls: List<String> = emptyList()
)

@Serializable
data class ApiEndpoint(
    val fullUrl: String,
    val baseUrl: String,
    val path: String,
    val httpMethod: String? = null,
    val sourceClass: String,
    val sourceType: String
)

@Serializable
data class DexAnalysis(
    val packageName: String,
    val contentProviderUris: List<ContentProviderInfo>,
    val intentExtras: List<IntentInfo>,
    val fileProviderPaths: List<FileProviderInfo>,
    val rawContentUriStrings: List<String>,
    val deepLinkUriStrings: List<String> = emptyList(),
    val contentProviderCalls: List<ContentProviderCallInfo> = emptyList(),
    val allUrlStrings: List<String> = emptyList(),
    val sensitiveStrings: List<SensitiveString> = emptyList(),
    val apiEndpoints: List<ApiEndpoint> = emptyList()
)
