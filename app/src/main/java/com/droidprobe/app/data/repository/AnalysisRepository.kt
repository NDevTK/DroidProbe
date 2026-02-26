package com.droidprobe.app.data.repository

import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.analysis.manifest.ManifestAnalyzer
import com.droidprobe.app.data.db.dao.AnalysisResultDao
import com.droidprobe.app.data.db.entity.AnalysisResultEntity
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ManifestAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class AnalysisRepository(
    private val manifestAnalyzer: ManifestAnalyzer,
    private val dexAnalyzer: DexAnalyzer,
    private val dao: AnalysisResultDao
) {
    // In-memory cache so child screens can access results without re-analyzing
    private val dexCache = ConcurrentHashMap<String, DexAnalysis>()
    private val manifestCache = ConcurrentHashMap<String, ManifestAnalysis>()

    suspend fun analyzeManifest(packageName: String, apkPath: String? = null): ManifestAnalysis {
        manifestCache[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            manifestAnalyzer.analyze(packageName, apkPath).also {
                manifestCache[packageName] = it
            }
        }
    }

    fun getCachedManifest(packageName: String): ManifestAnalysis? = manifestCache[packageName]
    fun getCachedDex(packageName: String): DexAnalysis? = dexCache[packageName]

    suspend fun analyzeDex(
        apkPath: String,
        manifestAnalysis: ManifestAnalysis,
        onProgress: ((DexAnalyzer.ProgressUpdate) -> Unit)? = null
    ): DexAnalysis {
        return dexAnalyzer.analyze(apkPath, manifestAnalysis, onProgress).also {
            dexCache[manifestAnalysis.packageName] = it
        }
    }

    suspend fun getCachedResult(packageName: String): AnalysisResultEntity? {
        return withContext(Dispatchers.IO) {
            dao.getAnalysisResult(packageName)
        }
    }

    suspend fun cacheResult(
        packageName: String,
        appName: String,
        versionCode: Long,
        manifestJson: String,
        dexJson: String? = null
    ) {
        withContext(Dispatchers.IO) {
            dao.insertAnalysisResult(
                AnalysisResultEntity(
                    packageName = packageName,
                    appName = appName,
                    versionCode = versionCode,
                    manifestJson = manifestJson,
                    dexJson = dexJson,
                    analyzedAt = System.currentTimeMillis()
                )
            )
        }
    }
}
