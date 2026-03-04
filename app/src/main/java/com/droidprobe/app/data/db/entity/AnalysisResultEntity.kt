package com.droidprobe.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analysis_results")
data class AnalysisResultEntity(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val analysisVersion: Int,
    val manifestJson: String,
    val dexJson: String?,
    val analyzedAt: Long
)
