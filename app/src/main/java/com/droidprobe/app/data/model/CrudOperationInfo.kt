package com.droidprobe.app.data.model

import kotlinx.serialization.Serializable

/**
 * A ContentProvider CRUD operation discovered via bytecode analysis.
 * Captures the operation type (INSERT/UPDATE/DELETE/GET_TYPE/QUERY/OPEN_FILE),
 * ContentValues keys used, MIME types returned by getType(),
 * projection columns from MatrixCursor, selection templates, sort orders,
 * and openFile modes.
 */
@Serializable
data class CrudOperationInfo(
    val operation: String,         // INSERT, UPDATE, DELETE, GET_TYPE, QUERY, OPEN_FILE
    val contentValuesKeys: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val projectionColumns: List<String> = emptyList(),
    val selectionTemplates: List<String> = emptyList(),
    val sortOrders: List<String> = emptyList(),
    val openFileModes: List<String> = emptyList(),
    val sourceClass: String,
    val sourceMethod: String?
)
