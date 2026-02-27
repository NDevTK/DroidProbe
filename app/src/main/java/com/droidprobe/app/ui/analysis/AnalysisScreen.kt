package com.droidprobe.app.ui.analysis

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidprobe.app.DroidProbeApplication
import com.droidprobe.app.data.model.SensitiveString
import com.droidprobe.app.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    onNavigateToContentProvider: (String) -> Unit,
    onNavigateToIntentBuilder: (String) -> Unit,
    onNavigateToFileProvider: (String) -> Unit,
    viewModel: AnalysisViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as DroidProbeApplication
            AnalysisViewModel.Factory(
                packageName = packageName,
                analysisRepository = app.appModule.analysisRepository,
                packageManager = app.packageManager
            )
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.appName.ifEmpty { packageName },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Analyzing manifest...")
                    }
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else -> {
                val manifest = uiState.manifestAnalysis ?: return@Scaffold

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Quick action buttons — vertical stacked icons with labels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ActionButton(
                            icon = Icons.Default.Storage,
                            label = "Content Providers",
                            enabled = manifest.providers.any { it.isExported },
                            onClick = { onNavigateToContentProvider(packageName) }
                        )
                        ActionButton(
                            icon = Icons.AutoMirrored.Filled.Send,
                            label = if (uiState.isDexAnalyzing) "Analyzing..." else "Intent Builder",
                            enabled = !uiState.isDexAnalyzing,
                            onClick = { onNavigateToIntentBuilder(packageName) }
                        )
                        ActionButton(
                            icon = Icons.Default.Folder,
                            label = "File Providers",
                            onClick = { onNavigateToFileProvider(packageName) }
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    // Custom Permissions
                    if (manifest.customPermissions.isNotEmpty()) {
                        SectionHeader(
                            title = "Custom Permissions",
                            count = manifest.customPermissions.size
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                manifest.customPermissions.forEach { perm ->
                                    Text(
                                        text = perm,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.isDexAnalyzing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                        if (uiState.dexProgress.isNotEmpty()) {
                            Text(
                                text = uiState.dexProgress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                            )
                        }
                    }

                    uiState.dexAnalysis?.let { dex ->
                        if (dex.sensitiveStrings.isNotEmpty()) {
                            SectionHeader(
                                title = "Secrets & Sensitive Strings",
                                count = dex.sensitiveStrings.size
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    dex.sensitiveStrings.forEach { secret ->
                                        SensitiveStringRow(secret)
                                    }
                                }
                            }
                        }

                        if (dex.allUrlStrings.isNotEmpty()) {
                            SectionHeader(
                                title = "URLs",
                                count = dex.allUrlStrings.size
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    dex.allUrlStrings.forEach { url ->
                                        UrlRow(url)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(4.dp)
    ) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.height(48.dp).width(48.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun SensitiveStringRow(secret: SensitiveString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = secret.category,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = secret.value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun UrlRow(url: String) {
    val chipLabel: String
    val chipColor: androidx.compose.ui.graphics.Color
    when {
        url.startsWith("https://") -> { chipLabel = "https"; chipColor = MaterialTheme.colorScheme.secondary }
        url.startsWith("http://") -> { chipLabel = "http"; chipColor = MaterialTheme.colorScheme.secondary }
        url.startsWith("file://") -> { chipLabel = "file"; chipColor = MaterialTheme.colorScheme.error }
        else -> { chipLabel = "url"; chipColor = MaterialTheme.colorScheme.tertiary }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = chipColor.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = chipLabel,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = chipColor
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
