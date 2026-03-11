package com.droidprobe.app.ui.analysis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
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
import com.droidprobe.app.data.model.ManifestAnalysis
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
    onNavigateToGoogleApi: (String, String) -> Unit = { _, _ -> },
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
                            enabled = !uiState.isDexAnalyzing &&
                                    (manifest.activities.any { it.isExported } ||
                                            manifest.services.any { it.isExported } ||
                                            manifest.receivers.any { it.isExported }),
                            onClick = { onNavigateToIntentBuilder(packageName) }
                        )
                        ActionButton(
                            icon = Icons.Default.Folder,
                            label = "File Providers",
                            enabled = uiState.dexAnalysis?.fileProviderPaths?.isNotEmpty() == true,
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
                                    CustomPermissionRow(perm, manifest)
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

                        if (dex.apiEndpoints.isNotEmpty()) {
                            val grouped = dex.apiEndpoints.groupBy { it.baseUrl.ifEmpty { "Unknown" } }
                            val sortedGroups = grouped.entries.sortedWith(
                                compareBy<Map.Entry<String, List<com.droidprobe.app.data.model.ApiEndpoint>>> {
                                    classifyDomain(it.key)?.sortOrder ?: 0
                                }.thenBy { it.key }
                            )

                            SectionHeader(
                                title = "API Endpoints",
                                count = dex.apiEndpoints.size
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    sortedGroups.forEach { (host, endpoints) ->
                                        val isExplorable = isExplorableApi(host)
                                        EndpointGroupHeader(
                                            host = host,
                                            count = endpoints.size,
                                            onExplore = if (isExplorable) {
                                                { onNavigateToGoogleApi(packageName, host) }
                                            } else null
                                        )
                                        endpoints.sortedBy { it.path }.forEach { endpoint ->
                                            EndpointRow(endpoint)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
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

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun smaliToReadable(smali: String): String {
    // Convert "Lcom/foo/Bar;" → "com.foo.Bar"
    return smali.removePrefix("L").removeSuffix(";").replace('/', '.')
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
private fun CustomPermissionRow(perm: String, manifest: ManifestAnalysis) {
    val context = LocalContext.current

    // Find components using this permission
    val users = buildList {
        manifest.activities.filter { it.permission == perm }.forEach { add("Activity" to it.name) }
        manifest.services.filter { it.permission == perm }.forEach { add("Service" to it.name) }
        manifest.receivers.filter { it.permission == perm }.forEach { add("Receiver" to it.name) }
        manifest.providers.filter { it.permission == perm || it.readPermission == perm || it.writePermission == perm }
            .forEach { add("Provider" to it.name) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { copyToClipboard(context, "Permission", perm) }
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = perm,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (users.isNotEmpty()) {
            users.forEach { (type, name) ->
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = type,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = name.substringAfterLast('.'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SensitiveStringRow(secret: SensitiveString) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { copyToClipboard(context, "Secret", secret.value) }
            .padding(vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        if (secret.sourceClass.isNotEmpty()) {
            Text(
                text = smaliToReadable(secret.sourceClass),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 1.dp)
            )
        }
        secret.associatedUrls.forEach { url ->
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, top = 1.dp)
            )
        }
    }
}

private data class DomainClassification(
    val label: String,
    val sortOrder: Int // higher = less interesting, sorted last
)

private fun classifyDomain(baseUrl: String): DomainClassification? {
    val lower = baseUrl.lowercase()
    return when {
        lower.contains("firebase") || lower.contains("crashlytics") ||
            lower.contains("mixpanel") || lower.contains("amplitude") ||
            lower.contains("analytics") || lower.contains("flurry") ||
            lower.contains("appsflyer") -> DomainClassification("Analytics", 3)
        lower.contains("doubleclick") || lower.contains("admob") ||
            lower.contains("applovin") || lower.contains("mopub") ||
            lower.contains("unity3d") || lower.contains("adcolony") ->
            DomainClassification("Ads", 3)
        lower.contains("cloudfront") || lower.contains("cloudflare") ||
            lower.contains("akamai") || lower.contains("fastly") ||
            lower.contains("cdn") -> DomainClassification("CDN", 2)
        lower.contains("sentry") || lower.contains("bugsnag") ||
            lower.contains("datadog") -> DomainClassification("Monitoring", 2)
        else -> null
    }
}

@Composable
private fun EndpointGroupHeader(
    host: String,
    count: Int,
    onExplore: (() -> Unit)? = null
) {
    val classification = classifyDomain(host)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = host.removePrefix("https://").removePrefix("http://"),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        if (onExplore != null) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
                onClick = onExplore
            ) {
                Text(
                    text = "Explore",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = count.toString(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (classification != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = classification.label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

private fun isExplorableApi(baseUrl: String): Boolean {
    val host = baseUrl.removePrefix("https://").removePrefix("http://")
        .substringBefore('/').substringBefore(':').lowercase()
    // Skip known non-API hosts (CDNs, analytics, ad networks)
    if (host.isEmpty()) return false
    val skipPatterns = listOf(
        "cloudfront.net", "cloudflare.com", "akamai", "fastly.net",
        "firebaseinstallations", "firebaselogging", "crashlytics",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "app-measurement.com", "google-analytics.com"
    )
    if (skipPatterns.any { host.contains(it) }) return false
    return true
}

@Composable
private fun EndpointRow(endpoint: com.droidprobe.app.data.model.ApiEndpoint) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { copyToClipboard(context, "URL", endpoint.fullUrl) }
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (endpoint.httpMethod != null) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = endpoint.httpMethod,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (endpoint.sourceType != "literal") {
            Surface(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = endpoint.sourceType,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = endpoint.path.ifEmpty { endpoint.fullUrl },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (endpoint.fullUrl.startsWith("http://") || endpoint.fullUrl.startsWith("https://")) {
            IconButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endpoint.fullUrl)))
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open in browser",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun UrlRow(url: String) {
    val context = LocalContext.current
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
            .clickable { copyToClipboard(context, "URL", url) }
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
        if (url.startsWith("http://") || url.startsWith("https://")) {
            IconButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open in browser",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
