package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class StaticUriProvider : ContentProvider() {

    companion object {
        val CONTENT_URI: Uri = Uri.parse("content://com.droidprobe.testapp.static/data")
        val USERS_URI: Uri = Uri.parse("content://com.droidprobe.testapp.static/users")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(arrayOf("_id"))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "application/octet-stream"
}
