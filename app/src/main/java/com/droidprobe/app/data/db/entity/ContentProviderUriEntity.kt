package com.droidprobe.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "content_provider_uris")
data class ContentProviderUriEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val authority: String?,
    val uriPattern: String,
    val matchCode: Int?,
    val columnsJson: String?,
    val sourceClass: String,
    val sourceMethod: String?
)
