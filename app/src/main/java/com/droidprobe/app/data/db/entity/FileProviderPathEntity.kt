package com.droidprobe.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_provider_paths")
data class FileProviderPathEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val authority: String,
    val pathType: String,
    val path: String,
    val name: String
)
