package com.droidprobe.app.data.repository

import com.droidprobe.app.data.model.AppInfo
import com.droidprobe.app.scanner.PackageScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val packageScanner: PackageScanner) {

    suspend fun getInstalledApps(includeSystemApps: Boolean = false): List<AppInfo> {
        return withContext(Dispatchers.IO) {
            packageScanner.getInstalledApps(includeSystemApps)
        }
    }
}
