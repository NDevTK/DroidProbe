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
    val manifestJson: ByteArray,
    val dexJson: ByteArray?,
    val analyzedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnalysisResultEntity) return false
        return packageName == other.packageName
    }
    override fun hashCode(): Int = packageName.hashCode()
}
