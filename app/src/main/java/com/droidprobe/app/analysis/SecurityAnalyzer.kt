package com.droidprobe.app.analysis

import com.droidprobe.app.analysis.dex.SecurityPatternDetector
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ExportedComponent
import com.droidprobe.app.data.model.FileProviderInfo
import com.droidprobe.app.data.model.ManifestAnalysis
import com.droidprobe.app.data.model.SecurityWarning
import com.droidprobe.app.data.model.SecurityWarning.Severity
import com.droidprobe.app.data.repository.AnalysisRepository

/**
 * Generates security warnings by cross-referencing DEX-detected patterns with
 * manifest analysis. Runs after DEX pass 2 as a post-processing step.
 *
 * Two modes:
 * - Per-app: runs immediately after single-app analysis (this class)
 * - Cross-app: future extension that queries the DB for multi-app chain detection
 */
class SecurityAnalyzer {

    fun analyze(
        manifest: ManifestAnalysis,
        dex: DexAnalysis,
        dexPatterns: List<SecurityPatternDetector.DetectedPattern>
    ): List<SecurityWarning> {
        val warnings = mutableListOf<SecurityWarning>()

        // Build exported component set (smali format) for cross-referencing
        val exportedSmali = mutableSetOf<String>()
        val exportedBySmali = mutableMapOf<String, String>() // smali -> component name
        fun addExported(components: List<com.droidprobe.app.data.model.ExportedComponent>) {
            components.filter { it.isExported }.forEach { comp ->
                val smali = "L${comp.name.replace('.', '/')};"
                exportedSmali.add(smali)
                exportedBySmali[smali] = comp.name
            }
        }
        addExported(manifest.activities)
        addExported(manifest.services)
        addExported(manifest.receivers)

        // 1. Unprotected exported components
        checkUnprotectedExports(manifest, warnings)

        // 2. DEX patterns in exported components
        checkDexPatternsInExports(dexPatterns, exportedSmali, exportedBySmali, warnings)

        // 3. Broad FileProvider configurations
        checkBroadFileProvider(dex.fileProviderPaths, manifest, warnings)

        // 4. Hardcoded secrets in exported component classes
        checkSecretsInExports(dex, exportedSmali, exportedBySmali, warnings)

        return warnings.sortedWith(compareBy({ it.severity.ordinal }, { it.category }))
    }

    private fun checkUnprotectedExports(manifest: ManifestAnalysis, warnings: MutableList<SecurityWarning>) {
        fun check(components: List<com.droidprobe.app.data.model.ExportedComponent>, type: String) {
            for (comp in components) {
                if (!comp.isExported) continue
                if (comp.permission != null) continue
                // Skip launcher activities — they're expected to be exported without permission
                val isLauncher = comp.intentFilters.any { filter ->
                    filter.actions.contains("android.intent.action.MAIN") &&
                        filter.categories.contains("android.intent.category.LAUNCHER")
                }
                if (isLauncher) continue

                warnings.add(SecurityWarning(
                    severity = Severity.MEDIUM,
                    category = "UNPROTECTED_EXPORT",
                    title = "Exported $type without permission",
                    description = "${comp.name.substringAfterLast('.')} is exported with no permission guard. " +
                        "Any app can interact with this component.",
                    componentName = comp.name
                ))
            }
        }
        check(manifest.activities, "activity")
        check(manifest.services, "service")
        check(manifest.receivers, "receiver")

        // Providers: check readPermission/writePermission
        for (prov in manifest.providers) {
            if (!prov.isExported) continue
            if (prov.readPermission == null && prov.writePermission == null && prov.permission == null) {
                warnings.add(SecurityWarning(
                    severity = Severity.HIGH,
                    category = "UNPROTECTED_EXPORT",
                    title = "Exported provider without permission",
                    description = "${prov.name.substringAfterLast('.')} (${prov.authority}) is exported " +
                        "with no read/write permission. Any app can query or modify its data.",
                    componentName = prov.name
                ))
            }
        }
    }

    private fun checkDexPatternsInExports(
        patterns: List<SecurityPatternDetector.DetectedPattern>,
        exportedSmali: Set<String>,
        exportedBySmali: Map<String, String>,
        warnings: MutableList<SecurityWarning>
    ) {
        for (pattern in patterns) {
            val isInExported = pattern.sourceClass in exportedSmali
            val componentName = exportedBySmali[pattern.sourceClass]

            when (pattern.category) {
                "WEBVIEW_JS_ENABLED" -> {
                    if (isInExported) {
                        warnings.add(SecurityWarning(
                            severity = Severity.HIGH,
                            category = "WEBVIEW_JS_ENABLED",
                            title = "WebView JavaScript enabled in exported component",
                            description = "JavaScript is enabled in an exported activity's WebView. " +
                                "If the WebView URL can be influenced via intent data, this enables XSS attacks.",
                            componentName = componentName,
                            sourceClass = pattern.sourceClass,
                            evidence = pattern.detail
                        ))
                    }
                }

                "WEBVIEW_FILE_ACCESS" -> {
                    if (isInExported) {
                        warnings.add(SecurityWarning(
                            severity = Severity.HIGH,
                            category = "WEBVIEW_FILE_ACCESS",
                            title = "WebView file access enabled in exported component",
                            description = "${pattern.detail} in an exported activity. " +
                                "Combined with JavaScript, this can leak local files.",
                            componentName = componentName,
                            sourceClass = pattern.sourceClass,
                            evidence = pattern.detail
                        ))
                    }
                }

                "INTENT_REDIRECTION" -> {
                    // Intent redirection is critical regardless of where it occurs,
                    // but especially dangerous in exported components
                    val severity = if (isInExported) Severity.CRITICAL else Severity.HIGH
                    warnings.add(SecurityWarning(
                        severity = severity,
                        category = "INTENT_REDIRECTION",
                        title = "Intent redirection (confused deputy)",
                        description = "A Parcelable extra is used to launch another component. " +
                            "An attacker can craft the extra to redirect execution with this app's privileges." +
                            if (isInExported) " The containing component is exported." else "",
                        componentName = componentName,
                        sourceClass = pattern.sourceClass,
                        evidence = pattern.detail
                    ))
                }

                "PATH_TRAVERSAL" -> {
                    warnings.add(SecurityWarning(
                        severity = Severity.MEDIUM,
                        category = "PATH_TRAVERSAL",
                        title = "Potential path traversal in ContentProvider",
                        description = "Uri.getLastPathSegment() is used in a ContentProvider. " +
                            "This method decodes percent-encoding, so %2F..%2F can bypass path checks.",
                        sourceClass = pattern.sourceClass,
                        evidence = pattern.detail
                    ))
                }
            }
        }
    }

    private fun checkBroadFileProvider(
        paths: List<FileProviderInfo>,
        manifest: ManifestAnalysis,
        warnings: MutableList<SecurityWarning>
    ) {
        for (fp in paths) {
            val isBroad = fp.pathType == "root-path" ||
                (fp.path.isEmpty() || fp.path == "." || fp.path == "/")

            if (!isBroad) continue

            // Check if the FileProvider is exported or has grantUriPermissions
            val provider = manifest.providers.find { it.authority == fp.authority }
            val isAccessible = provider?.isExported == true || provider?.grantUriPermissions == true

            if (isAccessible) {
                val severity = if (fp.pathType == "root-path") Severity.HIGH else Severity.MEDIUM
                warnings.add(SecurityWarning(
                    severity = severity,
                    category = "BROAD_FILE_PROVIDER",
                    title = "Broad FileProvider path configuration",
                    description = "${fp.pathType} with path=\"${fp.path}\" exposes " +
                        if (fp.pathType == "root-path") "the entire filesystem"
                        else "the entire ${fp.pathType} directory" +
                        ". Authority: ${fp.authority}",
                    componentName = provider?.name,
                    evidence = "${fp.pathType} path=\"${fp.path}\" name=\"${fp.name}\""
                ))
            }
        }
    }

    private fun checkSecretsInExports(
        dex: DexAnalysis,
        exportedSmali: Set<String>,
        exportedBySmali: Map<String, String>,
        warnings: MutableList<SecurityWarning>
    ) {
        for (secret in dex.sensitiveStrings) {
            if (secret.sourceClass in exportedSmali) {
                warnings.add(SecurityWarning(
                    severity = Severity.INFO,
                    category = "SECRET_IN_EXPORT",
                    title = "Hardcoded ${secret.category} in exported component",
                    description = "A ${secret.category} is hardcoded in an exported component's class. " +
                        "It may be extractable by decompiling the APK.",
                    componentName = exportedBySmali[secret.sourceClass],
                    sourceClass = secret.sourceClass,
                    evidence = "${secret.value.take(8)}...${secret.value.takeLast(4)}"
                ))
            }
        }
    }

    /**
     * Cross-app chain detection. Analyzes relationships between the target app
     * and all other scanned apps to find attack chains.
     *
     * Detects:
     * - Intent redirection chains: App A → this app (has getParcelableExtra→startActivity) → App C
     * - Provider access chains: Other apps query this app's unprotected providers
     * - Shared secrets: Same API key/token found across multiple apps
     * - Permission escalation: Unpermissioned apps can reach permissioned functionality
     *   through this app's exported components
     */
    fun analyzeCrossApp(
        manifest: ManifestAnalysis,
        dex: DexAnalysis,
        otherApps: List<AnalysisRepository.CrossAppData>
    ): List<SecurityWarning> {
        if (otherApps.isEmpty()) return emptyList()
        val warnings = mutableListOf<SecurityWarning>()

        checkIntentRedirectionChains(manifest, dex, otherApps, warnings)
        checkProviderAccessChains(manifest, dex, otherApps, warnings)
        checkSharedSecrets(dex, otherApps, warnings)
        checkPermissionEscalation(manifest, dex, otherApps, warnings)

        return warnings.sortedWith(compareBy({ it.severity.ordinal }, { it.category }))
    }

    /**
     * If this app has intent redirection (getParcelableExtra → startActivity),
     * find other apps that send intents to the redirecting component.
     * Chain: OtherApp → ThisApp(redirect) → arbitrary target
     */
    private fun checkIntentRedirectionChains(
        manifest: ManifestAnalysis,
        dex: DexAnalysis,
        otherApps: List<AnalysisRepository.CrossAppData>,
        warnings: MutableList<SecurityWarning>
    ) {
        // Find this app's components that have intent redirection warnings
        val redirectComponents = dex.securityWarnings
            .filter { it.category == "INTENT_REDIRECTION" && it.componentName != null }
            .map { it.componentName!! }
            .toSet()
        if (redirectComponents.isEmpty()) return

        // Collect the actions of redirecting components
        val redirectActions = mutableSetOf<String>()
        val allComponents = manifest.activities + manifest.services + manifest.receivers
        for (comp in allComponents) {
            if (comp.name in redirectComponents) {
                redirectActions.addAll(comp.intentFilters.flatMap { it.actions })
            }
        }

        // Find other apps that send intents targeting these components/actions
        for (other in otherApps) {
            for (extra in other.dex.intentExtras) {
                val targetsRedirect = extra.associatedComponent in redirectComponents ||
                    extra.associatedAction in redirectActions
                if (!targetsRedirect) continue

                warnings.add(SecurityWarning(
                    severity = Severity.CRITICAL,
                    category = "CHAIN_INTENT_REDIRECT",
                    title = "Intent redirection chain: ${other.appName}",
                    description = "${other.appName} (${other.packageName}) sends intents to " +
                        "${extra.associatedComponent?.substringAfterLast('.') ?: extra.associatedAction}, " +
                        "which has intent redirection. An attacker controlling ${other.appName}'s intent " +
                        "can redirect execution through this app with its privileges.",
                    componentName = extra.associatedComponent ?: redirectComponents.first(),
                    evidence = "Chain: ${other.packageName} → ${manifest.packageName} → arbitrary"
                ))
                break // One warning per sender app
            }
        }
    }

    /**
     * Find other apps that query this app's unprotected content providers.
     */
    private fun checkProviderAccessChains(
        manifest: ManifestAnalysis,
        dex: DexAnalysis,
        otherApps: List<AnalysisRepository.CrossAppData>,
        warnings: MutableList<SecurityWarning>
    ) {
        // This app's provider authorities
        val authorities = manifest.providers
            .filter { it.isExported }
            .mapNotNull { it.authority }
            .toSet()
        if (authorities.isEmpty()) return

        for (other in otherApps) {
            // Check if other app's code references our provider authorities
            val callers = other.dex.contentProviderCalls.filter { it.authority in authorities }
            val uriRefs = other.dex.rawContentUriStrings.filter { uri ->
                authorities.any { auth -> uri.contains(auth) }
            }

            if (callers.isNotEmpty() || uriRefs.isNotEmpty()) {
                val refCount = callers.size + uriRefs.size
                warnings.add(SecurityWarning(
                    severity = Severity.INFO,
                    category = "CROSS_APP_PROVIDER_ACCESS",
                    title = "${other.appName} accesses this app's providers",
                    description = "${other.appName} (${other.packageName}) references " +
                        "$refCount provider URI${if (refCount != 1) "s" else ""} belonging to this app. " +
                        "Verify that exposed data is intentionally shared.",
                    evidence = "Authorities: ${authorities.joinToString()}"
                ))
            }
        }
    }

    /**
     * Find identical sensitive strings (API keys, tokens) appearing in both
     * this app and other apps — may indicate shared credentials.
     */
    private fun checkSharedSecrets(
        dex: DexAnalysis,
        otherApps: List<AnalysisRepository.CrossAppData>,
        warnings: MutableList<SecurityWarning>
    ) {
        if (dex.sensitiveStrings.isEmpty()) return
        val ourSecrets = dex.sensitiveStrings.map { it.value }.toSet()

        for (other in otherApps) {
            val shared = other.dex.sensitiveStrings.filter { it.value in ourSecrets }
            if (shared.isEmpty()) continue

            val categories = shared.map { it.category }.distinct()
            warnings.add(SecurityWarning(
                severity = Severity.MEDIUM,
                category = "SHARED_SECRET",
                title = "Shared credentials with ${other.appName}",
                description = "${shared.size} secret${if (shared.size != 1) "s" else ""} " +
                    "(${categories.joinToString()}) are identical in both apps. " +
                    "If one app is compromised, shared keys may grant access to the other app's resources.",
                evidence = shared.joinToString { "${it.value.take(8)}..." }
            ))
        }
    }

    /**
     * Permission escalation: if this app has exported components with dangerous permissions
     * that can be reached by unpermissioned apps through unprotected exported components.
     *
     * Pattern: OtherApp (no permission) → ThisApp's unprotected export → ThisApp's permissioned action
     */
    private fun checkPermissionEscalation(
        manifest: ManifestAnalysis,
        dex: DexAnalysis,
        otherApps: List<AnalysisRepository.CrossAppData>,
        warnings: MutableList<SecurityWarning>
    ) {
        // Find this app's exported components without permission
        val unprotected = mutableSetOf<String>()
        fun collectUnprotected(components: List<ExportedComponent>) {
            for (comp in components) {
                if (comp.isExported && comp.permission == null) {
                    unprotected.add(comp.name)
                }
            }
        }
        collectUnprotected(manifest.activities)
        collectUnprotected(manifest.services)
        collectUnprotected(manifest.receivers)

        if (unprotected.isEmpty()) return

        // Check if any putExtra in this app's code targets its own permissioned components
        // (indicating internal IPC that could be hijacked)
        val permissionedComponents = mutableMapOf<String, String>() // name -> permission
        fun collectPermissioned(components: List<ExportedComponent>) {
            for (comp in components) {
                if (comp.permission != null) {
                    permissionedComponents[comp.name] = comp.permission
                }
            }
        }
        collectPermissioned(manifest.activities)
        collectPermissioned(manifest.services)
        collectPermissioned(manifest.receivers)

        if (permissionedComponents.isEmpty()) return

        // Look for intent extras that bridge unprotected → permissioned
        for (extra in dex.intentExtras) {
            val comp = extra.associatedComponent ?: continue
            if (comp in permissionedComponents) {
                // Check if the sourceClass is reachable from an unprotected component
                val sourceJava = extra.sourceClass
                    .removePrefix("L").removeSuffix(";").replace('/', '.')
                if (sourceJava in unprotected) {
                    warnings.add(SecurityWarning(
                        severity = Severity.HIGH,
                        category = "PERMISSION_ESCALATION",
                        title = "Permission escalation path",
                        description = "Unprotected component ${sourceJava.substringAfterLast('.')} " +
                            "sends intents to permission-guarded ${comp.substringAfterLast('.')} " +
                            "(requires ${permissionedComponents[comp]}). " +
                            "External apps may exploit this to bypass the permission check.",
                        componentName = sourceJava,
                        evidence = "$sourceJava → $comp (${permissionedComponents[comp]})"
                    ))
                }
            }
        }
    }
}
