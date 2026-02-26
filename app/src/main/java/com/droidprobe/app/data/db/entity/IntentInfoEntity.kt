package com.droidprobe.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "intent_info")
data class IntentInfoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val extraKey: String,
    val extraType: String?,
    val associatedAction: String?,
    val associatedComponent: String?,
    val sourceClass: String,
    val sourceMethod: String?
)
