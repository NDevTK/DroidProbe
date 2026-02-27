package com.droidprobe.app.ui.fileprovider

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.droidprobe.app.interaction.FileProviderAccessor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileProviderScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: FileProviderViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as DroidProbeApplication
            FileProviderViewModel.Factory(
                packageName = packageName,
                accessor = FileProviderAccessor(app.contentResolver),
                analysisRepository = app.appModule.analysisRepository
            )
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FileProvider Browser") },
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
            if (uiState.paths.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No FileProvider paths discovered for this app.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Probe All button
            if (uiState.paths.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.paths.size} discovered paths",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = { viewModel.probeAll() }) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Probe All")
                        }
                    }
                }
            }

            items(uiState.paths, key = { it.key }) { path ->
                FilePathCard(
                    path = path,
                    onProbe = { viewModel.probePath(path) }
                )
            }

            // Error
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
private fun FilePathCard(
    path: ProbablePath,
    onProbe: () -> Unit
) {
    val hasResult = path.result != null
    val isAccessible = path.result?.isAccessible == true

    Card(
        onClick = onProbe,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isAccessible -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                hasResult -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${path.info.pathType}: ${path.info.path}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = path.uri,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    path.isProbing -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    isAccessible -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Accessible",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    hasResult -> Icon(
                        Icons.Default.Lock,
                        contentDescription = "Not accessible",
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Icon(
                        Icons.Default.Search,
                        contentDescription = "Tap to probe",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Authority info
            Text(
                text = "Authority: ${path.info.authority}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            // Probe result details
            AnimatedVisibility(visible = hasResult) {
                path.result?.let { result ->
                    ProbeResultDetails(result)
                }
            }
        }
    }
}

@Composable
private fun ProbeResultDetails(result: FileProviderAccessor.FileInfo) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (result.isAccessible) {
            result.name?.let {
                Text("Name: $it", style = MaterialTheme.typography.bodySmall)
            }
            result.size?.let {
                Text("Size: $it bytes", style = MaterialTheme.typography.bodySmall)
            }
            result.mimeType?.let {
                Text("MIME: $it", style = MaterialTheme.typography.bodySmall)
            }
            result.previewText?.let { preview ->
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = preview.take(500),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 10
                    )
                }
            }
        }

        result.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
