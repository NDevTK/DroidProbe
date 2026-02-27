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
    val key: String,
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
        val allInfos = analysisRepository.getCachedDex(packageName)?.fileProviderPaths ?: emptyList()
        val seen = mutableSetOf<String>()
        val paths = mutableListOf<ProbablePath>()

        // Separate XML config roots from code-reference entries, grouped by authority
        val byAuthority = allInfos.groupBy { it.authority }

        for ((authority, infos) in byAuthority) {
            val xmlRoots = infos.filter { it.pathType != "code-reference" }
            val codeRefs = infos.filter { it.pathType == "code-reference" && it.filePath != null }

            // For each code-discovered file, create a ProbablePath per XML root
            for (codeRef in codeRefs) {
                val filePath = codeRef.filePath ?: continue
                for (root in xmlRoots) {
                    val uri = "content://$authority/${root.name}/$filePath"
                    val key = "$authority:${root.name}:$filePath"
                    if (!seen.add(key)) continue
                    paths.add(
                        ProbablePath(
                            info = root.copy(filePath = filePath),
                            uri = uri,
                            key = key
                        )
                    )
                }
                // If no XML roots found, still create an entry with just authority + filePath
                if (xmlRoots.isEmpty()) {
                    val uri = "content://$authority/$filePath"
                    val key = "$authority:code:$filePath"
                    if (!seen.add(key)) continue
                    paths.add(ProbablePath(info = codeRef, uri = uri, key = key))
                }
            }

            // XML roots with no matching code file paths — keep as root-only
            if (codeRefs.isEmpty()) {
                for (root in xmlRoots) {
                    val uri = "content://$authority/${root.name}/"
                    val key = "$authority:${root.pathType}:${root.path}:${root.name}"
                    if (!seen.add(key)) continue
                    paths.add(ProbablePath(info = root, uri = uri, key = key))
                }
            }
        }

        _uiState.update { it.copy(paths = paths) }
    }

    fun probePath(path: ProbablePath) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    paths = state.paths.map {
                        if (it.key == path.key) it.copy(isProbing = true) else it
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
                            if (it.key == path.key) it.copy(result = result, isProbing = false) else it
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        paths = state.paths.map {
                            if (it.key == path.key) it.copy(isProbing = false) else it
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
