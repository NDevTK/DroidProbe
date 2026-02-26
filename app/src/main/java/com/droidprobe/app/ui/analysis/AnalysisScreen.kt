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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.droidprobe.app.data.model.ContentProviderCallInfo
import com.droidprobe.app.data.model.ContentProviderInfo
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ExportedComponent
import com.droidprobe.app.data.model.FileProviderInfo
import com.droidprobe.app.data.model.IntentInfo
import com.droidprobe.app.data.model.ProviderComponent
import com.droidprobe.app.ui.components.PermissionBadge
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
                            label = "Intent Builder",
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

                    // Activities
                    SectionHeader(
                        title = "Activities",
                        count = manifest.activities.size,
                        initiallyExpanded = true
                    ) {
                        ComponentList(components = manifest.activities)
                    }

                    // Services
                    SectionHeader(
                        title = "Services",
                        count = manifest.services.size
                    ) {
                        ComponentList(components = manifest.services)
                    }

                    // Receivers
                    SectionHeader(
                        title = "Broadcast Receivers",
                        count = manifest.receivers.size
                    ) {
                        ComponentList(components = manifest.receivers)
                    }

                    // Content Providers
                    SectionHeader(
                        title = "Content Providers",
                        count = manifest.providers.size,
                        initiallyExpanded = true
                    ) {
                        ProviderList(providers = manifest.providers)
                    }

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

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    // DEX Analysis section (auto-triggered)
                    DexAnalysisSection(
                        dexAnalysis = uiState.dexAnalysis,
                        isDexAnalyzing = uiState.isDexAnalyzing,
                        dexProgress = uiState.dexProgress,
                        onStartDexAnalysis = { }
                    )

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
private fun ComponentList(components: List<ExportedComponent>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        components.forEach { component ->
            ComponentCard(component = component)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ComponentCard(component: ExportedComponent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val isBrowsable = component.intentFilters.any { filter ->
                filter.categories.any { it == "android.intent.category.BROWSABLE" }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = component.name.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isBrowsable) {
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
                    isExported = component.isExported,
                    permission = component.permission
                )
            }

            Text(
                text = component.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (component.permission != null) {
                Text(
                    text = "Permission: ${component.permission}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            component.intentFilters.forEach { filter ->
                if (filter.actions.isNotEmpty()) {
                    Text(
                        text = "Actions: ${filter.actions.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (filter.categories.isNotEmpty()) {
                    Text(
                        text = "Categories: ${filter.categories.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (filter.dataSchemes.isNotEmpty()) {
                    Text(
                        text = "Schemes: ${filter.dataSchemes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderList(providers: List<ProviderComponent>) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        providers.forEach { provider ->
            ProviderCard(provider = provider)
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ProviderCard(provider: ProviderComponent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = provider.name.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                PermissionBadge(
                    isExported = provider.isExported,
                    permission = provider.permission ?: provider.readPermission
                )
            }

            provider.authority?.let { authority ->
                Text(
                    text = "Authority: $authority",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Text(
                text = provider.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (provider.readPermission != null) {
                Text(
                    text = "Read: ${provider.readPermission}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (provider.writePermission != null) {
                Text(
                    text = "Write: ${provider.writePermission}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (provider.grantUriPermissions) {
                Text(
                    text = "grantUriPermissions: true",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            provider.pathPermissions.forEach { pp ->
                Text(
                    text = "Path: ${pp.path} (R: ${pp.readPermission ?: "none"}, W: ${pp.writePermission ?: "none"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DexAnalysisSection(
    dexAnalysis: DexAnalysis?,
    isDexAnalyzing: Boolean,
    dexProgress: String,
    onStartDexAnalysis: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "Bytecode Analysis",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        if (isDexAnalyzing) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (dexProgress.isNotEmpty()) {
                Text(
                    text = dexProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (dexAnalysis != null) {
            Spacer(modifier = Modifier.height(8.dp))

            // Split URIs into content:// (queryable) and deep links
            val contentUris = dexAnalysis.contentProviderUris.filter {
                it.uriPattern.startsWith("content://")
            }
            val deepLinkUris = dexAnalysis.contentProviderUris.filter {
                !it.uriPattern.startsWith("content://")
            }

            // Discovered content URIs
            if (contentUris.isNotEmpty()) {
                SectionHeader(
                    title = "Content Provider URIs",
                    count = contentUris.size,
                    initiallyExpanded = true
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        contentUris.forEach { uri ->
                            UriCard(uri)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Deep link URI patterns (from UriMatcher / Uri.parse with non-content schemes)
            if (deepLinkUris.isNotEmpty()) {
                SectionHeader(
                    title = "Deep Link URIs",
                    count = deepLinkUris.size,
                    initiallyExpanded = true
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        deepLinkUris.forEach { uri ->
                            UriCard(uri)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Discovered intent extras
            if (dexAnalysis.intentExtras.isNotEmpty()) {
                SectionHeader(
                    title = "Intent Extras",
                    count = dexAnalysis.intentExtras.size,
                    initiallyExpanded = true
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        dexAnalysis.intentExtras.forEach { extra ->
                            IntentExtraCard(extra)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Discovered file provider paths
            if (dexAnalysis.fileProviderPaths.isNotEmpty()) {
                SectionHeader(
                    title = "FileProvider Paths",
                    count = dexAnalysis.fileProviderPaths.size,
                    initiallyExpanded = true
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        dexAnalysis.fileProviderPaths.forEach { path ->
                            FileProviderPathCard(path)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Provider call() methods
            if (dexAnalysis.contentProviderCalls.isNotEmpty()) {
                SectionHeader(
                    title = "Provider Call Methods",
                    count = dexAnalysis.contentProviderCalls.size,
                    initiallyExpanded = true
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        dexAnalysis.contentProviderCalls.forEach { callInfo ->
                            ProviderCallCard(callInfo)
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // Raw content URI strings
            if (dexAnalysis.rawContentUriStrings.isNotEmpty()) {
                SectionHeader(
                    title = "Raw Content URIs (strings)",
                    count = dexAnalysis.rawContentUriStrings.size
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        dexAnalysis.rawContentUriStrings.forEach { uri ->
                            Text(
                                text = uri,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Deep link URI string constants
            if (dexAnalysis.deepLinkUriStrings.isNotEmpty()) {
                SectionHeader(
                    title = "Deep Link Strings",
                    count = dexAnalysis.deepLinkUriStrings.size
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        dexAnalysis.deepLinkUriStrings.forEach { uri ->
                            Text(
                                text = uri,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (dexAnalysis.contentProviderUris.isEmpty() &&
                dexAnalysis.intentExtras.isEmpty() &&
                dexAnalysis.fileProviderPaths.isEmpty() &&
                dexAnalysis.contentProviderCalls.isEmpty() &&
                dexAnalysis.rawContentUriStrings.isEmpty() &&
                dexAnalysis.deepLinkUriStrings.isEmpty()
            ) {
                Text(
                    text = "No IPC patterns found in bytecode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun UriCard(info: ContentProviderInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = info.uriPattern,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (info.matchCode != null) {
                Text(
                    text = "Match code: ${info.matchCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info.queryParameters.isNotEmpty()) {
                info.queryParameters.forEach { param ->
                    val values = info.queryParameterValues[param]
                    val display = if (values != null && values.isNotEmpty()) {
                        "$param = [${values.joinToString(", ")}]"
                    } else {
                        param
                    }
                    Text(
                        text = "?$display",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Text(
                text = "Source: ${info.sourceClass.removePrefix("L").removeSuffix(";").replace('/', '.')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IntentExtraCard(info: IntentInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.extraKey,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (info.possibleValues.isNotEmpty()) {
                    Text(
                        text = "Values: ${info.possibleValues.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    text = "Source: ${info.sourceClass.removePrefix("L").removeSuffix(";").replace('/', '.')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            info.extraType?.let { type ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = type,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCallCard(info: ContentProviderCallInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = info.methodName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (info.authority != null) {
                Text(
                    text = "Authority: ${info.authority}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Source: ${info.sourceClass.removePrefix("L").removeSuffix(";").replace('/', '.')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileProviderPathCard(info: FileProviderInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "${info.pathType}: ${info.path}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Authority: ${info.authority}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Name: ${info.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
