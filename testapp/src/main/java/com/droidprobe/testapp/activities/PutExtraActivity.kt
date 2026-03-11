package com.droidprobe.testapp.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Tests putExtra with literal values.
 * Exported with action: com.droidprobe.testapp.action.PUT
 */
class PutExtraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // putExtra with literal string, int, boolean values
        val launchIntent = Intent("com.droidprobe.testapp.action.DIRECT")
        launchIntent.putExtra("action_type", "navigate")
        launchIntent.putExtra("count", 42)
        launchIntent.putExtra("enabled", true)
        startActivity(launchIntent)
    }
}
