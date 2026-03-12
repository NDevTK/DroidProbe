package com.droidprobe.testapp.calls

import android.content.Context
import android.net.Uri

/**
 * Tests caller-side ContentResolver.query() detection.
 * ContentProviderCallExtractor should detect:
 * - projection: ["_id", "display_name", "email"]
 * - selection: "account_type=? AND active=?"
 * - sortOrder: "display_name ASC"
 * - uri: "content://com.droidprobe.testapp.querymeta/accounts"
 */
class QueryCaller(private val context: Context) {

    fun queryAccounts() {
        val projection = arrayOf("_id", "display_name", "email")
        val selection = "account_type=? AND active=?"
        val sortOrder = "display_name ASC"
        context.contentResolver.query(
            Uri.parse("content://com.droidprobe.testapp.querymeta/accounts"),
            projection,
            selection,
            null,
            sortOrder
        )
    }
}
