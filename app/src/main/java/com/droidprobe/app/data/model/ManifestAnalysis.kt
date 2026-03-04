package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ManifestAnalysis(
    val packageName: String,
    val activities: List<ExportedComponent>,
    val services: List<ExportedComponent>,
    val receivers: List<ExportedComponent>,
    val providers: List<ProviderComponent>,
    val customPermissions: List<String>
)

@Serializable
data class ExportedComponent(
    val name: String,
    val isExported: Boolean,
    val permission: String?,
    val intentFilters: List<IntentFilterInfo>,
    val targetActivity: String? = null
)

@Serializable
data class IntentFilterInfo(
    val actions: List<String>,
    val categories: List<String>,
    val dataSchemes: List<String>,
    val dataAuthorities: List<String>,
    val dataPaths: List<String>,
    val mimeTypes: List<String>
)

@Serializable
data class ProviderComponent(
    val name: String,
    val authority: String?,
    val isExported: Boolean,
    val permission: String?,
    val readPermission: String?,
    val writePermission: String?,
    val grantUriPermissions: Boolean,
    val pathPermissions: List<PathPermissionInfo>
)

@Serializable
data class PathPermissionInfo(
    val path: String,
    val type: String,
    val readPermission: String?,
    val writePermission: String?
)
