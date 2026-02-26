package com.droidprobe.app.data.model

data class AnalysisResult(
    val appInfo: AppInfo,
    val manifestAnalysis: ManifestAnalysis,
    val dexAnalysis: DexAnalysis?
)
