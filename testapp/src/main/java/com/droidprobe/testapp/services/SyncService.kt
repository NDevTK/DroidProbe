package com.droidprobe.testapp.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Tests that Service extras from onStartCommand are detected and linked to the service component.
 */
class SyncService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val syncType = intent?.getStringExtra("sync_type")
        val force = intent?.getBooleanExtra("force", false)

        if (syncType != null) {
            android.util.Log.d("SyncService", "Syncing $syncType, force=$force")
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
