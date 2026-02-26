package com.droidprobe.app.data.model

data class IntentInfo(
    val extraKey: String,
    val extraType: String?,
    val associatedAction: String?,
    val associatedComponent: String?,
    val sourceClass: String,
    val sourceMethod: String?
)
