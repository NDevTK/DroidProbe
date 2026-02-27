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

    fun analyze(packageName: String, apkPath: String? = null): ManifestAnalysis {
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

        val base = ManifestAnalysis(
            packageName = packageName,
            activities = extractComponents(packageInfo.activities),
            services = extractComponents(packageInfo.services),
            receivers = extractComponents(packageInfo.receivers),
            providers = extractProviders(packageInfo.providers),
            customPermissions = packageInfo.permissions?.map { it.name } ?: emptyList()
        )

        // Enrich with binary manifest XML for complete intent filters
        if (apkPath != null) {
            try {
                val parseResult = BinaryManifestParser().parseFromApk(apkPath)
                if (parseResult.filters.isNotEmpty() || parseResult.aliasTargets.isNotEmpty()) {
                    return enrichWithBinaryFilters(base, parseResult)
                }
            } catch (_: Exception) {
                // Fall back to PM-only result
            }
        }

        return base
    }

    private fun enrichWithBinaryFilters(
        base: ManifestAnalysis,
        parseResult: BinaryManifestParser.ParseResult
    ): ManifestAnalysis {
        val binaryFilters = parseResult.filters
        val aliasTargets = parseResult.aliasTargets

        fun enrichComponents(components: List<ExportedComponent>): List<ExportedComponent> {
            return components.map { component ->
                var enriched = component
                // Set targetActivity from binary manifest for activity-aliases
                val target = aliasTargets[component.name]
                if (target != null && enriched.targetActivity == null) {
                    enriched = enriched.copy(targetActivity = target)
                }
                val rawFilters = binaryFilters[component.name]
                if (rawFilters != null && rawFilters.isNotEmpty()) {
                    enriched = enriched.copy(intentFilters = rawFilters.map { raw ->
                        IntentFilterInfo(
                            actions = raw.actions,
                            categories = raw.categories,
                            dataSchemes = raw.dataSchemes,
                            dataAuthorities = raw.dataHosts.mapIndexed { i, host ->
                                val port = raw.dataPorts.getOrNull(i)
                                if (port != null) "$host:$port" else host
                            },
                            dataPaths = raw.dataPaths,
                            mimeTypes = raw.dataMimeTypes
                        )
                    })
                }
                enriched
            }
        }

        return base.copy(
            activities = enrichComponents(base.activities),
            services = enrichComponents(base.services),
            receivers = enrichComponents(base.receivers)
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
                intentFilters = extractIntentFilters(component),
                targetActivity = (component as? ActivityInfo)?.targetActivity
            )
        }
    }

    private fun extractIntentFilters(component: ComponentInfo): List<IntentFilterInfo> {
        return when (component) {
            is ActivityInfo -> queryFilters(component.packageName, component.name, ComponentType.ACTIVITY)
            is ServiceInfo -> queryFilters(component.packageName, component.name, ComponentType.SERVICE)
            else -> queryFilters(component.packageName, component.name, ComponentType.RECEIVER)
        }
    }

    private enum class ComponentType { ACTIVITY, SERVICE, RECEIVER }

    private fun queryFilters(packageName: String, className: String, type: ComponentType): List<IntentFilterInfo> {
        return try {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(packageName, className)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
                when (type) {
                    ComponentType.ACTIVITY -> packageManager.queryIntentActivities(intent, flags)
                    ComponentType.SERVICE -> packageManager.queryIntentServices(intent, flags)
                    ComponentType.RECEIVER -> packageManager.queryBroadcastReceivers(intent, flags)
                }
            } else {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_RESOLVED_FILTER
                when (type) {
                    ComponentType.ACTIVITY -> packageManager.queryIntentActivities(intent, flags)
                    ComponentType.SERVICE -> packageManager.queryIntentServices(intent, flags)
                    ComponentType.RECEIVER -> packageManager.queryBroadcastReceivers(intent, flags)
                }
            }

            resolveInfos.mapNotNull { resolveInfo ->
                resolveInfo.filter?.let { filter ->
                    IntentFilterInfo(
                        actions = (0 until filter.countActions()).map { filter.getAction(it) },
                        categories = (0 until filter.countCategories()).map { filter.getCategory(it) },
                        dataSchemes = (0 until filter.countDataSchemes()).map { filter.getDataScheme(it) },
                        dataAuthorities = (0 until filter.countDataAuthorities()).map {
                            val auth = filter.getDataAuthority(it)
                            if (auth.port == -1) auth.host else "${auth.host}:${auth.port}"
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
