package com.droidprobe.app.ui.providers

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidprobe.app.DroidProbeApplication
import com.droidprobe.app.interaction.ContentProviderInteractor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentProviderScreen(
    packageName: String,
    onNavigateBack: () -> Unit,
    viewModel: ContentProviderViewModel = viewModel(
        factory = run {
            val app = LocalContext.current.applicationContext as DroidProbeApplication
            ContentProviderViewModel.Factory(
                packageName = packageName,
                interactor = ContentProviderInteractor(app.contentResolver),
                analysisRepository = app.appModule.analysisRepository
            )
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Content Providers") },
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
            if (uiState.queryableUris.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No content provider URIs discovered for this app.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Query All button
            if (uiState.queryableUris.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${uiState.queryableUris.size} discovered URIs",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = { viewModel.queryAll() }) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Query All")
                        }
                    }
                }
            }

            items(uiState.queryableUris, key = { it.uri }) { queryable ->
                QueryableUriCard(
                    queryable = queryable,
                    isExpanded = uiState.expandedUri == queryable.uri,
                    onQuery = { viewModel.queryUri(queryable) },
                    onToggleExpand = { viewModel.toggleExpand(queryable.uri) }
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
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun QueryableUriCard(
    queryable: QueryableUri,
    isExpanded: Boolean,
    onQuery: () -> Unit,
    onToggleExpand: () -> Unit
) {
    val hasResult = queryable.result != null
    val isSuccess = hasResult && queryable.result!!.error == null
    val isError = hasResult && queryable.result!!.error != null

    Card(
        onClick = if (hasResult) onToggleExpand else onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSuccess -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = queryable.uri,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceBadge(queryable.source)
                        if (queryable.matchCode != null) {
                            Text(
                                text = "code: ${queryable.matchCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    queryable.sourceClass?.let { cls ->
                        Text(
                            text = cls,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    queryable.isQuerying -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    isSuccess -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    isError -> Icon(
                        Icons.Default.Lock,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    else -> Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Query",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Result summary
            if (hasResult) {
                val result = queryable.result!!
                Spacer(modifier = Modifier.height(4.dp))
                if (result.error != null) {
                    Text(
                        text = result.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2
                    )
                } else {
                    Text(
                        text = "${result.rowCount} rows · ${result.columns.size} columns",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Expanded result table
            AnimatedVisibility(visible = isExpanded && isSuccess) {
                queryable.result?.let { result ->
                    if (result.columns.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            ResultTable(
                                columns = result.columns,
                                rows = result.rows
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(source: String) {
    val color = when (source) {
        "DEX" -> MaterialTheme.colorScheme.tertiary
        "Manifest" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = source,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun ResultTable(
    columns: List<String>,
    rows: List<List<String?>>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(8.dp)
        ) {
            Row {
                columns.forEach { col ->
                    Box(modifier = Modifier
                        .width(140.dp)
                        .padding(4.dp)) {
                        Text(
                            text = col,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider()

            rows.forEach { row ->
                Row {
                    row.forEach { cell ->
                        Box(modifier = Modifier
                            .width(140.dp)
                            .padding(4.dp)) {
                            Text(
                                text = cell ?: "null",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = if (cell == null) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
