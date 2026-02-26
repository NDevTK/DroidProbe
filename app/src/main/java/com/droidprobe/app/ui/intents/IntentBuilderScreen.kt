package com.droidprobe.app.ui.intents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

            // Group by type
            val grouped = uiState.targets.groupBy { it.type }
            grouped.forEach { (type, targets) ->
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

                items(targets, key = { it.component.name }) { target ->
                    val isExpanded = uiState.expandedTarget?.component?.name == target.component.name
                    TargetCard(
                        target = target,
                        isExpanded = isExpanded,
                        extras = if (isExpanded) uiState.extras else emptyList(),
                        onToggleExpand = {
                            if (isExpanded) viewModel.collapseTarget()
                            else viewModel.expandTarget(target)
                        },
                        onQuickLaunch = { viewModel.quickLaunch(target) },
                        onLaunchWithExtras = { viewModel.launchTarget(target) },
                        onExtraChanged = { index, entry -> viewModel.updateExtra(index, entry) }
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

@Composable
private fun TargetCard(
    target: LaunchableTarget,
    isExpanded: Boolean,
    extras: List<ExtraEntry>,
    onToggleExpand: () -> Unit,
    onQuickLaunch: () -> Unit,
    onLaunchWithExtras: () -> Unit,
    onExtraChanged: (Int, ExtraEntry) -> Unit
) {
    val typeIcon: ImageVector = when (target.type) {
        "Activity" -> Icons.AutoMirrored.Filled.OpenInNew
        "Service" -> Icons.Default.Settings
        "Receiver" -> Icons.Default.Notifications
        else -> Icons.AutoMirrored.Filled.OpenInNew
    }

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
                PermissionBadge(
                    isExported = target.component.isExported,
                    permission = target.component.permission
                )
            }

            // Actions and categories
            if (target.actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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

            if (target.discoveredExtras.isNotEmpty()) {
                Text(
                    text = "${target.discoveredExtras.size} discovered extras",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onQuickLaunch,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch")
                }

                if (target.discoveredExtras.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onToggleExpand
                    ) {
                        Icon(
                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            "Extras"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Extras")
                    }
                }
            }

            // Expandable extras editor
            AnimatedVisibility(visible = isExpanded && extras.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Fill in extras before launching:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    extras.forEachIndexed { index, entry ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Key (read-only, pre-filled from DEX)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = entry.key,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = entry.type,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }

                            // Value (editable with type-appropriate keyboard)
                            OutlinedTextField(
                                value = entry.value,
                                onValueChange = { onExtraChanged(index, entry.copy(value = it)) },
                                label = { Text(entry.type) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = when (entry.type) {
                                        "Int", "Long", "Short", "Byte" -> KeyboardType.Number
                                        "Float", "Double" -> KeyboardType.Decimal
                                        "Boolean" -> KeyboardType.Text
                                        else -> KeyboardType.Text
                                    }
                                )
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = onLaunchWithExtras,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Launch with Extras")
                    }
                }
            }
        }
    }
}
