package com.droidprobe.app.analysis.manifest

import android.content.pm.ActivityInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.os.Build
import com.droidprobe.app.data.model.ExportedComponent
import com.droidprobe.app.data.model.IntentFilterInfo
import com.droidprobe.app.data.model.ManifestAnalysis
import com.droidprobe.app.data.model.PathPermissionInfo
import com.droidprobe.app.data.model.ProviderComponent

class ManifestAnalyzer(private val packageManager: PackageManager) {

    fun analyze(packageName: String): ManifestAnalysis {
        val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA

        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }

        return ManifestAnalysis(
            packageName = packageName,
            activities = extractComponents(packageInfo.activities),
            services = extractComponents(packageInfo.services),
            receivers = extractComponents(packageInfo.receivers),
            providers = extractProviders(packageInfo.providers),
            customPermissions = packageInfo.permissions?.map { it.name } ?: emptyList()
        )
    }

    private fun extractComponents(components: Array<out ComponentInfo>?): List<ExportedComponent> {
        if (components == null) return emptyList()
        return components.map { component ->
            ExportedComponent(
                name = component.name,
                isExported = component.exported,
                permission = when (component) {
                    is ActivityInfo -> component.permission
                    is ServiceInfo -> component.permission
                    else -> null
                },
                intentFilters = extractIntentFilters(component)
            )
        }
    }

    private fun extractIntentFilters(component: ComponentInfo): List<IntentFilterInfo> {
        // PackageManager doesn't directly expose intent filters in a structured way
        // for arbitrary packages. We use queryIntentActivities for activities.
        // For Phase 1, we extract what we can from the component metadata.
        // Full intent filter extraction requires manifest XML parsing (Phase 2 enhancement).
        if (component is ActivityInfo) {
            return extractActivityIntentFilters(component)
        }
        return emptyList()
    }

    private fun extractActivityIntentFilters(activityInfo: ActivityInfo): List<IntentFilterInfo> {
        // Try to discover intent filters by querying the package manager
        // This is limited but works for main intent filters
        return try {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(
                    activityInfo.packageName,
                    activityInfo.name
                )
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
            }

            resolveInfos.mapNotNull { resolveInfo ->
                resolveInfo.filter?.let { filter ->
                    IntentFilterInfo(
                        actions = (0 until filter.countActions()).map { filter.getAction(it) },
                        categories = (0 until filter.countCategories()).map { filter.getCategory(it) },
                        dataSchemes = (0 until filter.countDataSchemes()).map { filter.getDataScheme(it) },
                        dataAuthorities = (0 until filter.countDataAuthorities()).map {
                            val auth = filter.getDataAuthority(it)
                            "${auth.host}:${auth.port}"
                        },
                        dataPaths = (0 until filter.countDataPaths()).map {
                            filter.getDataPath(it).path
                        },
                        mimeTypes = (0 until filter.countDataTypes()).map { filter.getDataType(it) }
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractProviders(providers: Array<out ProviderInfo>?): List<ProviderComponent> {
        if (providers == null) return emptyList()
        return providers.map { provider ->
            ProviderComponent(
                name = provider.name,
                authority = provider.authority,
                isExported = provider.exported,
                permission = provider.readPermission ?: provider.writePermission,
                readPermission = provider.readPermission,
                writePermission = provider.writePermission,
                grantUriPermissions = provider.grantUriPermissions,
                pathPermissions = provider.pathPermissions?.map { pp ->
                    PathPermissionInfo(
                        path = pp.path ?: pp.readPermission ?: "unknown",
                        type = when {
                            pp.path != null -> "literal"
                            else -> "pattern"
                        },
                        readPermission = pp.readPermission,
                        writePermission = pp.writePermission
                    )
                } ?: emptyList()
            )
        }
    }
}
