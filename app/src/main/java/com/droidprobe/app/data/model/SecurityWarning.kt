package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SecurityWarning(
    val severity: Severity,
    val category: String,
    val title: String,
    val description: String,
    val componentName: String? = null,
    val sourceClass: String? = null,
    val evidence: String? = null
) {
    @Serializable
    enum class Severity { CRITICAL, HIGH, MEDIUM, INFO }
}
