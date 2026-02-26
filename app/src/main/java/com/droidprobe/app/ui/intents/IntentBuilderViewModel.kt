package com.droidprobe.app.ui.intents

import android.content.Intent
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
    val discoveredExtras: List<IntentInfo>
)

data class ExtraEntry(
    val key: String = "",
    val value: String = "",
    val type: String = "String"
)

data class IntentBuilderUiState(
    val packageName: String = "",
    val targets: List<LaunchableTarget> = emptyList(),
    // Expanded target for editing extras before launch
    val expandedTarget: LaunchableTarget? = null,
    val extras: List<ExtraEntry> = emptyList(),
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

                val targets = mutableListOf<LaunchableTarget>()

                fun addTargets(components: List<ExportedComponent>, type: String) {
                    components.filter { it.isExported }.forEach { comp ->
                        val allActions = comp.intentFilters.flatMap { it.actions }
                        val allCategories = comp.intentFilters.flatMap { it.categories }
                        val allSchemes = comp.intentFilters.flatMap { it.dataSchemes }

                        // Match by associatedComponent — resolved from inheritance chain
                        val compExtras = discoveredExtras
                            .filter { it.associatedComponent == comp.name }
                            .distinctBy { it.extraKey }

                        targets.add(
                            LaunchableTarget(
                                component = comp,
                                type = type,
                                actions = allActions,
                                categories = allCategories,
                                dataSchemes = allSchemes,
                                discoveredExtras = compExtras
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
            ExtraEntry(key = info.extraKey, value = "", type = info.extraType ?: "String")
        }
        _uiState.update {
            it.copy(expandedTarget = target, extras = extras, result = null, error = null)
        }
    }

    fun collapseTarget() {
        _uiState.update { it.copy(expandedTarget = null, extras = emptyList()) }
    }

    fun updateExtra(index: Int, entry: ExtraEntry) {
        _uiState.update {
            val list = it.extras.toMutableList()
            if (index < list.size) list[index] = entry
            it.copy(extras = list)
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

        val action = target.actions.firstOrNull()
        val params = IntentLauncher.IntentParams(
            action = action,
            data = null,
            type = null,
            componentPackage = packageName,
            componentClass = target.component.name,
            categories = target.categories,
            extras = extras,
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val intent = intentLauncher.buildIntent(params)

        val result = when (target.type) {
            "Activity" -> intentLauncher.launchActivity(intent)
            "Service" -> intentLauncher.startService(intent).map { }
            "Receiver" -> intentLauncher.sendBroadcast(intent)
            else -> intentLauncher.launchActivity(intent)
        }

        result.fold(
            onSuccess = { _uiState.update { it.copy(result = "Launched successfully") } },
            onFailure = { e -> _uiState.update { it.copy(error = e.message ?: "Launch failed") } }
        )
    }

    /** Quick launch without expanding — no extras */
    fun quickLaunch(target: LaunchableTarget) {
        _uiState.update { it.copy(expandedTarget = null, extras = emptyList()) }
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
