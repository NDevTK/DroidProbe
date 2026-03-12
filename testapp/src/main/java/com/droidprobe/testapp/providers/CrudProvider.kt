package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Tests ContentProvider CRUD operations beyond query().
 * ContentProviderCrudExtractor should detect:
 * - insert(): ContentValues keys "title", "body", "author", "created_at"
 * - update(): ContentValues keys "title", "body", "updated_at", selection="status=?"
 * - delete(): selection="expired=?" on ITEMS, no selection on ITEM_ID
 * - getType(): MIME types for collections vs single items
 *
 * Authority: com.droidprobe.testapp.crud
 */
class CrudProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.testapp.crud"
        private const val ITEMS = 1
        private const val ITEM_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "articles", ITEMS)
            addURI(AUTHORITY, "articles/#", ITEM_ID)
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
        return MatrixCursor(arrayOf("_id", "title", "body"))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        when (uriMatcher.match(uri)) {
            ITEMS -> {
                val title = values?.getAsString("title")
                val body = values?.getAsString("body")
                val author = values?.getAsString("author")
                val createdAt = values?.getAsLong("created_at")
                // Simulate insert
                return Uri.withAppendedPath(uri, "1")
            }
        }
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        when (uriMatcher.match(uri)) {
            ITEMS -> {
                val title = values?.getAsString("title")
                val body = values?.getAsString("body")
                val updatedAt = values?.getAsLong("updated_at")
                // Uses selection="status=?"
                return 1
            }
            ITEM_ID -> {
                val title = values?.getAsString("title")
                val body = values?.getAsString("body")
                return 1
            }
        }
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        when (uriMatcher.match(uri)) {
            ITEMS -> {
                // Bulk delete with selection="expired=?"
                return 1
            }
            ITEM_ID -> {
                // Single item delete, no selection
                return 1
            }
        }
        return 0
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            ITEMS -> "vnd.android.cursor.dir/vnd.droidprobe.article"
            ITEM_ID -> "vnd.android.cursor.item/vnd.droidprobe.article"
            else -> null
        }
    }
}
