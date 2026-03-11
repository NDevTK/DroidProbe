package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests Bundle extras detection.
 * Exported with action: com.droidprobe.testapp.action.BUNDLE
 */
class BundleExtraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bundle = intent.extras ?: return

        val theme = bundle.getString("theme")
        val itemCount = bundle.getInt("item_count", 0)
        val autoRefresh = bundle.getBoolean("auto_refresh", true)

        Log.d("BundleExtra", "theme=$theme itemCount=$itemCount autoRefresh=$autoRefresh")
    }
}
