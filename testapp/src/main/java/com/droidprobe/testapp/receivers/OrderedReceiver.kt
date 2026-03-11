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
 * - "command" extra via getStringExtra() (standard extra detection)
 * Exported with action: com.droidprobe.testapp.action.ORDERED
 */
class OrderedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("command")
        Log.d("OrderedReceiver", "Received command: $command")

        resultCode = Activity.RESULT_OK
        resultData = "processed"

        val extras = Bundle()
        extras.putString("status", "complete")
        setResultExtras(extras)
    }
}
