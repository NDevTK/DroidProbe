package com.droidprobe.app.data.model

data class DexAnalysis(
    val packageName: String,
    val contentProviderUris: List<ContentProviderInfo>,
    val intentExtras: List<IntentInfo>,
    val fileProviderPaths: List<FileProviderInfo>,
    val rawContentUriStrings: List<String>
)
