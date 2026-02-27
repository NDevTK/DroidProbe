package com.droidprobe.app.ui.intents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidprobe.app.DroidProbeApplication
import com.droidprobe.app.interaction.IntentLauncher
import com.droidprobe.app.ui.components.PermissionBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentBuilderScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: IntentBuilderViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as DroidProbeApplication
            IntentBuilderViewModel.Factory(
                packageName = packageName,
                intentLauncher = IntentLauncher(app),
                analysisRepository = app.appModule.analysisRepository
            )
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Intent Launcher") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.targets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No exported components found for this app.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Group by type, sort within each group by risk: BROWSABLE > EXPOSED > PROTECTED
            val grouped = uiState.targets.groupBy { it.type }
            grouped.forEach { (type, targets) ->
                val sorted = targets.sortedWith(compareBy { target ->
                    when {
                        target.categories.any { it == "android.intent.category.BROWSABLE" } -> 0
                        target.component.permission == null -> 1
                        else -> 2
                    }
                })

                item {
                    Text(
                        text = when (type) {
                            "Activity" -> "Activities"
                            "Service" -> "Services"
                            "Receiver" -> "Broadcast Receivers"
                            else -> type
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                items(sorted, key = { it.component.name }) { target ->
                    val isExpanded = uiState.expandedTarget?.component?.name == target.component.name
                    TargetCard(
                        target = target,
                        packageName = uiState.packageName,
                        isExpanded = isExpanded,
                        extras = if (isExpanded) uiState.extras else emptyList(),
                        queryParams = if (isExpanded) uiState.queryParams else emptyList(),
                        dataUri = if (isExpanded) uiState.dataUri else "",
                        onToggleExpand = {
                            if (isExpanded) viewModel.collapseTarget()
                            else viewModel.expandTarget(target)
                        },
                        onQuickLaunch = { viewModel.quickLaunch(target) },
                        onLaunchWithExtras = { viewModel.launchTarget(target) },
                        onExtraChanged = { index, entry -> viewModel.updateExtra(index, entry) },
                        onQueryParamChanged = { index, entry -> viewModel.updateQueryParam(index, entry) },
                        onDataUriChanged = { viewModel.updateDataUri(it) }
                    )
                }
            }

            // Result / Error
            if (uiState.result != null) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = uiState.result!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (uiState.error != null) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = uiState.error!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(
    target: LaunchableTarget,
    packageName: String,
    isExpanded: Boolean,
    extras: List<ExtraEntry>,
    queryParams: List<QueryParamEntry>,
    dataUri: String,
    onToggleExpand: () -> Unit,
    onQuickLaunch: () -> Unit,
    onLaunchWithExtras: () -> Unit,
    onExtraChanged: (Int, ExtraEntry) -> Unit,
    onQueryParamChanged: (Int, QueryParamEntry) -> Unit,
    onDataUriChanged: (String) -> Unit
) {
    val typeIcon: ImageVector = when (target.type) {
        "Activity" -> Icons.AutoMirrored.Filled.OpenInNew
        "Service" -> Icons.Default.Settings
        "Receiver" -> Icons.Default.Notifications
        else -> Icons.AutoMirrored.Filled.OpenInNew
    }

    val hasExpandableContent = target.discoveredExtras.isNotEmpty() || target.discoveredDataUris.isNotEmpty() || target.queryParamsByUri.isNotEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    typeIcon,
                    contentDescription = target.type,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.component.name.substringAfterLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = target.component.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (target.categories.any { it == "android.intent.category.BROWSABLE" }) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "BROWSABLE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                PermissionBadge(
                    isExported = target.component.isExported,
                    permission = target.component.permission
                )
            }

            // Actions and categories
            if (target.actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    target.actions.forEach { action ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = action.substringAfterLast('.'),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Summary counts
            val summaryParts = mutableListOf<String>()
            if (target.discoveredExtras.isNotEmpty()) {
                summaryParts.add("${target.discoveredExtras.size} extras")
            }
            if (target.discoveredDataUris.isNotEmpty()) {
                summaryParts.add("${target.discoveredDataUris.size} data URIs")
            }
            val totalQueryParams = target.queryParamsByUri.values.sumOf { it.size }
            if (totalQueryParams > 0) {
                summaryParts.add("$totalQueryParams query params")
            }
            if (summaryParts.isNotEmpty()) {
                Text(
                    text = summaryParts.joinToString(" + "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Action buttons
            val isBrowsable = target.categories.any { it == "android.intent.category.BROWSABLE" }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onQuickLaunch,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch")
                }

                if (hasExpandableContent) {
                    FilledTonalButton(
                        onClick = onToggleExpand
                    ) {
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Details"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Details")
                    }
                }

                if (isBrowsable) {
                    val context = LocalContext.current
                    val shareLink = buildShareableLink(target, packageName, dataUri, queryParams)
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Deep Link", shareLink))
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy Link")
                    }
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareLink)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Link"))
                    }) {
                        Icon(Icons.Default.Share, "Share Link")
                    }
                }
            }

            // Expandable details editor
            AnimatedVisibility(visible = isExpanded && hasExpandableContent) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Deep link URI chips — tap to fill the data URI field
                    if (target.discoveredDataUris.isNotEmpty()) {
                        Text(
                            "Data URI (tap to select):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            target.discoveredDataUris.forEach { uri ->
                                FilterChip(
                                    selected = dataUri == uri,
                                    onClick = {
                                        onDataUriChanged(if (dataUri == uri) "" else uri)
                                    },
                                    label = {
                                        // Show just the path portion for readability
                                        val display = uri.substringAfter("://")
                                            .substringAfter('/')
                                            .ifEmpty { uri }
                                        Text(
                                            text = display,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = null,
                                            modifier = Modifier.height(16.dp)
                                        )
                                    }
                                )
                            }
                        }

                        // Show the full selected URI
                        if (dataUri.isNotBlank()) {
                            Text(
                                text = dataUri,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    // Query parameters editor (shown when a data URI is selected)
                    if (queryParams.isNotEmpty() && dataUri.isNotBlank()) {
                        Text(
                            "Query Parameters:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        queryParams.forEachIndexed { index, param ->
                            val label = when {
                                param.defaultValue != null -> "${param.key} (default: ${param.defaultValue})"
                                else -> param.key
                            }
                            SuggestableTextField(
                                value = param.value,
                                onValueChange = { onQueryParamChanged(index, param.copy(value = it)) },
                                label = label,
                                suggestedValues = param.suggestedValues
                            )
                        }
                    }

                    // Extras editor
                    if (extras.isNotEmpty()) {
                        Text(
                            "Extras:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        extras.forEachIndexed { index, entry ->
                            Column {
                                Text(
                                    text = entry.key,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                SuggestableTextField(
                                    value = entry.value,
                                    onValueChange = { onExtraChanged(index, entry.copy(value = it)) },
                                    label = entry.type,
                                    suggestedValues = entry.suggestedValues,
                                    keyboardType = when (entry.type) {
                                        "Int", "Long", "Short", "Byte" -> KeyboardType.Number
                                        "Float", "Double" -> KeyboardType.Decimal
                                        else -> KeyboardType.Text
                                    }
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onLaunchWithExtras,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (dataUri.isNotBlank()) "Launch with Data URI" else "Launch with Extras")
                    }
                }
            }
        }
    }
}

private fun buildShareableLink(
    target: LaunchableTarget,
    packageName: String,
    selectedDataUri: String,
    queryParams: List<QueryParamEntry>
): String {
    // 1. If a data URI is actively selected (expanded card), use it with query params
    if (selectedDataUri.isNotBlank()) {
        return buildFullUri(selectedDataUri, queryParams)
    }

    // 2. If discovered data URIs have a custom scheme, use the first one
    val customSchemeUri = target.discoveredDataUris.firstOrNull { uri ->
        val scheme = uri.substringBefore("://", "")
        scheme.isNotEmpty() && scheme !in setOf("content", "http", "https")
    }
    if (customSchemeUri != null) return customSchemeUri

    // 3. Try to build a custom-scheme URI from manifest intent filter data
    val browsableFilter = target.component.intentFilters.firstOrNull { filter ->
        filter.categories.contains("android.intent.category.BROWSABLE")
    }
    if (browsableFilter != null) {
        val scheme = browsableFilter.dataSchemes.firstOrNull { it !in setOf("http", "https") }
        if (scheme != null) {
            val authority = browsableFilter.dataAuthorities.firstOrNull() ?: ""
            val path = browsableFilter.dataPaths.firstOrNull() ?: ""
            return "$scheme://$authority$path"
        }
    }

    // 4. Fallback: intent:// URI
    return buildIntentUri(target, packageName)
}

/**
 * Build an Android intent:// URI for sharing browsable intents that don't have
 * a custom scheme. Format: intent://HOST/PATH#Intent;scheme=SCHEME;action=ACTION;
 * category=CATEGORY;package=PACKAGE;end
 */
private fun buildIntentUri(target: LaunchableTarget, packageName: String): String {
    val browsableFilter = target.component.intentFilters.firstOrNull { filter ->
        filter.categories.contains("android.intent.category.BROWSABLE")
    }

    val sb = StringBuilder("intent://")

    // Add host/path from manifest data
    val authority = browsableFilter?.dataAuthorities?.firstOrNull()
    val path = browsableFilter?.dataPaths?.firstOrNull()
    if (authority != null) sb.append(authority)
    if (path != null) sb.append(path)

    sb.append("#Intent")

    // Scheme
    val scheme = browsableFilter?.dataSchemes?.firstOrNull()
        ?: target.dataSchemes.firstOrNull()
    if (scheme != null) sb.append(";scheme=$scheme")

    // Action
    val action = browsableFilter?.actions?.firstOrNull()
        ?: target.actions.firstOrNull()
    if (action != null) sb.append(";action=$action")

    // Categories
    target.categories.forEach { sb.append(";category=$it") }

    // Package
    sb.append(";package=$packageName")

    sb.append(";end")
    return sb.toString()
}

private fun buildFullUri(dataUri: String, queryParams: List<QueryParamEntry>): String {
    val builder = Uri.parse(dataUri).buildUpon()
    queryParams.filter { it.value.isNotBlank() }.forEach { param ->
        builder.appendQueryParameter(param.key, param.value)
    }
    return builder.build().toString()
}

/**
 * Text field that shows a dropdown of suggested values when available,
 * while still allowing freeform text input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestedValues: List<String>,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null
) {
    val placeholderContent: @Composable (() -> Unit)? = placeholder?.let {
        { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
    }

    if (suggestedValues.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholderContent,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
    } else {
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    expanded = true
                },
                label = { Text(label) },
                placeholder = placeholderContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )

            // Filter suggestions based on current text
            val filtered = if (value.isBlank()) suggestedValues
            else suggestedValues.filter { it.contains(value, ignoreCase = true) }

            if (filtered.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    filtered.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                onValueChange(suggestion)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
