package com.droidprobe.app.ui.providers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.ContentProviderInfo
import com.droidprobe.app.data.model.ProviderComponent
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.ContentProviderInteractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents a queryable URI entry in the UI — either from DEX analysis or manifest authorities.
 */
data class QueryableUri(
    val uri: String,
    val source: String,        // "DEX" or "Manifest"
    val sourceClass: String?,  // class where found (DEX only)
    val matchCode: Int?,
    val columns: List<String>
)

data class ContentProviderUiState(
    val packageName: String = "",
    val providers: List<ProviderComponent> = emptyList(),
    val queryableUris: List<QueryableUri> = emptyList(),
    // Active query
    val selectedUri: QueryableUri? = null,
    val queryResult: ContentProviderInteractor.QueryResult? = null,
    val isExecuting: Boolean = false,
    val error: String? = null
)

class ContentProviderViewModel(
    private val packageName: String,
    private val interactor: ContentProviderInteractor,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContentProviderUiState(packageName = packageName))
    val uiState: StateFlow<ContentProviderUiState> = _uiState.asStateFlow()

    init {
        loadAnalysis()
    }

    private fun loadAnalysis() {
        viewModelScope.launch {
            try {
                val manifest = analysisRepository.analyzeManifest(packageName)
                val dex = analysisRepository.getCachedDex(packageName)

                val uris = mutableListOf<QueryableUri>()

                // Add URIs from DEX analysis
                dex?.contentProviderUris?.forEach { info ->
                    uris.add(
                        QueryableUri(
                            uri = info.uriPattern,
                            source = "DEX",
                            sourceClass = info.sourceClass
                                .removePrefix("L").removeSuffix(";").replace('/', '.'),
                            matchCode = info.matchCode,
                            columns = info.associatedColumns
                        )
                    )
                }

                // Add raw content:// strings from DEX that weren't already found
                val knownUris = uris.map { it.uri }.toSet()
                dex?.rawContentUriStrings?.forEach { raw ->
                    if (raw !in knownUris) {
                        uris.add(
                            QueryableUri(
                                uri = raw,
                                source = "String constant",
                                sourceClass = null,
                                matchCode = null,
                                columns = emptyList()
                            )
                        )
                    }
                }

                // Add manifest authorities as base URIs if no DEX URIs found for them
                val dexAuthorities = uris.mapNotNull { Uri.parse(it.uri).authority }.toSet()
                manifest.providers.filter { it.isExported }.forEach { provider ->
                    val auth = provider.authority ?: return@forEach
                    if (auth !in dexAuthorities) {
                        uris.add(
                            QueryableUri(
                                uri = "content://$auth/",
                                source = "Manifest",
                                sourceClass = provider.name,
                                matchCode = null,
                                columns = emptyList()
                            )
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        providers = manifest.providers,
                        queryableUris = uris
                    )
                }
            } catch (_: Exception) { }
        }
    }

    fun queryUri(queryableUri: QueryableUri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(selectedUri = queryableUri, isExecuting = true, error = null, queryResult = null)
            }
            try {
                val result = interactor.query(
                    ContentProviderInteractor.QueryParams(
                        uri = Uri.parse(queryableUri.uri),
                        projection = null, // all columns
                        selection = null,
                        selectionArgs = null,
                        sortOrder = null
                    )
                )
                _uiState.update {
                    it.copy(queryResult = result, isExecuting = false)
                }
                if (result.error != null) {
                    _uiState.update { it.copy(error = result.error) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExecuting = false, error = e.message ?: "Query failed")
                }
            }
        }
    }

    fun clearResult() {
        _uiState.update { it.copy(selectedUri = null, queryResult = null, error = null) }
    }

    class Factory(
        private val packageName: String,
        private val interactor: ContentProviderInteractor,
        private val analysisRepository: AnalysisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ContentProviderViewModel(packageName, interactor, analysisRepository) as T
        }
    }
}
