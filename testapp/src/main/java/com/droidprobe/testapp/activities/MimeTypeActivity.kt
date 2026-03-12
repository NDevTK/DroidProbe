package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

// Tests data URI + MIME type intent filter matching.
// Manifest declares image/* and application/pdf MIME types.
// IntentExtraExtractor should detect the "display_mode" extra.
class MimeTypeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val displayMode = intent.getStringExtra("display_mode")
        val uri = intent.data

        if (displayMode != null) {
            when {
                displayMode.equals("fullscreen") -> Log.d("MimeType", "fullscreen mode")
                displayMode.equals("thumbnail") -> Log.d("MimeType", "thumbnail mode")
                displayMode.equals("preview") -> Log.d("MimeType", "preview mode")
            }
        }
        Log.d("MimeType", "uri=$uri display=$displayMode")
    }
}
