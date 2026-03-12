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
    val discoveredMimeTypes: Map<String, String> = emptyMap(), // uri -> mimeType from setDataAndType
    val discoveredCategories: List<String> = emptyList() // categories found in bytecode beyond manifest
)

data class ExtraEntry(
    val key: String = "",
    val value: String = "",
    val type: String = "String",
    val suggestedValues: List<String> = emptyList(),
    val associatedAction: String? = null
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
    val dataUriPage: Int = 0,
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

    // Cached indexes for lazy param combination building
    private var cachedPatternsByClass = emptyMap<String, List<com.droidprobe.app.data.model.ContentProviderInfo>>()
    private var cachedPatternsByAuthority = emptyMap<String, List<com.droidprobe.app.data.model.ContentProviderInfo>>()
    private var cachedSchemeByAuthority = emptyMap<String, String>()
    private var cachedProducerUrls = emptyList<String>()

    init {
        loadComponents()
    }

    private fun loadComponents() {
        viewModelScope.launch {
            try {
                val manifest = analysisRepository.analyzeManifest(packageName)
                val dex = analysisRepository.getCachedDex(packageName)
                val discoveredExtras = dex?.intentExtras ?: emptyList()

                // Single pass: build scheme lookup + pattern indexes directly from contentProviderUris
                // (avoids creating a separate 20k-element deepLinkPatterns list)
                val schemeByAuthority = mutableMapOf<String, String>()
                val patternsByClass = mutableMapOf<String, MutableList<com.droidprobe.app.data.model.ContentProviderInfo>>()
                val patternsByAuthority = mutableMapOf<String, MutableList<com.droidprobe.app.data.model.ContentProviderInfo>>()
                dex?.deepLinkUriStrings?.forEach { uriStr ->
                    val schemeEnd = uriStr.indexOf("://")
                    if (schemeEnd > 0) {
                        val scheme = uriStr.substring(0, schemeEnd)
                        val authority = uriStr.substring(schemeEnd + 3).substringBefore('/')
                        if (authority.isNotEmpty()) schemeByAuthority[authority] = scheme
                    }
                }
                dex?.contentProviderUris?.forEach { p ->
                    val uri = p.uriPattern
                    val schemeEnd = uri.indexOf("://")
                    if (schemeEnd > 0) {
                        val scheme = uri.substring(0, schemeEnd)
                        val authority = uri.substring(schemeEnd + 3).substringBefore('/')
                        if (scheme == "content") return@forEach
                        if (authority.isNotEmpty()) schemeByAuthority[authority] = scheme
                        patternsByClass.getOrPut(p.sourceClass) { mutableListOf() }.add(p)
                        patternsByAuthority.getOrPut(authority) { mutableListOf() }.add(p)
                    } else {
                        if (uri.startsWith("content://")) return@forEach
                        patternsByClass.getOrPut(p.sourceClass) { mutableListOf() }.add(p)
                        val auth = uri.substringBefore('/')
                        if (auth.isNotEmpty()) {
                            patternsByAuthority.getOrPut(auth) { mutableListOf() }.add(p)
                        }
                    }
                }

                // Cache indexes for lazy param resolution
                cachedPatternsByClass = patternsByClass
                cachedPatternsByAuthority = patternsByAuthority
                cachedSchemeByAuthority = schemeByAuthority
                cachedProducerUrls = (dex?.allUrlStrings ?: emptyList()) +
                    (dex?.deepLinkUriStrings ?: emptyList())

                // Collect Intent.setData()/setDataAndType() URIs
                val intentDataUris = dex?.discoveredDataUris ?: emptySet()
                val intentMimeTypes = dex?.discoveredDataMimeTypes ?: emptyMap()
                val dexCategories = dex?.discoveredCategories ?: emptySet()

                val extrasByComponent = discoveredExtras.groupBy { it.associatedComponent }

                val targets = mutableListOf<LaunchableTarget>()

                fun addTargets(components: List<ExportedComponent>, type: String) {
                    components.filter { it.isExported }.forEach { comp ->
                        val allActions = comp.intentFilters.flatMap { it.actions }
                        val allCategories = comp.intentFilters.flatMap { it.categories }
                        val allSchemes = comp.intentFilters.flatMap { it.dataSchemes }

                        // O(1) extras lookup instead of filtering all extras
                        val compExtras = buildList {
                            extrasByComponent[comp.name]?.let { addAll(it) }
                            comp.targetActivity?.let { ta ->
                                extrasByComponent[ta]?.let { addAll(it) }
                            }
                        }.distinctBy { it.extraKey }

                        val compSmali = "L${comp.name.replace('.', '/')};"
                        val targetSmali = comp.targetActivity?.let { "L${it.replace('.', '/')};" }

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

                        val filterDeepLinkUris = mutableSetOf<String>()
                        for (filter in comp.intentFilters) {
                            for (scheme in filter.dataSchemes) {
                                for (host in filter.dataAuthorities) {
                                    if (filter.dataPaths.isNotEmpty()) {
                                        for (path in filter.dataPaths) {
                                            filterDeepLinkUris.add("$scheme://$host$path")
                                        }
                                    } else {
                                        filterDeepLinkUris.add("$scheme://$host")
                                    }
                                }
                            }
                        }

                        val compDataUris = mutableListOf<String>()
                        val seenUris = HashSet<String>()

                        // Collect unique URIs only — params resolved lazily on URI selection
                        val seenPatterns = HashSet<String>()
                        fun collectPattern(p: com.droidprobe.app.data.model.ContentProviderInfo) {
                            if (!seenPatterns.add("${p.sourceClass}|${p.uriPattern}")) return

                            val fullUri: String
                            val uri = p.uriPattern
                            if (uri.contains("://")) {
                                fullUri = uri
                            } else {
                                val authority = uri.substringBefore('/')
                                val scheme = schemeByAuthority[authority]
                                    ?: compSchemeByAuthority[authority]
                                    ?: compFallbackScheme
                                fullUri = if (scheme != null) "$scheme://$uri" else uri
                            }
                            if (seenUris.add(fullUri)) compDataUris.add(fullUri)
                        }

                        // Class-based matching via index
                        patternsByClass[compSmali]?.forEach(::collectPattern)
                        targetSmali?.let { patternsByClass[it]?.forEach(::collectPattern) }

                        // URI-based matching via authority index
                        for (filterUri in filterDeepLinkUris) {
                            val schemeEnd = filterUri.indexOf("://")
                            if (schemeEnd < 0) continue
                            val auth = filterUri.substring(schemeEnd + 3).substringBefore('/')
                            val bucket = patternsByAuthority[auth] ?: continue
                            for (pattern in bucket) {
                                if (pattern.uriPattern == filterUri ||
                                    pattern.uriPattern.startsWith("$filterUri?") ||
                                    pattern.uriPattern.startsWith("$filterUri/")) {
                                    collectPattern(pattern)
                                }
                            }
                        }

                        // Producer URL scanning — collect base URIs only (params resolved lazily)
                        if (filterDeepLinkUris.isNotEmpty()) {
                            for (urlStr in cachedProducerUrls) {
                                val urlNoFragment = urlStr.substringBefore('#')
                                val queryIdx = urlNoFragment.indexOf('?')
                                if (queryIdx < 0) continue

                                val baseUrl = urlNoFragment.substring(0, queryIdx)
                                val matchesFilter = filterDeepLinkUris.any { filterUri ->
                                    baseUrl == filterUri || baseUrl.startsWith("$filterUri/")
                                }
                                if (!matchesFilter) continue

                                if (seenUris.add(baseUrl)) compDataUris.add(baseUrl)
                            }
                        }

                        // Add Intent.setData()/setDataAndType() URIs matching this component's schemes
                        val compAllSchemes = (allSchemes + filterDeepLinkUris.mapNotNull { uri ->
                            val idx = uri.indexOf("://")
                            if (idx > 0) uri.substring(0, idx) else null
                        }).toSet()
                        for (dataUri in intentDataUris) {
                            val idx = dataUri.indexOf("://")
                            val uriScheme = if (idx > 0) dataUri.substring(0, idx) else continue
                            if (uriScheme in compAllSchemes || compAllSchemes.isEmpty()) {
                                if (seenUris.add(dataUri)) compDataUris.add(dataUri)
                            }
                        }

                        // Collect MIME types for discovered data URIs
                        val compMimeTypes = mutableMapOf<String, String>()
                        for (uri in compDataUris) {
                            intentMimeTypes[uri]?.let { compMimeTypes[uri] = it }
                        }

                        // Categories from bytecode not already in manifest
                        val manifestCatSet = allCategories.toSet()
                        val extraCategories = dexCategories.filter { it !in manifestCatSet }

                        targets.add(
                            LaunchableTarget(
                                component = comp,
                                type = type,
                                actions = allActions,
                                categories = allCategories,
                                dataSchemes = allSchemes,
                                discoveredExtras = compExtras,
                                discoveredDataUris = compDataUris,
                                discoveredMimeTypes = compMimeTypes,
                                discoveredCategories = extraCategories
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
                suggestedValues = info.possibleValues,
                associatedAction = info.associatedAction
            )
        }
        _uiState.update {
            it.copy(expandedTarget = target, extras = extras, queryParams = emptyList(), dataUri = "", result = null, error = null)
        }
    }

    fun setDataUriPage(page: Int) {
        _uiState.update { it.copy(dataUriPage = page) }
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
        val queryParams = if (uri.isNotBlank()) resolveQueryParams(uri) else emptyList()
        _uiState.update { it.copy(dataUri = uri, queryParams = queryParams) }
    }

    /** Lazily merge all param sources for a single URI into one flat param list. */
    private fun resolveQueryParams(uri: String): List<QueryParamEntry> {
        val params = mutableMapOf<String, QueryParamEntry>()

        fun mergeParam(key: String, values: List<String> = emptyList(), default: String? = null) {
            val existing = params[key]
            if (existing == null) {
                params[key] = QueryParamEntry(
                    key = key,
                    suggestedValues = values,
                    defaultValue = default
                )
            } else {
                params[key] = existing.copy(
                    suggestedValues = (existing.suggestedValues + values).distinct(),
                    defaultValue = existing.defaultValue ?: default
                )
            }
        }

        fun mergeFromPattern(p: com.droidprobe.app.data.model.ContentProviderInfo) {
            for (key in p.queryParameters) {
                mergeParam(key, p.queryParameterValues[key] ?: emptyList(), p.queryParameterDefaults[key])
            }
        }

        // Compare by authority+path, ignoring scheme differences
        // (collectPattern resolves schemes using per-component maps we don't cache)
        val uriWithoutScheme = if (uri.contains("://")) uri.substringAfter("://") else uri

        fun matchesUri(p: com.droidprobe.app.data.model.ContentProviderInfo): Boolean {
            val patternPath = if (p.uriPattern.contains("://")) p.uriPattern.substringAfter("://")
            else p.uriPattern
            // Compare base path only (strip query string from pattern)
            return patternPath.substringBefore('?') == uriWithoutScheme
        }

        // From class-based patterns (matches how loadComponents collects URIs)
        val target = _uiState.value.expandedTarget
        if (target != null) {
            val compSmali = "L${target.component.name.replace('.', '/')};"
            cachedPatternsByClass[compSmali]?.forEach { p ->
                if (matchesUri(p)) mergeFromPattern(p)
            }
            target.component.targetActivity?.let { ta ->
                val targetSmali = "L${ta.replace('.', '/')};"
                cachedPatternsByClass[targetSmali]?.forEach { p ->
                    if (matchesUri(p)) mergeFromPattern(p)
                }
            }
        }

        // From authority-based patterns
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd > 0) {
            val auth = uri.substring(schemeEnd + 3).substringBefore('/')
            cachedPatternsByAuthority[auth]?.forEach { p ->
                if (matchesUri(p)) mergeFromPattern(p)
            }
        }

        // From producer URLs matching this URI
        for (urlStr in cachedProducerUrls) {
            val urlNoFragment = urlStr.substringBefore('#')
            val queryIdx = urlNoFragment.indexOf('?')
            if (queryIdx < 0) continue
            if (urlNoFragment.substring(0, queryIdx) != uri) continue

            val queryString = urlNoFragment.substring(queryIdx + 1)
            if (queryString.isBlank()) continue

            for (pair in queryString.split('&')) {
                val eqIdx = pair.indexOf('=')
                if (eqIdx > 0) {
                    val key = pair.substring(0, eqIdx)
                    val value = pair.substring(eqIdx + 1)
                    mergeParam(key, if (value.isNotEmpty()) listOf(value) else emptyList())
                } else if (pair.isNotBlank()) {
                    mergeParam(pair)
                }
            }
        }

        // Add boolean suggestions where applicable
        return params.values.map { entry ->
            val default = entry.defaultValue
            val isBoolean = default == "true" || default == "false"
            if (isBoolean) entry.copy(
                suggestedValues = (entry.suggestedValues + listOf("true", "false")).distinct()
            ) else entry
        }
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
