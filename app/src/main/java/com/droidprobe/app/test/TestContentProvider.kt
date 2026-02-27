package com.droidprobe.app.test

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class TestContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.test.provider"
        private const val ROOT = 0
        private const val ITEMS = 1
        private const val ITEM_ID = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, null, ROOT)
            addURI(AUTHORITY, "items", ITEMS)
            addURI(AUTHORITY, "items/#", ITEM_ID)
        }
    }

    private data class Item(val id: Long, var name: String, var value: String)

    private val items = mutableListOf(
        Item(1, "api_endpoint", "https://api.example.com/v2"),
        Item(2, "debug_mode", "true"),
        Item(3, "max_retries", "5")
    )
    private var nextId = 4L

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val filter = uri.getQueryParameter("filter")
        val cols = arrayOf("_id", "name", "value")
        val cursor = MatrixCursor(cols)

        when (uriMatcher.match(uri)) {
            ROOT, ITEMS -> {
                val filtered = if (filter != null) {
                    items.filter { it.name.contains(filter, ignoreCase = true) }
                } else {
                    items
                }
                filtered.forEach { cursor.addRow(arrayOf<Any>(it.id, it.name, it.value)) }
            }
            ITEM_ID -> {
                val id = uri.lastPathSegment?.toLongOrNull()
                items.find { it.id == id }?.let {
                    cursor.addRow(arrayOf<Any>(it.id, it.name, it.value))
                }
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != ITEMS || values == null) return null
        val item = Item(
            id = nextId++,
            name = values.getAsString("name") ?: "",
            value = values.getAsString("value") ?: ""
        )
        items.add(item)
        return Uri.withAppendedPath(Uri.parse("content://$AUTHORITY/items"), item.id.toString())
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (uriMatcher.match(uri) != ITEM_ID || values == null) return 0
        val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
        val item = items.find { it.id == id } ?: return 0
        values.getAsString("name")?.let { item.name = it }
        values.getAsString("value")?.let { item.value = it }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uriMatcher.match(uri) != ITEM_ID) return 0
        val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
        return if (items.removeAll { it.id == id }) 1 else 0
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        ITEMS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.items"
        ITEM_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.items"
        else -> "application/octet-stream"
    }
}
