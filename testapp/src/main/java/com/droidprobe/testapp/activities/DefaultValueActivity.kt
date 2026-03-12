package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests getXxxExtra() default value extraction for multiple types.
 * IntentExtraExtractor should detect the default values:
 * - retry_count: Int, default=3
 * - debug_mode: Boolean, default=false
 * - timeout_ms: Long, default=5000
 * - threshold: Float (no default extraction for float yet)
 * - max_size: Short, default=100
 * - priority: Byte, default=1
 * - verbose: Boolean, default=true
 */
class DefaultValueActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val retryCount = intent.getIntExtra("retry_count", 3)
        val debugMode = intent.getBooleanExtra("debug_mode", false)
        val timeout = intent.getLongExtra("timeout_ms", 5000L)
        val maxSize = intent.getShortExtra("max_size", 100)
        val priority = intent.getByteExtra("priority", 1)
        val verbose = intent.getBooleanExtra("verbose", true)
        val label = intent.getStringExtra("label")

        Log.d("Defaults", "retry=$retryCount debug=$debugMode timeout=$timeout " +
            "maxSize=$maxSize priority=$priority verbose=$verbose label=$label")
    }
}
