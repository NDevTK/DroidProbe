package com.droidprobe.app.data.model

enum class KeyStatus { UNTESTED, TESTING, VALID, INVALID }

data class DiscoveryDocument(
    val name: String,
    val version: String,
    val title: String,
    val description: String,
    val rootUrl: String,
    val servicePath: String,
    val resources: Map<String, DiscoveryResource>
)

data class DiscoveryResource(
    val name: String,
    val methods: Map<String, DiscoveryMethod>,
    val resources: Map<String, DiscoveryResource>
)

data class DiscoveryMethod(
    val id: String,
    val httpMethod: String,
    val path: String,
    val description: String,
    val parameters: Map<String, DiscoveryParameter>,
    val parameterOrder: List<String>,
    val scopes: List<String>,
    val source: String = "",
    val hasBody: Boolean = false
)

data class DiscoveryParameter(
    val name: String,
    val type: String,
    val location: String,
    val required: Boolean,
    val description: String,
    val default: String?,
    val enumValues: List<String>
)

data class ExecutionResult(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String>
)
