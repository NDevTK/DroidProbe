package com.droidprobe.app.ui.analysis

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.analysis.SecurityAnalyzer
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ManifestAnalysis
import com.droidprobe.app.data.repository.AnalysisRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisUiState(
    val packageName: String = "",
    val appName: String = "",
    val sourceDir: String = "",
    val manifestAnalysis: ManifestAnalysis? = null,
    val dexAnalysis: DexAnalysis? = null,
    val isLoading: Boolean = true,
    val isDexAnalyzing: Boolean = false,
    val dexProgress: String = "",
    val error: String? = null
)

class AnalysisViewModel(
    private val packageName: String,
    private val analysisRepository: AnalysisRepository,
    private val packageManager: PackageManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState(packageName = packageName))
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        analyze()
    }

    fun analyze() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                val sourceDir = appInfo.sourceDir
                val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode

                val manifest = analysisRepository.analyzeManifest(packageName, sourceDir, versionCode, appName)

                _uiState.update {
                    it.copy(
                        appName = appName,
                        sourceDir = sourceDir,
                        manifestAnalysis = manifest,
                        isLoading = false
                    )
                }

                // Automatically start DEX analysis
                analyzeDexInternal(sourceDir, manifest, versionCode, appName)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Analysis failed"
                    )
                }
            }
        }
    }

    private suspend fun analyzeDexInternal(
        sourceDir: String,
        manifest: ManifestAnalysis,
        versionCode: Long,
        appName: String
    ) {
        _uiState.update { it.copy(isDexAnalyzing = true, dexProgress = "Scanning bytecode...") }
        try {
            val dexAnalysis = analysisRepository.analyzeDex(
                apkPath = sourceDir,
                manifestAnalysis = manifest,
                versionCode = versionCode,
                appName = appName,
                onProgress = { progress ->
                    _uiState.update { it.copy(dexProgress = progress.message) }
                }
            )

            _uiState.update {
                it.copy(
                    dexAnalysis = dexAnalysis,
                    isDexAnalyzing = false,
                    dexProgress = ""
                )
            }

            // Load cross-app warnings in background
            loadCrossAppWarnings(manifest, dexAnalysis)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isDexAnalyzing = false,
                    dexProgress = "",
                    error = "DEX analysis failed: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadCrossAppWarnings(manifest: ManifestAnalysis, dex: DexAnalysis) {
        try {
            val crossAppData = analysisRepository.getAllCrossAppData(packageName)
            if (crossAppData.isEmpty()) return

            val crossWarnings = SecurityAnalyzer().analyzeCrossApp(manifest, dex, crossAppData)
            if (crossWarnings.isEmpty()) return

            _uiState.update { state ->
                val currentDex = state.dexAnalysis ?: return@update state
                state.copy(
                    dexAnalysis = currentDex.copy(
                        securityWarnings = currentDex.securityWarnings + crossWarnings
                    )
                )
            }
        } catch (_: Exception) {
            // Cross-app analysis is best-effort; don't fail the main analysis
        }
    }

    class Factory(
        private val packageName: String,
        private val analysisRepository: AnalysisRepository,
        private val packageManager: PackageManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AnalysisViewModel(packageName, analysisRepository, packageManager) as T
        }
    }
}
