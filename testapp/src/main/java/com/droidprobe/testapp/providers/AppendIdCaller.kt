package com.droidprobe.testapp.providers

import android.content.ContentUris
import android.net.Uri

/**
 * Tests ContentUris.withAppendedId() detection.
 * The extractor should detect the base URI and append /#.
 */
class AppendIdCaller {

    fun buildItemUri(id: Long): Uri {
        val baseUri = Uri.parse("content://com.droidprobe.testapp.basic/items")
        return ContentUris.withAppendedId(baseUri, id)
    }
}
