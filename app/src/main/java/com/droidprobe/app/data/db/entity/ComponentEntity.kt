package com.droidprobe.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "components")
data class ComponentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val componentName: String,
    val componentType: String,
    val isExported: Boolean,
    val permission: String?,
    val intentFiltersJson: String?
)
