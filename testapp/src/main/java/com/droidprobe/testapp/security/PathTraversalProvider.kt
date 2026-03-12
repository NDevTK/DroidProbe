package com.droidprobe.testapp.security

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * Test pattern: ContentProvider using Uri.getLastPathSegment() which is
 * vulnerable to encoded path separators (%2F..%2F).
 * SecurityPatternDetector should flag PATH_TRAVERSAL.
 */
class PathTraversalProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val filename = uri.lastPathSegment ?: "default"
        val file = File(context!!.filesDir, filename)
        val cursor = MatrixCursor(arrayOf("name", "size"))
        if (file.exists()) {
            cursor.addRow(arrayOf(file.name, file.length()))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?): Int = 0
}
