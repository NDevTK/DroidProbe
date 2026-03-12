package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests getXxxExtra() default value extraction.
 * IntentExtraExtractor should detect the default values for typed extras.
 */
class DefaultValueActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val retryCount = intent.getIntExtra("retry_count", 3)
        val debugMode = intent.getBooleanExtra("debug_mode", false)
        val timeout = intent.getLongExtra("timeout_ms", 5000L)

        Log.d("Defaults", "retry=$retryCount debug=$debugMode timeout=$timeout")
    }
}
