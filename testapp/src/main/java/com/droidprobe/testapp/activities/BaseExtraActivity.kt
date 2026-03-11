package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Base class (NOT exported) reading extras.
 * Tests inheritance resolution — these extras should propagate to InheritedChildActivity.
 */
open class BaseExtraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = intent.getStringExtra("session_token")
        val debugMode = intent.getBooleanExtra("debug_mode", false)

        Log.d("BaseExtra", "sessionToken=$sessionToken debugMode=$debugMode")
    }
}
