package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class BasicProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.testapp.basic"
        private const val ITEMS = 1
        private const val ITEM_ID = 2
        private const val CATEGORY = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "items", ITEMS)
            addURI(AUTHORITY, "items/#", ITEM_ID)
            addURI(AUTHORITY, "categories/*", CATEGORY)
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
        val cursor = MatrixCursor(arrayOf("_id", "name", "value"))

        when (uriMatcher.match(uri)) {
            ITEMS -> {
                val filter = uri.getQueryParameter("filter")
                val sortBy = uri.getQueryParameter("sort_by")

                // Forward value scanning: string equals comparisons
                if (filter != null) {
                    if (filter.equals("active")) {
                        cursor.addRow(arrayOf(1L, "active_item", "a"))
                    } else if (filter.equals("archived")) {
                        cursor.addRow(arrayOf(2L, "archived_item", "b"))
                    }
                }
                if (sortBy != null) {
                    when {
                        sortBy.equals("name") -> {}
                        sortBy.equals("date") -> {}
                        sortBy.equals("id") -> {}
                    }
                }
            }
            ITEM_ID -> {
                val id = ContentUris.parseId(uri)
                cursor.addRow(arrayOf(id, "item_$id", "value"))

                // Also test ContentUris.withAppendedId
                val baseUri = Uri.parse("content://$AUTHORITY/items")
                val itemUri = ContentUris.withAppendedId(baseUri, id)
                itemUri.toString() // force use
            }
            CATEGORY -> {
                val category = uri.lastPathSegment
                cursor.addRow(arrayOf(1L, category, "cat_value"))
            }
        }

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "application/octet-stream"
}
