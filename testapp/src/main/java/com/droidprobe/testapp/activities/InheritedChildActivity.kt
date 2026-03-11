package com.droidprobe.testapp.activities

import android.os.Bundle
import android.util.Log

/**
 * Extends BaseExtraActivity — tests inheritance resolution.
 * Should have its own extras PLUS inherited session_token and debug_mode.
 * Exported with action: com.droidprobe.testapp.action.INHERITED
 */
class InheritedChildActivity : BaseExtraActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetScreen = intent.getStringExtra("target_screen")
        val refreshInterval = intent.getLongExtra("refresh_interval", 5000L)

        Log.d("InheritedChild", "targetScreen=$targetScreen refreshInterval=$refreshInterval")
    }
}
