package com.droidprobe.app.ui.providers

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.ContentProviderCallInfo
import com.droidprobe.app.data.model.ProviderComponent
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.ContentProviderInteractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QueryableUri(
    val uri: String,
    val source: String,
    val sourceClass: String?,
    val matchCode: Int?,
    val columns: List<String>,
    val result: ContentProviderInteractor.QueryResult? = null,
    val isQuerying: Boolean = false
)

data class ContentProviderUiState(
    val packageName: String = "",
    val providers: List<ProviderComponent> = emptyList(),
    val queryableUris: List<QueryableUri> = emptyList(),
    val callMethods: List<ContentProviderCallInfo> = emptyList(),
    val expandedUri: String? = null,
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

                dex?.contentProviderUris
                    ?.filter { it.uriPattern.startsWith("content://") }
                    ?.forEach { info ->
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
                        queryableUris = uris,
                        callMethods = dex?.contentProviderCalls ?: emptyList()
                    )
                }
            } catch (_: Exception) { }
        }
    }

    fun queryUri(queryableUri: QueryableUri) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    queryableUris = state.queryableUris.map {
                        if (it.uri == queryableUri.uri) it.copy(isQuerying = true) else it
                    },
                    error = null
                )
            }
            try {
                val result = interactor.query(
                    ContentProviderInteractor.QueryParams(
                        uri = Uri.parse(queryableUri.uri),
                        projection = null,
                        selection = null,
                        selectionArgs = null,
                        sortOrder = null
                    )
                )
                _uiState.update { state ->
                    state.copy(
                        queryableUris = state.queryableUris.map {
                            if (it.uri == queryableUri.uri) it.copy(
                                result = result,
                                isQuerying = false
                            ) else it
                        },
                        expandedUri = queryableUri.uri,
                        error = if (result.error != null) result.error else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        queryableUris = state.queryableUris.map {
                            if (it.uri == queryableUri.uri) it.copy(
                                result = ContentProviderInteractor.QueryResult(
                                    emptyList(), emptyList(), 0, e.message ?: "Query failed"
                                ),
                                isQuerying = false
                            ) else it
                        },
                        error = e.message
                    )
                }
            }
        }
    }

    fun queryAll() {
        _uiState.value.queryableUris.filter { it.result == null }.forEach { queryUri(it) }
    }

    fun toggleExpand(uri: String) {
        _uiState.update {
            it.copy(expandedUri = if (it.expandedUri == uri) null else uri)
        }
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
