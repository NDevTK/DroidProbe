package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ContentProviderCallInfo(
    val authority: String?,
    val methodName: String,
    val sourceClass: String,
    val sourceMethod: String?
)
