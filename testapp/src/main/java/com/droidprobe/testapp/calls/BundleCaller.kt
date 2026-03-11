package com.droidprobe.testapp.calls

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

/**
 * Tests ContentResolver.call() with a populated Bundle extras parameter.
 * ContentProviderCallExtractor should detect:
 * - authority: com.droidprobe.testapp.basic
 * - methodName: "export"
 * - arg: "all"
 * Note: Bundle keys ("format", "version") are NOT currently extracted.
 */
class BundleCaller {

    fun callWithBundle(resolver: ContentResolver) {
        val extras = Bundle()
        extras.putString("format", "json")
        extras.putInt("version", 2)
        resolver.call(
            Uri.parse("content://com.droidprobe.testapp.basic"),
            "export",
            "all",
            extras
        )
    }
}
