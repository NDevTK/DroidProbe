package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests permission-protected exported activity.
 * Manifest declares android:permission="com.droidprobe.testapp.permission.ADMIN"
 * IntentExtraExtractor should detect "admin_command" extra.
 * UI should show "Protected" badge with permission name.
 */
class PermissionProtectedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val command = intent.getStringExtra("admin_command")
        val level = intent.getIntExtra("access_level", 0)

        Log.d("PermProtected", "command=$command level=$level")
    }
}
