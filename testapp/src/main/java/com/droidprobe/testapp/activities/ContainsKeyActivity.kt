package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests Bundle.containsKey() detection.
 * IntentExtraExtractor should detect "feature_flag" and "experiment_id"
 * as extras via containsKey() calls.
 * Exported with action: com.droidprobe.testapp.action.CONTAINS_KEY
 */
class ContainsKeyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val extras = intent.extras
        if (extras != null) {
            if (extras.containsKey("feature_flag")) {
                Log.d("ContainsKey", "Has feature_flag")
            }
            if (extras.containsKey("experiment_id")) {
                Log.d("ContainsKey", "Has experiment_id")
            }
        }
    }
}
