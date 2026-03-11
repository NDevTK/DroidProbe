package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests startsWith value detection.
 * ForwardValueScanner.scanStringValues() should detect "air" and "sea" as startsWith values.
 * Exported with action: com.droidprobe.testapp.action.PREFIX
 */
class PrefixValueActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val transport = intent.getStringExtra("transport")
        if (transport != null) {
            if (transport.startsWith("air")) {
                Log.d("Prefix", "Air transport")
            } else if (transport.startsWith("sea")) {
                Log.d("Prefix", "Sea transport")
            }
        }
    }
}
