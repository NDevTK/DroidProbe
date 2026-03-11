package com.droidprobe.app.ui.googleapi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidprobe.app.DroidProbeApplication
import com.droidprobe.app.data.model.DiscoveryMethod
import com.droidprobe.app.data.model.DiscoveryResource
import com.droidprobe.app.data.model.KeyStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleApiExplorerScreen(
    packageName: String,
    rootUrl: String,
    onNavigateBack: () -> Unit,
    viewModel: GoogleApiExplorerViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as DroidProbeApplication
            GoogleApiExplorerViewModel.Factory(
                packageName = packageName,
                rootUrl = rootUrl,
                fetcher = app.appModule.apiSpecFetcher,
                analysisRepository = app.appModule.analysisRepository
            )
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.discovery?.title ?: rootUrl.removePrefix("https://").removeSuffix(":443"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Fetching API spec...")
                    }
                }
            }
            uiState.error != null && uiState.discovery == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(onClick = { viewModel.retry() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
                val discovery = uiState.discovery ?: return@Scaffold
                val flatItems = remember(discovery) { flattenResources(discovery.resources) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    // API info header
                    item {
                        if (discovery.description.isNotEmpty()) {
                            Text(
                                text = discovery.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // API key selector with validation
                    if (uiState.apiKeys.isNotEmpty()) {
                        item {
                            ApiKeySelector(
                                keys = uiState.apiKeys,
                                selectedKey = uiState.selectedApiKey,
                                keyValidation = uiState.keyValidation,
                                onSelectKey = viewModel::selectApiKey
                            )
                        }
                    } else {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "No API keys found in this app",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // Auto-execute result
                    uiState.autoExecuteResult?.let { (method, result) ->
                        item {
                            AutoExecuteResultCard(method = method, result = result, context = context)
                        }
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    // Resource tree
                    items(flatItems, key = { it.key }) { item ->
                        when (item) {
                            is FlatItem.ResourceHeader -> {
                                ResourceHeaderRow(
                                    name = item.name,
                                    depth = item.depth,
                                    methodCount = item.methodCount
                                )
                            }
                            is FlatItem.Method -> {
                                val isSelected = uiState.selectedMethod?.id == item.method.id
                                MethodRow(
                                    method = item.method,
                                    depth = item.depth,
                                    isExpanded = isSelected,
                                    onClick = {
                                        viewModel.selectMethod(if (isSelected) null else item.method)
                                    }
                                )
                                if (isSelected) {
                                    MethodDetail(
                                        method = item.method,
                                        paramValues = uiState.paramValues,
                                        requestBody = uiState.requestBody,
                                        onParamChange = viewModel::updateParam,
                                        onBodyChange = viewModel::updateRequestBody,
                                        executionResult = uiState.executionResult,
                                        isExecuting = uiState.isExecuting,
                                        onExecute = viewModel::executeMethod,
                                        context = context
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// --- Flattened resource tree items ---

private sealed class FlatItem(val key: String) {
    class ResourceHeader(
        val name: String,
        val depth: Int,
        val methodCount: Int,
        key: String
    ) : FlatItem(key)

    class Method(
        val method: DiscoveryMethod,
        val depth: Int,
        key: String
    ) : FlatItem(key)
}

private fun flattenResources(
    resources: Map<String, DiscoveryResource>,
    depth: Int = 0,
    prefix: String = ""
): List<FlatItem> {
    val items = mutableListOf<FlatItem>()
    for ((name, resource) in resources.entries.sortedBy { it.key }) {
        val fullName = if (prefix.isEmpty()) name else "$prefix.$name"
        val totalMethods = countMethods(resource)
        items.add(FlatItem.ResourceHeader(name, depth, totalMethods, "res:$fullName"))
        for ((_, method) in resource.methods.entries.sortedBy { it.key }) {
            items.add(FlatItem.Method(method, depth + 1, "method:${method.id}"))
        }
        items.addAll(flattenResources(resource.resources, depth + 1, fullName))
    }
    return items
}

private fun countMethods(resource: DiscoveryResource): Int {
    return resource.methods.size + resource.resources.values.sumOf { countMethods(it) }
}

// --- Composables ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeySelector(
    keys: List<String>,
    selectedKey: String?,
    keyValidation: Map<String, KeyStatus>,
    onSelectKey: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedStatus = selectedKey?.let { keyValidation[it] }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "API Key",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedKey?.let { maskKey(it) } ?: "None",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                leadingIcon = selectedStatus?.let { status ->
                    { KeyStatusIcon(status) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                keys.forEach { key ->
                    val status = keyValidation[key]
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (status != null) {
                                    KeyStatusIcon(status)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(maskKey(key), fontFamily = FontFamily.Monospace)
                            }
                        },
                        onClick = {
                            onSelectKey(key)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Validation status summary
        val testing = keyValidation.count { it.value == KeyStatus.TESTING }
        val valid = keyValidation.count { it.value == KeyStatus.VALID }
        val invalid = keyValidation.count { it.value == KeyStatus.INVALID }
        if (keyValidation.isNotEmpty()) {
            Text(
                text = when {
                    testing > 0 -> "Validating keys..."
                    valid > 0 -> "$valid key${if (valid > 1) "s" else ""} validated"
                    invalid > 0 -> "No working keys found"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    testing > 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    valid > 0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun KeyStatusIcon(status: KeyStatus) {
    when (status) {
        KeyStatus.TESTING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        KeyStatus.VALID -> Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                "OK",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        KeyStatus.INVALID -> Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                "FAIL",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        KeyStatus.UNTESTED -> {}
    }
}

@Composable
private fun AutoExecuteResultCard(
    method: DiscoveryMethod,
    result: com.droidprobe.app.data.model.ExecutionResult,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.statusCode in 200..299)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Auto-test: ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SourceBadge(source = method.source)
                Spacer(modifier = Modifier.width(4.dp))
                HttpMethodBadge(method.httpMethod)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    method.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            ResponseViewer(result = result, context = context)
        }
    }
}

private fun maskKey(key: String): String {
    if (key.length <= 12) return key
    return key.take(8) + "..." + key.takeLast(4)
}

@Composable
private fun SourceBadge(source: String) {
    if (source.isEmpty()) return
    val (label, color) = when (source) {
        "dex" -> "DEX" to MaterialTheme.colorScheme.tertiary
        "spec" -> "Spec" to MaterialTheme.colorScheme.primary
        "both" -> "Both" to MaterialTheme.colorScheme.secondary
        else -> return
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun HttpMethodBadge(httpMethod: String) {
    val methodColor = when (httpMethod) {
        "GET" -> MaterialTheme.colorScheme.primary
        "POST" -> MaterialTheme.colorScheme.tertiary
        "PUT" -> MaterialTheme.colorScheme.secondary
        "DELETE" -> MaterialTheme.colorScheme.error
        "PATCH" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        color = methodColor.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = httpMethod,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = methodColor
        )
    }
}

@Composable
private fun ResourceHeaderRow(name: String, depth: Int, methodCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "$methodCount",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MethodRow(
    method: DiscoveryMethod,
    depth: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (method.source.isNotEmpty()) {
            SourceBadge(source = method.source)
            Spacer(modifier = Modifier.width(4.dp))
        }
        HttpMethodBadge(method.httpMethod)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = method.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MethodDetail(
    method: DiscoveryMethod,
    paramValues: Map<String, String>,
    requestBody: String,
    onParamChange: (String, String) -> Unit,
    onBodyChange: (String) -> Unit,
    executionResult: com.droidprobe.app.data.model.ExecutionResult?,
    isExecuting: Boolean,
    onExecute: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Description
            if (method.description.isNotEmpty()) {
                Text(
                    text = method.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Scopes warning
            if (method.scopes.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                "Requires OAuth scopes (API key may not suffice)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            method.scopes.forEach { scope ->
                                Text(
                                    text = scope.substringAfterLast('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Parameters
            val sortedParams = method.parameters.values.sortedWith(
                compareByDescending<com.droidprobe.app.data.model.DiscoveryParameter> { it.required }
                    .thenBy { if (it.location == "path") 0 else 1 }
                    .thenBy { it.name }
            )

            for (param in sortedParams) {
                val value = paramValues[param.name] ?: ""
                OutlinedTextField(
                    value = value,
                    onValueChange = { onParamChange(param.name, it) },
                    label = {
                        Text(buildString {
                            append(param.name)
                            if (param.required) append(" *")
                            append(" (${param.location})")
                        })
                    },
                    supportingText = if (param.description.isNotEmpty()) {
                        {
                            Text(
                                param.description,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }

            // Request body for POST/PUT/PATCH
            if (method.httpMethod in listOf("POST", "PUT", "PATCH")) {
                OutlinedTextField(
                    value = requestBody,
                    onValueChange = onBodyChange,
                    label = { Text("Request Body (JSON)") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Execute button
            FilledTonalButton(
                onClick = onExecute,
                enabled = !isExecuting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Executing...")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Execute")
                }
            }

            // Response
            if (executionResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ResponseViewer(result = executionResult, context = context)
            }
        }
    }
}

@Composable
private fun ResponseViewer(result: com.droidprobe.app.data.model.ExecutionResult, context: Context) {
    val statusColor = when {
        result.statusCode in 200..299 -> MaterialTheme.colorScheme.primary
        result.statusCode in 400..499 -> MaterialTheme.colorScheme.error
        result.statusCode >= 500 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (result.statusCode == -1) "ERROR" else "${result.statusCode}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Response", result.body))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy response",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        val displayBody = try {
            if (result.body.trimStart().startsWith("{") || result.body.trimStart().startsWith("[")) {
                org.json.JSONObject(result.body).toString(2)
            } else {
                result.body
            }
        } catch (_: Exception) {
            try {
                org.json.JSONArray(result.body).toString(2)
            } catch (_: Exception) {
                result.body
            }
        }

        SelectionContainer {
            Text(
                text = if (displayBody.length > 5000) displayBody.take(5000) + "\n... (truncated)" else displayBody,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
