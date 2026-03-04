package com.droidprobe.app.data.repository

import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.analysis.manifest.ManifestAnalyzer
import com.droidprobe.app.data.db.dao.AnalysisResultDao
import com.droidprobe.app.data.db.entity.AnalysisResultEntity
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ManifestAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

class AnalysisRepository(
    private val manifestAnalyzer: ManifestAnalyzer,
    private val dexAnalyzer: DexAnalyzer,
    private val dao: AnalysisResultDao
) {
    companion object {
        // ╔══════════════════════════════════════════════════════════════╗
        // ║  BUMP THIS when analysis logic changes (extractors, CFG,   ║
        // ║  collectors, manifest parsing). Invalidates ALL cached     ║
        // ║  DEX/manifest results.                                     ║
        // ╚══════════════════════════════════════════════════════════════╝
        const val ANALYSIS_VERSION = 1
    }

    private val json = Json { ignoreUnknownKeys = true }

    // In-memory cache so child screens can access results without re-analyzing
    private val dexCache = ConcurrentHashMap<String, DexAnalysis>()
    private val manifestCache = ConcurrentHashMap<String, ManifestAnalysis>()

    suspend fun analyzeManifest(
        packageName: String,
        apkPath: String? = null,
        versionCode: Long = 0,
        appName: String = ""
    ): ManifestAnalysis {
        manifestCache[packageName]?.let { return it }

        // Check disk cache
        if (versionCode > 0) {
            val cached = withContext(Dispatchers.IO) { dao.getAnalysisResult(packageName) }
            if (cached != null &&
                cached.versionCode == versionCode &&
                cached.analysisVersion == ANALYSIS_VERSION
            ) {
                try {
                    val manifest = json.decodeFromString<ManifestAnalysis>(cached.manifestJson)
                    manifestCache[packageName] = manifest
                    return manifest
                } catch (_: Exception) {
                    // Corrupted cache, re-analyze
                }
            }
        }

        return withContext(Dispatchers.IO) {
            manifestAnalyzer.analyze(packageName, apkPath).also { manifest ->
                manifestCache[packageName] = manifest
                // Persist manifest to disk cache
                if (versionCode > 0) {
                    persistResult(packageName, appName, versionCode, manifest, dexJson = null)
                }
            }
        }
    }

    fun getCachedManifest(packageName: String): ManifestAnalysis? = manifestCache[packageName]
    fun getCachedDex(packageName: String): DexAnalysis? = dexCache[packageName]

    suspend fun analyzeDex(
        apkPath: String,
        manifestAnalysis: ManifestAnalysis,
        versionCode: Long = 0,
        appName: String = "",
        onProgress: ((DexAnalyzer.ProgressUpdate) -> Unit)? = null
    ): DexAnalysis {
        val packageName = manifestAnalysis.packageName

        // Check disk cache
        if (versionCode > 0) {
            val cached = withContext(Dispatchers.IO) { dao.getAnalysisResult(packageName) }
            if (cached != null &&
                cached.versionCode == versionCode &&
                cached.analysisVersion == ANALYSIS_VERSION &&
                cached.dexJson != null
            ) {
                try {
                    val dex = json.decodeFromString<DexAnalysis>(cached.dexJson)
                    dexCache[packageName] = dex
                    return dex
                } catch (_: Exception) {
                    // Corrupted cache, re-analyze
                }
            }
        }

        return dexAnalyzer.analyze(apkPath, manifestAnalysis, onProgress).also { dex ->
            dexCache[packageName] = dex
            // Persist to disk cache
            if (versionCode > 0) {
                persistResult(packageName, appName, versionCode, manifestAnalysis, json.encodeToString(dex))
            }
        }
    }

    private suspend fun persistResult(
        packageName: String,
        appName: String,
        versionCode: Long,
        manifest: ManifestAnalysis,
        dexJson: String?
    ) {
        withContext(Dispatchers.IO) {
            dao.insertAnalysisResult(
                AnalysisResultEntity(
                    packageName = packageName,
                    appName = appName,
                    versionCode = versionCode,
                    analysisVersion = ANALYSIS_VERSION,
                    manifestJson = json.encodeToString(manifest),
                    dexJson = dexJson,
                    analyzedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getCachedResult(packageName: String): AnalysisResultEntity? {
        return withContext(Dispatchers.IO) {
            dao.getAnalysisResult(packageName)
        }
    }
}
