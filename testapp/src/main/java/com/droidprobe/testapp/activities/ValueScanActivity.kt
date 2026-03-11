package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests forward value scanning: string equals, int switch (packed-switch), parseInt chain.
 * Exported with action: com.droidprobe.testapp.action.VALUES
 */
class ValueScanActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // String forward value scanning — equals comparisons
        val mode = intent.getStringExtra("mode")
        if (mode != null) {
            if (mode.equals("light")) {
                Log.d("ValueScan", "Light mode")
            } else if (mode.equals("dark")) {
                Log.d("ValueScan", "Dark mode")
            } else if (mode.equals("system")) {
                Log.d("ValueScan", "System mode")
            }
        }

        // Int forward value scanning — when/switch
        val level = intent.getIntExtra("level", 0)
        when (level) {
            1 -> Log.d("ValueScan", "Level 1 - Beginner")
            2 -> Log.d("ValueScan", "Level 2 - Intermediate")
            3 -> Log.d("ValueScan", "Level 3 - Advanced")
            4 -> Log.d("ValueScan", "Level 4 - Expert")
        }

        // parseInt chain: string → int → comparison
        val priorityCode = intent.getStringExtra("priority_code")
        if (priorityCode != null) {
            val code = Integer.parseInt(priorityCode)
            if (code == 10) {
                Log.d("ValueScan", "Low priority")
            } else if (code == 20) {
                Log.d("ValueScan", "Medium priority")
            } else if (code == 30) {
                Log.d("ValueScan", "High priority")
            }
        }
    }
}
