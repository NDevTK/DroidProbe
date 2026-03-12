package com.droidprobe.app.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.AppInfo
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.data.repository.AppRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppListUiState(
    val allApps: List<AppInfo> = emptyList(),
    val filteredApps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val error: String? = null,
    val bulkScanProgress: BulkScanProgress? = null
)

data class BulkScanProgress(
    val scanned: Int,
    val total: Int,
    val currentApp: String,
    val failed: Int = 0
)

class AppListViewModel(
    private val appRepository: AppRepository,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()
    private var bulkScanJob: Job? = null

    init {
        loadApps()
    }

    fun loadApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Always load all apps — for a security tool, system apps are prime targets
                val apps = appRepository.getInstalledApps(includeSystemApps = true)
                _uiState.update {
                    it.copy(
                        allApps = apps,
                        filteredApps = filterApps(apps, it.searchQuery),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load apps")
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredApps = filterApps(it.allApps, query)
            )
        }
    }

    fun startBulkScan() {
        if (bulkScanJob?.isActive == true) return
        val apps = _uiState.value.allApps
        if (apps.isEmpty()) return

        bulkScanJob = viewModelScope.launch {
            var scanned = 0
            var failed = 0
            _uiState.update {
                it.copy(bulkScanProgress = BulkScanProgress(0, apps.size, apps.first().appName))
            }

            for (app in apps) {
                _uiState.update {
                    it.copy(bulkScanProgress = BulkScanProgress(scanned, apps.size, app.appName, failed))
                }
                try {
                    val manifest = analysisRepository.analyzeManifest(
                        app.packageName, app.sourceDir, app.versionCode, app.appName
                    )
                    analysisRepository.analyzeDex(
                        apkPath = app.sourceDir,
                        manifestAnalysis = manifest,
                        versionCode = app.versionCode,
                        appName = app.appName
                    )
                } catch (_: Exception) {
                    failed++
                }
                scanned++
            }

            _uiState.update {
                it.copy(bulkScanProgress = null)
            }
        }
    }

    fun cancelBulkScan() {
        bulkScanJob?.cancel()
        bulkScanJob = null
        _uiState.update { it.copy(bulkScanProgress = null) }
    }

    private fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> {
        if (query.isBlank()) return apps
        val lowerQuery = query.lowercase()
        return apps.filter {
            it.appName.lowercase().contains(lowerQuery) ||
                    it.packageName.lowercase().contains(lowerQuery)
        }
    }

    class Factory(
        private val appRepository: AppRepository,
        private val analysisRepository: AnalysisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppListViewModel(appRepository, analysisRepository) as T
        }
    }
}
