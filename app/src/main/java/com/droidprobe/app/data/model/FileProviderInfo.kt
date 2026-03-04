package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FileProviderInfo(
    val authority: String,
    val pathType: String,
    val path: String,
    val name: String,
    val filePath: String? = null
)
