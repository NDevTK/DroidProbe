package com.droidprobe.app.scanner

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.droidprobe.app.data.model.AppInfo

class PackageScanner(private val packageManager: PackageManager) {

    fun getInstalledApps(includeSystemApps: Boolean = false): List<AppInfo> {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps
            .filter { includeSystemApps || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.toAppInfo() }
            .sortedBy { it.appName.lowercase() }
    }

    private fun ApplicationInfo.toAppInfo(): AppInfo {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return AppInfo(
            packageName = packageName,
            appName = packageManager.getApplicationLabel(this).toString(),
            versionName = packageInfo.versionName,
            versionCode = packageInfo.longVersionCode,
            sourceDir = sourceDir,
            isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            icon = try {
                packageManager.getApplicationIcon(this)
            } catch (_: Exception) {
                null
            },
            targetSdk = targetSdkVersion,
            minSdk = minSdkVersion,
            uid = uid
        )
    }
}
