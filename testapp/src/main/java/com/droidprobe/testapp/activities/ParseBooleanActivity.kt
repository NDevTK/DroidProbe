package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests Boolean.parseBoolean() forward value detection.
 * ForwardValueScanner should detect parseBoolean() and add "true", "false" as possibleValues.
 * Exported with action: com.droidprobe.testapp.action.PARSE_BOOL
 */
class ParseBooleanActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val verbose = intent.getStringExtra("verbose")
        if (verbose != null) {
            val isVerbose = java.lang.Boolean.parseBoolean(verbose)
            if (isVerbose) {
                Log.d("ParseBoolean", "Verbose mode enabled")
            }
        }
    }
}
