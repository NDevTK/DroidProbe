package com.droidprobe.app.data.model

data class FileProviderInfo(
    val authority: String,
    val pathType: String,
    val path: String,
    val name: String,
    val filePath: String? = null
)
