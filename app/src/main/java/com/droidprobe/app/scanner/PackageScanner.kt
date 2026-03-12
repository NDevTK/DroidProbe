package com.droidprobe.app.scanner

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.droidprobe.app.data.model.AppInfo
import java.security.MessageDigest

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
            uid = uid,
            certSha1 = extractCertSha1(packageName)
        )
    }

    /**
     * Extract the SHA1 fingerprint of the app's signing certificate.
     * Used for constructing X-Goog-Spatula headers and identifying
     * the Google Cloud project context for Android API authentication.
     */
    @Suppress("DEPRECATION")
    private fun extractCertSha1(pkg: String): String? {
        return try {
            val signingInfo = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val pi = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                pi.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                val pi = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                pi.signatures?.firstOrNull()
            }
            if (signingInfo != null) {
                val digest = MessageDigest.getInstance("SHA-1")
                val sha1 = digest.digest(signingInfo.toByteArray())
                sha1.joinToString("") { "%02x".format(it) }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
