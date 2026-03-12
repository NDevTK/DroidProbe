package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ContentProviderCallInfo(
    val authority: String?,
    val methodName: String,
    val arg: String? = null,
    val sourceClass: String,
    val sourceMethod: String?
)

@Serializable
data class ContentResolverQueryInfo(
    val uri: String?,
    val projection: List<String>,
    val selection: String?,
    val sortOrder: String?,
    val sourceClass: String,
    val sourceMethod: String?
)
