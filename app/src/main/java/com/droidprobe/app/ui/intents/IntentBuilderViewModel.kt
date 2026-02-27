package com.droidprobe.app.ui.intents

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidprobe.app.data.model.ExportedComponent
import com.droidprobe.app.data.model.IntentInfo
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.interaction.IntentLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A launchable target — a discovered exported component with pre-filled intent details.
 */
data class LaunchableTarget(
    val component: ExportedComponent,
    val type: String,  // "Activity", "Service", "Receiver"
    val actions: List<String>,
    val categories: List<String>,
    val dataSchemes: List<String>,
    val discoveredExtras: List<IntentInfo>,
    val discoveredDataUris: List<String> = emptyList(),
    /** Per-URI query params: full URI string → list of param names */
    val queryParamsByUri: Map<String, List<String>> = emptyMap(),
    /** Per-URI query param values: full URI string → (param → values) */
    val queryParamValuesByUri: Map<String, Map<String, List<String>>> = emptyMap(),
    /** Per-URI query param defaults: full URI string → (param → default value) */
    val queryParamDefaultsByUri: Map<String, Map<String, String>> = emptyMap()
)

data class ExtraEntry(
    val key: String = "",
    val value: String = "",
    val type: String = "String",
    val suggestedValues: List<String> = emptyList()
)

data class QueryParamEntry(
    val key: String = "",
    val value: String = "",
    val suggestedValues: List<String> = emptyList(),
    val defaultValue: String? = null
)

data class IntentBuilderUiState(
    val packageName: String = "",
    val targets: List<LaunchableTarget> = emptyList(),
    // Expanded target for editing extras before launch
    val expandedTarget: LaunchableTarget? = null,
    val extras: List<ExtraEntry> = emptyList(),
    val queryParams: List<QueryParamEntry> = emptyList(),
    val dataUri: String = "",
    val result: String? = null,
    val error: String? = null
)

class IntentBuilderViewModel(
    private val packageName: String,
    private val intentLauncher: IntentLauncher,
    private val analysisRepository: AnalysisRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IntentBuilderUiState(packageName = packageName))
    val uiState: StateFlow<IntentBuilderUiState> = _uiState.asStateFlow()

    init {
        loadComponents()
    }

    private fun loadComponents() {
        viewModelScope.launch {
            try {
                val manifest = analysisRepository.analyzeManifest(packageName)
                val dex = analysisRepository.getCachedDex(packageName)
                val discoveredExtras = dex?.intentExtras ?: emptyList()

                // Collect deep link URI patterns (non-content:// from UriMatcher/Uri.parse)
                val deepLinkPatterns = dex?.contentProviderUris
                    ?.filter { !it.uriPattern.startsWith("content://") }
                    ?: emptyList()

                // Build a scheme lookup from deep link string constants.
                // e.g. "clock-app://com.google.android.deskclock" -> scheme "clock-app"
                val schemeByAuthority = mutableMapOf<String, String>()
                dex?.deepLinkUriStrings?.forEach { uriStr ->
                    val schemeEnd = uriStr.indexOf("://")
                    if (schemeEnd > 0) {
                        val scheme = uriStr.substring(0, schemeEnd)
                        val authority = uriStr.substring(schemeEnd + 3).substringBefore('/')
                        if (authority.isNotEmpty()) {
                            schemeByAuthority[authority] = scheme
                        }
                    }
                }
                // Also check full URIs in contentProviderUris
                dex?.contentProviderUris?.forEach { info ->
                    val uri = info.uriPattern
                    val schemeEnd = uri.indexOf("://")
                    if (schemeEnd > 0) {
                        val scheme = uri.substring(0, schemeEnd)
                        val authority = uri.substring(schemeEnd + 3).substringBefore('/')
                        if (scheme != "content" && authority.isNotEmpty()) {
                            schemeByAuthority[authority] = scheme
                        }
                    }
                }

                val targets = mutableListOf<LaunchableTarget>()

                fun addTargets(components: List<ExportedComponent>, type: String) {
                    components.filter { it.isExported }.forEach { comp ->
                        val allActions = comp.intentFilters.flatMap { it.actions }
                        val allCategories = comp.intentFilters.flatMap { it.categories }
                        val allSchemes = comp.intentFilters.flatMap { it.dataSchemes }

                        // Match extras by associatedComponent — resolved from inheritance chain
                        val compExtras = discoveredExtras
                            .filter { it.associatedComponent == comp.name }
                            .distinctBy { it.extraKey }

                        // Match deep link URIs by sourceClass — same approach as extras.
                        // The sourceClass tells us which class the UriMatcher lives in.
                        val compSmali = "L${comp.name.replace('.', '/')};"
                        // Build per-component scheme fallback from manifest intent filters
                        val compSchemeByAuthority = mutableMapOf<String, String>()
                        var compFallbackScheme: String? = null
                        for (filter in comp.intentFilters) {
                            val schemes = filter.dataSchemes.filter { it != "content" }
                            if (schemes.isNotEmpty()) {
                                if (compFallbackScheme == null) compFallbackScheme = schemes.first()
                                for (authority in filter.dataAuthorities) {
                                    for (scheme in schemes) {
                                        compSchemeByAuthority.putIfAbsent(authority, scheme)
                                    }
                                }
                            }
                        }

                        val compDataUris = mutableListOf<String>()
                        val perUriParams = mutableMapOf<String, List<String>>()
                        val perUriParamValues = mutableMapOf<String, Map<String, List<String>>>()
                        val perUriParamDefaults = mutableMapOf<String, Map<String, String>>()
                        for (pattern in deepLinkPatterns) {
                            if (pattern.sourceClass != compSmali) continue
                            val fullUri: String
                            val uri = pattern.uriPattern
                            if (uri.contains("://")) {
                                fullUri = uri
                            } else {
                                val authority = uri.substringBefore('/')
                                val scheme = schemeByAuthority[authority]
                                    ?: compSchemeByAuthority[authority]
                                    ?: compFallbackScheme
                                fullUri = if (scheme != null) "$scheme://$uri" else uri
                            }
                            compDataUris.add(fullUri)
                            if (pattern.queryParameters.isNotEmpty()) {
                                perUriParams[fullUri] = pattern.queryParameters.sorted()
                            }
                            if (pattern.queryParameterValues.isNotEmpty()) {
                                perUriParamValues[fullUri] = pattern.queryParameterValues
                                    .mapValues { (_, values) -> values.sorted() }
                            }
                            if (pattern.queryParameterDefaults.isNotEmpty()) {
                                perUriParamDefaults[fullUri] = pattern.queryParameterDefaults
                            }
                        }

                        targets.add(
                            LaunchableTarget(
                                component = comp,
                                type = type,
                                actions = allActions,
                                categories = allCategories,
                                dataSchemes = allSchemes,
                                discoveredExtras = compExtras,
                                discoveredDataUris = compDataUris.distinct(),
                                queryParamsByUri = perUriParams,
                                queryParamValuesByUri = perUriParamValues,
                                queryParamDefaultsByUri = perUriParamDefaults
                            )
                        )
                    }
                }

                addTargets(manifest.activities, "Activity")
                addTargets(manifest.services, "Service")
                addTargets(manifest.receivers, "Receiver")

                _uiState.update { it.copy(targets = targets) }
            } catch (_: Exception) { }
        }
    }

    fun expandTarget(target: LaunchableTarget) {
        val extras = target.discoveredExtras.map { info ->
            ExtraEntry(
                key = info.extraKey,
                value = "",
                type = info.extraType ?: "String",
                suggestedValues = info.possibleValues
            )
        }
        _uiState.update {
            it.copy(expandedTarget = target, extras = extras, queryParams = emptyList(), dataUri = "", result = null, error = null)
        }
    }

    fun collapseTarget() {
        _uiState.update { it.copy(expandedTarget = null, extras = emptyList(), queryParams = emptyList(), dataUri = "") }
    }

    fun updateExtra(index: Int, entry: ExtraEntry) {
        _uiState.update {
            val list = it.extras.toMutableList()
            if (index < list.size) list[index] = entry
            it.copy(extras = list)
        }
    }

    fun updateDataUri(uri: String) {
        val target = _uiState.value.expandedTarget
        val queryParams = if (uri.isNotBlank() && target != null) {
            val paramNames = target.queryParamsByUri[uri] ?: emptyList()
            val paramValues = target.queryParamValuesByUri[uri] ?: emptyMap()
            val paramDefaults = target.queryParamDefaultsByUri[uri] ?: emptyMap()
            paramNames.map { key ->
                val default = paramDefaults[key]
                val isBoolean = default == "true" || default == "false"
                val suggestions = (paramValues[key] ?: emptyList()) +
                    if (isBoolean) listOf("true", "false") else emptyList()
                QueryParamEntry(
                    key = key,
                    value = "",
                    suggestedValues = suggestions.distinct(),
                    defaultValue = default
                )
            }
        } else {
            emptyList()
        }
        _uiState.update { it.copy(dataUri = uri, queryParams = queryParams) }
    }

    fun updateQueryParam(index: Int, entry: QueryParamEntry) {
        _uiState.update {
            val list = it.queryParams.toMutableList()
            if (index < list.size) list[index] = entry
            it.copy(queryParams = list)
        }
    }

    fun launchTarget(target: LaunchableTarget) {
        _uiState.update { it.copy(result = null, error = null) }

        val extras = mutableMapOf<String, IntentLauncher.TypedExtra>()
        _uiState.value.extras.filter { it.key.isNotBlank() && it.value.isNotBlank() }.forEach { entry ->
            extras[entry.key] = when (entry.type) {
                "Int" -> IntentLauncher.TypedExtra.IntExtra(entry.value.toIntOrNull() ?: 0)
                "Long" -> IntentLauncher.TypedExtra.LongExtra(entry.value.toLongOrNull() ?: 0L)
                "Boolean" -> IntentLauncher.TypedExtra.BooleanExtra(entry.value.toBoolean())
                "Float" -> IntentLauncher.TypedExtra.FloatExtra(entry.value.toFloatOrNull() ?: 0f)
                "Double" -> IntentLauncher.TypedExtra.DoubleExtra(entry.value.toDoubleOrNull() ?: 0.0)
                else -> IntentLauncher.TypedExtra.StringExtra(entry.value)
            }
        }

        // Build data URI with query parameters appended
        var dataUri = _uiState.value.dataUri.takeIf { it.isNotBlank() }
        if (dataUri != null) {
            val filledParams = _uiState.value.queryParams.filter { it.value.isNotBlank() }
            if (filledParams.isNotEmpty()) {
                val uriBuilder = Uri.parse(dataUri).buildUpon()
                filledParams.forEach { param ->
                    uriBuilder.appendQueryParameter(param.key, param.value)
                }
                dataUri = uriBuilder.build().toString()
            }
        }
        val action = target.actions.firstOrNull()
        val params = IntentLauncher.IntentParams(
            action = action,
            data = dataUri?.let { Uri.parse(it) },
            type = null,
            componentPackage = packageName,
            componentClass = target.component.name,
            categories = target.categories,
            extras = extras,
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val intent = intentLauncher.buildIntent(params)

        if (target.type == "Receiver") {
            intentLauncher.sendOrderedBroadcast(intent) { br ->
                val parts = mutableListOf<String>()
                parts.add("resultCode=${br.resultCode}")
                if (br.resultData != null) parts.add("resultData=\"${br.resultData}\"")
                if (br.resultExtras.isNotEmpty()) {
                    parts.add("extras={${br.resultExtras.entries.joinToString { "${it.key}=${it.value}" }}}")
                }
                _uiState.update { it.copy(result = "Broadcast result: ${parts.joinToString(", ")}") }
            }.onFailure { e ->
                _uiState.update { it.copy(error = e.message ?: "Broadcast failed") }
            }
            return
        }

        val result = when (target.type) {
            "Activity" -> intentLauncher.launchActivity(intent)
            "Service" -> intentLauncher.startService(intent).map { }
            else -> intentLauncher.launchActivity(intent)
        }

        result.fold(
            onSuccess = { _uiState.update { it.copy(result = "Launched successfully") } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message ?: "Launch failed") } }
        )
    }

    /** Quick launch without expanding — no extras */
    fun quickLaunch(target: LaunchableTarget) {
        _uiState.update { it.copy(expandedTarget = null, extras = emptyList(), queryParams = emptyList(), dataUri = "") }
        launchTarget(target)
    }

    class Factory(
        private val packageName: String,
        private val intentLauncher: IntentLauncher,
        private val analysisRepository: AnalysisRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return IntentBuilderViewModel(packageName, intentLauncher, analysisRepository) as T
        }
    }
}
