package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Tests ContentProvider query metadata extraction:
 * - query(): MatrixCursor projection columns, selection templates, sort orders
 * - openFile(): file access mode detection
 *
 * ContentProviderCrudExtractor should detect:
 * - QUERY: projection ["_id", "display_name", "email", "account_type"] for ACCOUNTS
 * - QUERY: projection ["_id", "subject", "sender", "timestamp"] for MESSAGES
 * - QUERY: selection "account_type=?" template
 * - QUERY: sort orders "display_name ASC", "timestamp DESC"
 * - OPEN_FILE: modes "r", "rw"
 *
 * Authority: com.droidprobe.testapp.querymeta
 */
class QueryMetadataProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.droidprobe.testapp.querymeta"
        private const val ACCOUNTS = 1
        private const val ACCOUNT_ID = 2
        private const val MESSAGES = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "accounts", ACCOUNTS)
            addURI(AUTHORITY, "accounts/#", ACCOUNT_ID)
            addURI(AUTHORITY, "messages", MESSAGES)
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
        when (uriMatcher.match(uri)) {
            ACCOUNTS -> {
                val cursor = MatrixCursor(arrayOf("_id", "display_name", "email", "account_type"))
                val sel = selection ?: "account_type=?"
                val sort = sortOrder ?: "display_name ASC"
                // Simulated query using sel and sort
                cursor.addRow(arrayOf<Any>("1", "Test User", "test@example.com", "google"))
                return cursor
            }
            MESSAGES -> {
                val cursor = MatrixCursor(arrayOf("_id", "subject", "sender", "timestamp"))
                val sort = sortOrder ?: "timestamp DESC"
                cursor.addRow(arrayOf<Any>("1", "Hello", "sender@example.com", "0"))
                return cursor
            }
        }
        return MatrixCursor(arrayOf("_id"))
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode == "r" || mode == "rw") {
            // Supported modes
        }
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}
