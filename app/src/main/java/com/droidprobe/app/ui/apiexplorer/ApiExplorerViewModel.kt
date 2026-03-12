package com.droidprobe.app.ui.apiexplorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.DiscoveryDocument
import com.droidprobe.app.data.model.DiscoveryMethod
import com.droidprobe.app.data.model.ExecutionResult
import com.droidprobe.app.data.model.KeyStatus
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.ApiSpecFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ApiExplorerUiState(
    val rootUrl: String = "",
    val apiKeys: List<String> = emptyList(),
    val selectedApiKey: String? = null,
    val discovery: DiscoveryDocument? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedMethod: DiscoveryMethod? = null,
    val paramValues: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val executionResult: ExecutionResult? = null,
    val isExecuting: Boolean = false,
    val keyValidation: Map<String, KeyStatus> = emptyMap(),
    val userSelectedKey: Boolean = false,
    val autoExecuteResult: Pair<DiscoveryMethod, ExecutionResult>? = null
)

/** Categories of sensitive strings that represent API keys/tokens (vs other secrets). */
val KEY_CATEGORIES = setOf(
    "Google API Key", "Stripe Key", "Square Key", "Slack Token",
    "GitHub Token", "GitLab Token", "Twilio Key", "SendGrid Key",
    "Mapbox Token", "Algolia Key", "Bearer Token"
)

class ApiExplorerViewModel(
    private val packageName: String,
    private val rootUrl: String,
    private val fetcher: ApiSpecFetcher,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApiExplorerUiState(rootUrl = rootUrl))
    val uiState: StateFlow<ApiExplorerUiState> = _uiState.asStateFlow()

    init {
        loadApiKeys()
        fetchDiscovery(_uiState.value.selectedApiKey)
    }

    private fun loadApiKeys() {
        val dex = analysisRepository.getCachedDex(packageName) ?: return
        val allKeys = dex.sensitiveStrings
            .filter { it.category in KEY_CATEGORIES }

        if (allKeys.isEmpty()) return

        val keys = allKeys.map { it.value }.distinct()

        val rootHost = try {
            java.net.URL(rootUrl).host
        } catch (_: Exception) { rootUrl }

        // CFG-verified: associatedUrls contains actual endpoints each key flows to
        val scored = allKeys.distinctBy { it.value }.sortedByDescending { secret ->
            if (secret.associatedUrls.any { it.contains(rootHost, ignoreCase = true) }) 1 else 0
        }

        val bestKey = scored.firstOrNull()?.value ?: keys.first()

        _uiState.update {
            it.copy(apiKeys = keys, selectedApiKey = bestKey)
        }
    }

    private fun fetchDiscovery(apiKey: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Build virtual doc from DEX endpoints
            val dex = analysisRepository.getCachedDex(packageName)
            val rootHost = try {
                java.net.URL(rootUrl).host
            } catch (_: Exception) { rootUrl }
            val matchingEndpoints = dex?.apiEndpoints?.filter {
                it.baseUrl.contains(rootHost, ignoreCase = true)
            } ?: emptyList()
            val virtualDoc = fetcher.synthesizeFromEndpoints(rootUrl, matchingEndpoints)

            // Attempt remote fetch
            val remoteResult = fetcher.fetchSpec(rootUrl, apiKey)

            val finalDoc = remoteResult.fold(
                onSuccess = { remoteDoc ->
                    if (virtualDoc != null) fetcher.mergeDocuments(remoteDoc, virtualDoc)
                    else remoteDoc
                },
                onFailure = {
                    virtualDoc
                }
            )

            if (finalDoc != null) {
                _uiState.update { it.copy(discovery = finalDoc, isLoading = false) }
                autoValidateAndExecute(finalDoc)
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = remoteResult.exceptionOrNull()?.message
                            ?: "No API specification found and no endpoints extracted"
                    )
                }
            }
        }
    }

    private fun autoValidateAndExecute(doc: DiscoveryDocument) {
        val keys = _uiState.value.apiKeys
        if (keys.isEmpty()) return

        // Find a simple GET method with no required path params
        val testMethod = findTestMethod(doc) ?: return

        viewModelScope.launch {
            // Mark all keys as TESTING
            val keysToTest = keys.take(5)
            _uiState.update {
                it.copy(keyValidation = keysToTest.associateWith { KeyStatus.TESTING })
            }

            val jobs = keysToTest.map { key ->
                async {
                    val result = withTimeoutOrNull(8_000L) {
                        fetcher.executeMethod(
                            rootUrl = doc.rootUrl.ifEmpty { rootUrl },
                            servicePath = doc.servicePath,
                            method = testMethod,
                            params = emptyMap(),
                            apiKey = key
                        ).getOrNull()
                    }
                    key to result
                }
            }

            var firstValidResult: Pair<String, ExecutionResult>? = null

            for (job in jobs) {
                val (key, result) = job.await()
                val status = when {
                    result == null -> KeyStatus.UNTESTED
                    result.statusCode in 200..299 -> KeyStatus.VALID
                    result.statusCode in listOf(401, 403) -> KeyStatus.INVALID
                    else -> KeyStatus.UNTESTED
                }
                _uiState.update {
                    it.copy(keyValidation = it.keyValidation + (key to status))
                }
                if (status == KeyStatus.VALID && firstValidResult == null) {
                    firstValidResult = key to result!!
                }
            }

            // Auto-select first valid key and show result
            if (firstValidResult != null && !_uiState.value.userSelectedKey) {
                _uiState.update {
                    it.copy(
                        selectedApiKey = firstValidResult.first,
                        autoExecuteResult = testMethod to firstValidResult.second
                    )
                }
            }
        }
    }

    private fun findTestMethod(doc: DiscoveryDocument): DiscoveryMethod? {
        val allMethods = mutableListOf<DiscoveryMethod>()
        fun collect(resources: Map<String, com.droidprobe.app.data.model.DiscoveryResource>) {
            for ((_, res) in resources) {
                allMethods.addAll(res.methods.values)
                collect(res.resources)
            }
        }
        collect(doc.resources)

        // Prefer GET with no required path params
        return allMethods
            .filter { it.httpMethod == "GET" }
            .sortedBy { m -> m.parameters.count { it.value.required && it.value.location == "path" } }
            .firstOrNull()
            ?: allMethods.firstOrNull { it.httpMethod == "GET" }
    }

    fun retry() {
        fetchDiscovery(_uiState.value.selectedApiKey)
    }

    fun selectApiKey(key: String) {
        _uiState.update { it.copy(selectedApiKey = key, userSelectedKey = true) }
    }

    fun selectMethod(method: DiscoveryMethod?) {
        if (method == null) {
            _uiState.update { it.copy(selectedMethod = null, paramValues = emptyMap(), requestBody = "", executionResult = null) }
            return
        }
        val defaults = method.parameters.mapValues { (_, param) ->
            param.default ?: ""
        }
        _uiState.update {
            it.copy(selectedMethod = method, paramValues = defaults, requestBody = "", executionResult = null)
        }
    }

    fun updateParam(name: String, value: String) {
        _uiState.update { it.copy(paramValues = it.paramValues + (name to value)) }
    }

    fun updateRequestBody(body: String) {
        _uiState.update { it.copy(requestBody = body) }
    }

    fun executeMethod() {
        val state = _uiState.value
        val method = state.selectedMethod ?: return
        val discovery = state.discovery ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true, executionResult = null) }
            val body = if (method.httpMethod in listOf("POST", "PUT", "PATCH") && state.requestBody.isNotBlank()) {
                state.requestBody
            } else null

            fetcher.executeMethod(
                rootUrl = discovery.rootUrl.ifEmpty { rootUrl },
                servicePath = discovery.servicePath,
                method = method,
                params = state.paramValues,
                apiKey = state.selectedApiKey,
                requestBody = body
            ).fold(
                onSuccess = { result ->
                    _uiState.update { it.copy(executionResult = result, isExecuting = false) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            executionResult = ExecutionResult(-1, e.message ?: "Request failed", emptyMap()),
                            isExecuting = false
                        )
                    }
                }
            )
        }
    }

    class Factory(
        private val packageName: String,
        private val rootUrl: String,
        private val fetcher: ApiSpecFetcher,
        private val analysisRepository: AnalysisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ApiExplorerViewModel(packageName, rootUrl, fetcher, analysisRepository) as T
        }
    }
}
