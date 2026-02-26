package com.droidprobe.app.interaction

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentProviderInteractor(private val contentResolver: ContentResolver) {

    data class QueryParams(
        val uri: Uri,
        val projection: Array<String>?,
        val selection: String?,
        val selectionArgs: Array<String>?,
        val sortOrder: String?
    )

    data class QueryResult(
        val columns: List<String>,
        val rows: List<List<String?>>,
        val rowCount: Int,
        val error: String?
    )

    suspend fun query(params: QueryParams): QueryResult = withContext(Dispatchers.IO) {
        try {
            val cursor = contentResolver.query(
                params.uri,
                params.projection,
                params.selection,
                params.selectionArgs,
                params.sortOrder
            )
            cursor?.use { c ->
                val columns = c.columnNames.toList()
                val rows = mutableListOf<List<String?>>()
                while (c.moveToNext() && rows.size < 500) {
                    rows.add(columns.indices.map { i ->
                        try {
                            c.getString(i)
                        } catch (_: Exception) {
                            "<blob>"
                        }
                    })
                }
                QueryResult(columns, rows, c.count, null)
            } ?: QueryResult(emptyList(), emptyList(), 0, "Cursor was null")
        } catch (e: SecurityException) {
            QueryResult(emptyList(), emptyList(), 0, "SecurityException: ${e.message}")
        } catch (e: Exception) {
            QueryResult(emptyList(), emptyList(), 0, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun insert(uri: Uri, values: ContentValues): String = withContext(Dispatchers.IO) {
        try {
            val result = contentResolver.insert(uri, values)
            result?.toString() ?: "Insert returned null"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun update(
        uri: Uri,
        values: ContentValues,
        where: String?,
        args: Array<String>?
    ): String = withContext(Dispatchers.IO) {
        try {
            val count = contentResolver.update(uri, values, where, args)
            "$count row(s) updated"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun delete(uri: Uri, where: String?, args: Array<String>?): String =
        withContext(Dispatchers.IO) {
            try {
                val count = contentResolver.delete(uri, where, args)
                "$count row(s) deleted"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
}
