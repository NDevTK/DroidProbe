package com.droidprobe.testapp.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Tests ordered broadcast result setting.
 * IntentExtraExtractor should detect:
 * - "command" extra via getStringExtra() with values "start", "stop", "restart"
 * - "priority_level" extra via getIntExtra() with default=0
 *
 * OrderedBroadcastExtractor should detect:
 * - resultCode = Activity.RESULT_OK
 * - resultData = "processed"
 * - setResultExtras: "status"="complete", "timestamp" (Long)
 * - isOrderedBroadcast = true (calls resultCode/resultData/setResultExtras)
 *
 * Exported with action: com.droidprobe.testapp.action.ORDERED
 */
class OrderedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        val priorityLevel = intent.getIntExtra("priority_level", 0)
        Log.d("OrderedReceiver", "Received command: $command priority: $priorityLevel")

        if (command != null) {
            when {
                command.equals("start") -> Log.d("OrderedReceiver", "Starting")
                command.equals("stop") -> Log.d("OrderedReceiver", "Stopping")
                command.equals("restart") -> Log.d("OrderedReceiver", "Restarting")
            }
        }

        resultCode = Activity.RESULT_OK
        resultData = "processed"

        val extras = Bundle()
        extras.putString("status", "complete")
        extras.putLong("timestamp", System.currentTimeMillis())
        setResultExtras(extras)
    }
}
