package com.droidprobe.app.ui.googleapi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.DiscoveryDocument
import com.droidprobe.app.data.model.DiscoveryMethod
import com.droidprobe.app.data.model.ExecutionResult
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.ApiSpecFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GoogleApiUiState(
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
    val isExecuting: Boolean = false
)

class GoogleApiExplorerViewModel(
    private val packageName: String,
    private val rootUrl: String,
    private val fetcher: ApiSpecFetcher,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GoogleApiUiState(rootUrl = rootUrl))
    val uiState: StateFlow<GoogleApiUiState> = _uiState.asStateFlow()

    init {
        loadApiKeys()
        fetchDiscovery(_uiState.value.selectedApiKey)
    }

    private fun loadApiKeys() {
        val dex = analysisRepository.getCachedDex(packageName) ?: return
        // Include all key/token-like sensitive strings as potential auth
        val keyCategories = setOf(
            "Google API Key", "Stripe Key", "Square Key", "Slack Token",
            "GitHub Token", "GitLab Token", "Twilio Key", "SendGrid Key",
            "Mapbox Token", "Algolia Key", "Bearer Token"
        )
        val allKeys = dex.sensitiveStrings
            .filter { it.category in keyCategories }

        if (allKeys.isEmpty()) return

        val keys = allKeys.map { it.value }.distinct()

        // Score each key by proximity to this rootUrl
        val rootHost = try {
            java.net.URL(rootUrl).host
        } catch (_: Exception) { rootUrl }

        val endpointSourceClasses = dex.apiEndpoints
            .filter { it.baseUrl.contains(rootHost, ignoreCase = true) }
            .map { it.sourceClass }
            .toSet()

        val scored = allKeys.distinctBy { it.value }.sortedByDescending { secret ->
            var score = 0
            // Highest: same source class as an endpoint using this rootUrl
            if (secret.sourceClass in endpointSourceClasses) score += 100
            // High: associated URLs contain this rootUrl host
            if (secret.associatedUrls.any { it.contains(rootHost, ignoreCase = true) }) score += 50
            // Medium: same package prefix as endpoint source classes
            val secretPkg = secret.sourceClass.substringBeforeLast('/').removePrefix("L")
            if (endpointSourceClasses.any { it.substringBeforeLast('/').removePrefix("L") == secretPkg }) score += 25
            // Bonus: Google API keys get a boost for googleapis.com hosts
            if (secret.category == "Google API Key" && rootHost.endsWith("googleapis.com")) score += 75
            score
        }

        val bestKey = scored.firstOrNull()?.value ?: keys.first()

        _uiState.update {
            it.copy(apiKeys = keys, selectedApiKey = bestKey)
        }
    }

    private fun fetchDiscovery(apiKey: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            fetcher.fetchSpec(rootUrl, apiKey).fold(
                onSuccess = { doc ->
                    _uiState.update { it.copy(discovery = doc, isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to fetch discovery document")
                    }
                }
            )
        }
    }

    fun retry() {
        fetchDiscovery(_uiState.value.selectedApiKey)
    }

    fun selectApiKey(key: String) {
        _uiState.update { it.copy(selectedApiKey = key) }
    }

    fun selectMethod(method: DiscoveryMethod?) {
        if (method == null) {
            _uiState.update { it.copy(selectedMethod = null, paramValues = emptyMap(), requestBody = "", executionResult = null) }
            return
        }
        // Pre-fill defaults
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
            return GoogleApiExplorerViewModel(packageName, rootUrl, fetcher, analysisRepository) as T
        }
    }
}
