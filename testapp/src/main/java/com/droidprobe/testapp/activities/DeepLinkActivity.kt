package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Browsable deep link activity with query parameters.
 * Intent filter: BROWSABLE, scheme=testapp, host=open
 * Tests deep link query parameter detection.
 */
class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: return

        val screen = uri.getQueryParameter("screen")
        val id = uri.getQueryParameter("id")

        Log.d("DeepLink", "screen=$screen id=$id")
    }
}
