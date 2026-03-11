package com.droidprobe.testapp.calls

import android.content.Context
import android.net.Uri

/**
 * Tests ContentProviderCallExtractor detection of ContentResolver.call() invocations.
 */
class ProviderCaller(private val context: Context) {

    fun callBackup() {
        // ContentResolver.call(Uri, method, arg, extras) — URI-based authority
        context.contentResolver.call(
            Uri.parse("content://com.droidprobe.testapp.basic"),
            "backup",
            null,
            null
        )
    }

    fun callClearCache() {
        // ContentResolver.call(Uri, method, arg, extras) — with arg
        context.contentResolver.call(
            Uri.parse("content://com.droidprobe.testapp.basic"),
            "clear_cache",
            "all",
            null
        )
    }

    fun callSync() {
        // ContentResolver.call(String authority, method, arg, extras) — API 29+ string authority
        context.contentResolver.call(
            "com.droidprobe.testapp.dispatch",
            "sync",
            null,
            null
        )
    }
}
