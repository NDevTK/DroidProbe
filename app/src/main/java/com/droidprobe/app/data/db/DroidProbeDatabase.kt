package com.droidprobe.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.droidprobe.app.data.db.dao.AnalysisResultDao
import com.droidprobe.app.data.db.entity.AnalysisResultEntity
import com.droidprobe.app.data.db.entity.ComponentEntity
import com.droidprobe.app.data.db.entity.ContentProviderUriEntity
import com.droidprobe.app.data.db.entity.FileProviderPathEntity
import com.droidprobe.app.data.db.entity.IntentInfoEntity

@Database(
    entities = [
        AnalysisResultEntity::class,
        ComponentEntity::class,
        ContentProviderUriEntity::class,
        IntentInfoEntity::class,
        FileProviderPathEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class DroidProbeDatabase : RoomDatabase() {
    abstract fun analysisResultDao(): AnalysisResultDao
}
