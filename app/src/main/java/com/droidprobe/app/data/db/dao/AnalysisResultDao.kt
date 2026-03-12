package com.droidprobe.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.droidprobe.app.data.db.entity.AnalysisResultEntity
import com.droidprobe.app.data.db.entity.ComponentEntity
import com.droidprobe.app.data.db.entity.ContentProviderUriEntity
import com.droidprobe.app.data.db.entity.FileProviderPathEntity
import com.droidprobe.app.data.db.entity.IntentInfoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysisResult(entity: AnalysisResultEntity)

    @Query("SELECT * FROM analysis_results WHERE packageName = :packageName")
    suspend fun getAnalysisResult(packageName: String): AnalysisResultEntity?

    @Query("SELECT * FROM analysis_results ORDER BY analyzedAt DESC")
    fun getAllAnalysisResults(): Flow<List<AnalysisResultEntity>>

    @Query("SELECT * FROM analysis_results WHERE analysisVersion = :version AND dexJson IS NOT NULL AND packageName != :excludePackage")
    suspend fun getAllAnalyzedExcept(version: Int, excludePackage: String): List<AnalysisResultEntity>

    @Query("DELETE FROM analysis_results WHERE packageName = :packageName")
    suspend fun deleteAnalysisResult(packageName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComponents(components: List<ComponentEntity>)

    @Query("SELECT * FROM components WHERE packageName = :packageName")
    suspend fun getComponentsForPackage(packageName: String): List<ComponentEntity>

    @Query("DELETE FROM components WHERE packageName = :packageName")
    suspend fun deleteComponentsForPackage(packageName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContentProviderUris(uris: List<ContentProviderUriEntity>)

    @Query("SELECT * FROM content_provider_uris WHERE packageName = :packageName")
    suspend fun getContentProviderUris(packageName: String): List<ContentProviderUriEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntentInfos(intents: List<IntentInfoEntity>)

    @Query("SELECT * FROM intent_info WHERE packageName = :packageName")
    suspend fun getIntentInfos(packageName: String): List<IntentInfoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileProviderPaths(paths: List<FileProviderPathEntity>)

    @Query("SELECT * FROM file_provider_paths WHERE packageName = :packageName")
    suspend fun getFileProviderPaths(packageName: String): List<FileProviderPathEntity>
}
