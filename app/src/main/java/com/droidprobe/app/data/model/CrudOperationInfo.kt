package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

/**
 * A ContentProvider CRUD operation discovered via bytecode analysis.
 * Captures the operation type (INSERT/UPDATE/DELETE/GET_TYPE),
 * ContentValues keys used, and MIME types returned by getType().
 */
@Serializable
data class CrudOperationInfo(
    val operation: String,         // INSERT, UPDATE, DELETE, GET_TYPE
    val contentValuesKeys: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val sourceClass: String,
    val sourceMethod: String?
)
