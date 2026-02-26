package com.droidprobe.app.test

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout

/**
 * Base class that reads common extras. Extras here should be detected
 * and attributed to any exported subclass (e.g. TestInheritedActivity).
 */
open class TestBaseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sessionToken = intent.getStringExtra("session_token")
        val debugMode = intent.getBooleanExtra("debug_mode", false)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Base extras: session_token=$sessionToken, debug_mode=$debugMode"
            textSize = 16f
        })
        setContentView(layout)
    }
}
