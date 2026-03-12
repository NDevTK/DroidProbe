package com.droidprobe.app.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val sourceDir: String,
    val isSystemApp: Boolean,
    val icon: Drawable?,
    val targetSdk: Int,
    val minSdk: Int,
    val uid: Int,
    val certSha1: String? = null
)
