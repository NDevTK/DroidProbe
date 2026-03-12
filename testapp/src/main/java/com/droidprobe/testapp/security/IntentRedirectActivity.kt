package com.droidprobe.testapp.security

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Test pattern: Intent redirection (confused deputy).
 * Gets a Parcelable extra and uses it to start another activity.
 * SecurityPatternDetector should flag INTENT_REDIRECTION.
 */
class IntentRedirectActivity : Activity() {

    @JvmField val TAG = "IntentRedirect"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val nextIntent = intent.getParcelableExtra<Intent>("next_intent")
        if (nextIntent != null) {
            startActivity(nextIntent)
        }
        finish()
    }
}
