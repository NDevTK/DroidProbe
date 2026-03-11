package com.droidprobe.testapp.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tests that BroadcastReceiver extras are detected and linked to the receiver component.
 */
class DataReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val source = intent.getStringExtra("source")
        val priority = intent.getIntExtra("priority", 0)

        if (source != null) {
            android.util.Log.d("DataReceiver", "Received from $source with priority $priority")
        }
    }
}
