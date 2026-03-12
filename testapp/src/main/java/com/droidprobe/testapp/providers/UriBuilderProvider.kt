package com.droidprobe.testapp.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Tests Uri.Builder pattern detection.
 * UriPatternExtractor should detect content URIs constructed via
 * Uri.Builder().scheme("content").authority("...").appendPath("...").build()
 * Authority: com.droidprobe.testapp.builder
 */
class UriBuilderProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.droidprobe.testapp.builder"

        fun buildRecordsUri(): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("records")
                .build()
        }

        fun buildRecordUri(id: Long): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("records")
                .appendPath(id.toString())
                .build()
        }

        fun buildFilteredRecordsUri(): Uri {
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("records")
                .appendQueryParameter("status", "active")
                .appendQueryParameter("format", "json")
                .build()
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val built = buildRecordsUri()
        Log.d("UriBuilder", "Querying: $built")
        return null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
