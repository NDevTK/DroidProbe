package com.droidprobe.app.data.model

data class ContentProviderCallInfo(
    val authority: String?,
    val methodName: String,
    val sourceClass: String,
    val sourceMethod: String?
)
