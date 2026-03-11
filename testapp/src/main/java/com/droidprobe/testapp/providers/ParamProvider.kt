package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ParamProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.testapp.params"
        private const val DATA = 1

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "data", DATA)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("_id", "value"))

        when (uriMatcher.match(uri)) {
            DATA -> {
                // Boolean query parameters
                val verbose = uri.getBooleanQueryParameter("verbose", false)
                val includeDeleted = uri.getBooleanQueryParameter("include_deleted", true)

                // Plural getQueryParameters (returns List<String>)
                val tags = uri.getQueryParameters("tags")

                if (verbose) {
                    cursor.addRow(arrayOf(1L, "verbose_data"))
                }
                if (includeDeleted) {
                    cursor.addRow(arrayOf(2L, "deleted_data"))
                }
                for (tag in tags) {
                    cursor.addRow(arrayOf(3L, "tag: $tag"))
                }
            }
        }

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "application/octet-stream"
}
