package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests inter-procedural string value resolution.
 * Gets extra, passes to private method which does the string comparisons.
 * Exported with action: com.droidprobe.testapp.action.INTERPROC
 */
class InterProcActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val navAction = intent.getStringExtra("nav_action")
        if (navAction != null) {
            handleNavAction(navAction)
        }
    }

    private fun handleNavAction(action: String) {
        if (action.equals("home")) {
            Log.d("InterProc", "Navigating home")
        } else if (action.equals("settings")) {
            Log.d("InterProc", "Navigating to settings")
        } else if (action.equals("profile")) {
            Log.d("InterProc", "Navigating to profile")
        }
    }
}
