package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class DispatchProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.testapp.dispatch"
        private const val MESSAGES = 10
        private const val THREAD_ID = 20

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "messages", MESSAGES)
            addURI(AUTHORITY, "threads/#", THREAD_ID)
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
        val cursor = MatrixCursor(arrayOf("_id", "content"))

        when (uriMatcher.match(uri)) {
            MESSAGES -> {
                // These params should be scoped to MESSAGES match code only
                val sender = uri.getQueryParameter("sender")
                val limit = uri.getQueryParameter("limit")
                if (sender != null) {
                    cursor.addRow(arrayOf(1L, "msg from $sender"))
                }
                if (limit != null) {
                    // use limit
                    limit.toIntOrNull()
                }
            }
            THREAD_ID -> {
                // These params should be scoped to THREAD_ID match code only
                val threadId = uri.getQueryParameter("thread_id")
                val page = uri.getQueryParameter("page")
                if (threadId != null) {
                    cursor.addRow(arrayOf(1L, "thread $threadId"))
                }
                if (page != null) {
                    page.toIntOrNull()
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
