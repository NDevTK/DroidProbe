package com.droidprobe.app.ui.fileprovider

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.FileProviderInfo
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.FileProviderAccessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A discovered file provider path with its probe result.
 */
data class ProbablePath(
    val info: FileProviderInfo,
    val uri: String,
    val result: FileProviderAccessor.FileInfo? = null,
    val isProbing: Boolean = false
)

data class FileProviderUiState(
    val packageName: String = "",
    val paths: List<ProbablePath> = emptyList(),
    val error: String? = null
)

class FileProviderViewModel(
    private val packageName: String,
    private val accessor: FileProviderAccessor,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileProviderUiState(packageName = packageName))
    val uiState: StateFlow<FileProviderUiState> = _uiState.asStateFlow()

    init {
        loadDiscoveredPaths()
    }

    private fun loadDiscoveredPaths() {
        val dex = analysisRepository.getCachedDex(packageName)
        val paths = (dex?.fileProviderPaths ?: emptyList()).map { info ->
            ProbablePath(
                info = info,
                uri = "content://${info.authority}/${info.name}"
            )
        }
        _uiState.update { it.copy(paths = paths) }
    }

    fun probePath(path: ProbablePath) {
        viewModelScope.launch {
            // Mark this path as probing
            _uiState.update { state ->
                state.copy(
                    paths = state.paths.map {
                        if (it.uri == path.uri) it.copy(isProbing = true) else it
                    },
                    error = null
                )
            }

            try {
                val uri = Uri.parse(path.uri)
                val result = accessor.probeUri(uri)
                _uiState.update { state ->
                    state.copy(
                        paths = state.paths.map {
                            if (it.uri == path.uri) it.copy(result = result, isProbing = false) else it
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        paths = state.paths.map {
                            if (it.uri == path.uri) it.copy(isProbing = false) else it
                        },
                        error = e.message
                    )
                }
            }
        }
    }

    fun probeAll() {
        _uiState.value.paths.filter { it.result == null }.forEach { probePath(it) }
    }

    class Factory(
        private val packageName: String,
        private val accessor: FileProviderAccessor,
        private val analysisRepository: AnalysisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return FileProviderViewModel(packageName, accessor, analysisRepository) as T
        }
    }
}
