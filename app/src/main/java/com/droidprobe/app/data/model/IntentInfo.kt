package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class IntentInfo(
    val extraKey: String,
    val extraType: String?,
    val possibleValues: List<String> = emptyList(),
    val defaultValue: String? = null,
    val associatedAction: String?,
    val associatedComponent: String?,
    val sourceClass: String,
    val sourceMethod: String?
)
