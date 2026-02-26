package com.droidprobe.app.test

import android.os.Bundle
import android.widget.TextView

/**
 * Exported activity that extends TestBaseActivity.
 * It reads its own extras AND inherits base class extras.
 * Tests inheritance chain detection: extras from TestBaseActivity
 * (session_token, debug_mode) should show up on this component too.
 */
class TestInheritedActivity : TestBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetScreen = intent.getStringExtra("target_screen")
        val refreshInterval = intent.getLongExtra("refresh_interval", 5000L)

        // Append to whatever the base class set up
        val tv = TextView(this).apply {
            text = "Inherited extras: target_screen=$targetScreen, " +
                    "refresh_interval=$refreshInterval"
            textSize = 16f
        }
        (window.decorView as? android.widget.LinearLayout)?.addView(tv)
    }
}
