package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.droidprobe.app.data.model.ContentProviderInfo
import com.droidprobe.app.data.model.DexAnalysis
import com.droidprobe.app.data.model.ManifestAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

class DexAnalyzer {

    data class ProgressUpdate(
        val currentDex: Int,
        val totalDex: Int,
        val currentClass: Int,
        val totalClasses: Int,
        val message: String
    )

    suspend fun analyze(
        apkPath: String,
        manifestAnalysis: ManifestAnalysis,
        onProgress: ((ProgressUpdate) -> Unit)? = null
    ): DexAnalysis = withContext(Dispatchers.Default) {
        val dexFiles = loadDexFiles(apkPath)
        val stringCollector = StringConstantCollector()
        val fileProviderExtractor = FileProviderExtractor(manifestAnalysis)

        // Also extract FileProvider XML configs from the APK
        fileProviderExtractor.processApkResources(apkPath)

        // --- Pass 1: Build class hierarchy and class index from all DEX classes ---
        val classHierarchy = mutableMapOf<String, String>() // class -> superclass
        val classIndex = mutableMapOf<String, DexBackedClassDef>() // class -> class def
        for (dexFile in dexFiles) {
            for (classDef in dexFile.classes) {
                classIndex[classDef.type] = classDef
                val superclass = classDef.superclass
                if (superclass != null) {
                    classHierarchy[classDef.type] = superclass
                }
            }
        }

        // Build set of all exported component classes in smali format
        val componentClasses = mutableSetOf<String>()
        fun addComponents(components: List<com.droidprobe.app.data.model.ExportedComponent>) {
            components.filter { it.isExported }.forEach { comp ->
                // com.example.LoginActivity -> Lcom/example/LoginActivity;
                val smali = "L${comp.name.replace('.', '/')};"
                componentClasses.add(smali)
            }
        }
        addComponents(manifestAnalysis.activities)
        addComponents(manifestAnalysis.services)
        addComponents(manifestAnalysis.receivers)

        val uriExtractor = UriPatternExtractor(manifestAnalysis, classIndex)
        val intentExtractor = IntentExtraExtractor(classHierarchy, componentClasses, classIndex)
        val callExtractor = ContentProviderCallExtractor()
        val urlExtractor = UrlExtractor(classIndex)

        // --- Pass 1.5: Pre-scan for URI parameter wrapper methods ---
        // Detects methods that internally call getQueryParameter/getBooleanQueryParameter
        // with a parameter-sourced key, enabling inter-procedural param detection.
        // Also detects bulk param readers: getQueryParameterNames() → Map → Map.get("key")
        for (dexFile in dexFiles) {
            for (classDef in dexFile.classes) {
                if (isFrameworkClass(classDef.type)) continue
                uriExtractor.preScanForWrappers(classDef)
                uriExtractor.preScanForBulkParamReaders(classDef)
                urlExtractor.preScanRetrofitBuilders(classDef)
            }
        }

        // --- Pass 2: Extract data from all classes ---
        for ((dexIndex, dexFile) in dexFiles.withIndex()) {
            val classes = dexFile.classes.toList()
            for ((classIdx, classDef) in classes.withIndex()) {
                // Skip framework classes for performance
                val className = classDef.type
                if (isFrameworkClass(className)) continue

                onProgress?.invoke(
                    ProgressUpdate(
                        currentDex = dexIndex + 1,
                        totalDex = dexFiles.size,
                        currentClass = classIdx + 1,
                        totalClasses = classes.size,
                        message = "DEX ${dexIndex + 1}/${dexFiles.size}: ${className.substringAfterLast('/')}"
                    )
                )

                DexDebugLog.logVerbose(className,
                    "[DexAnalyzer] Processing class $className (dex ${dexIndex + 1}, class ${classIdx + 1}/${classes.size})")

                stringCollector.process(classDef)
                uriExtractor.process(classDef)
                intentExtractor.process(classDef)
                fileProviderExtractor.process(classDef)
                callExtractor.process(classDef)
                urlExtractor.process(classDef)
            }
        }

        // Resolve extras in superclasses to their component descendants
        val resolvedExtras = intentExtractor.resolveSuperclassExtras().toMutableList()

        // Resolve activity-alias extras: duplicate target activity's extras to the alias
        for (comp in manifestAnalysis.activities) {
            if (!comp.isExported || comp.targetActivity == null) continue
            val targetExtras = resolvedExtras.filter { it.associatedComponent == comp.targetActivity }
            for (extra in targetExtras) {
                resolvedExtras.add(extra.copy(associatedComponent = comp.name))
            }
        }

        // Link each extra to its component's manifest intent filter action
        val componentActionMap = mutableMapOf<String, String>()
        for (comp in manifestAnalysis.activities + manifestAnalysis.receivers + manifestAnalysis.services) {
            val action = comp.intentFilters.flatMap { it.actions }.firstOrNull() ?: continue
            componentActionMap[comp.name] = action
        }
        val extrasWithActions = resolvedExtras.map { extra ->
            val action = extra.associatedComponent?.let { componentActionMap[it] }
            if (action != null) extra.copy(associatedAction = action) else extra
        }

        val uriResults = uriExtractor.getResults().toMutableList()

        // --- Post-process: Propagate orphaned query params from helper classes ---
        // When an exported activity delegates getQueryParameter() to a helper class
        // (composition pattern), the params are orphaned. Link them by scanning
        // the activity's bytecode for referenced helper classes.
        val orphanedParams = uriExtractor.getOrphanedParamsByClass()
        if (orphanedParams.isNotEmpty()) {
            val existingSourceClasses = uriResults.map { it.sourceClass }.toSet()
            val allComponents = manifestAnalysis.activities + manifestAnalysis.receivers + manifestAnalysis.services
            for (comp in allComponents) {
                if (!comp.isExported) continue

                val compSmali = "L${comp.name.replace('.', '/')};"

                // For activity-aliases, resolve to the target activity's class
                val scanSmali = if (comp.targetActivity != null) {
                    "L${comp.targetActivity.replace('.', '/')};"
                } else {
                    compSmali
                }

                // Skip if this component already has URI results with params
                val alreadyHasParams = uriResults.any {
                    (it.sourceClass == compSmali || it.sourceClass == scanSmali) &&
                        it.queryParameters.isNotEmpty()
                }
                if (alreadyHasParams) continue

                // Collect all non-framework classes referenced by this activity
                val referencedClasses = collectClassReferences(
                    scanSmali, classHierarchy, classIndex
                )

                // Collect orphaned params from the component itself and referenced helper classes
                val params = mutableSetOf<String>()
                val paramValues = mutableMapOf<String, MutableSet<String>>()
                val paramDefaults = mutableMapOf<String, String>()
                // Include the component's own class (activities that call getQueryParameter directly)
                val classesToCheck = referencedClasses + setOfNotNull(compSmali, scanSmali)
                for (refClass in classesToCheck) {
                    val orphaned = orphanedParams[refClass] ?: continue
                    params.addAll(orphaned.params)
                    orphaned.values.forEach { (k, v) ->
                        paramValues.getOrPut(k) { mutableSetOf() }.addAll(v)
                    }
                    paramDefaults.putAll(orphaned.defaults)
                }
                if (params.isEmpty()) continue

                DexDebugLog.log("[DexAnalyzer] Cross-class param propagation: " +
                    "${comp.name} ← ${params.size} params from helper classes")

                // Build URIs from intent filter data (any filter with scheme+host)
                for (filter in comp.intentFilters) {
                    for (scheme in filter.dataSchemes) {
                        for (host in filter.dataAuthorities) {
                            val paths = filter.dataPaths.ifEmpty { listOf("") }
                            for (path in paths) {
                                val uri = if (path.isNotEmpty()) "$scheme://$host$path"
                                    else "$scheme://$host"
                                uriResults.add(
                                    ContentProviderInfo(
                                        authority = host,
                                        uriPattern = uri,
                                        matchCode = null,
                                        associatedColumns = emptyList(),
                                        queryParameters = params.sorted().toList(),
                                        queryParameterValues = paramValues
                                            .filter { it.value.isNotEmpty() }
                                            .mapValues { (_, v) -> v.sorted().toList() },
                                        queryParameterDefaults = paramDefaults,
                                        sourceClass = compSmali,
                                        sourceMethod = null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Post-process fallback: Host-based orphan matching ---
        // For gateway/DI patterns where BFS can't trace the class reference chain
        // (e.g. LensDeeplink → GoogleAppGatewayActivity → dynamic dispatch → bxrh),
        // match orphaned params to components by comparing validated URI hosts.
        if (orphanedParams.isNotEmpty()) {
            val validatedHosts = uriExtractor.getValidatedHostsByClass()
            val allComponents2 = manifestAnalysis.activities + manifestAnalysis.receivers + manifestAnalysis.services
            for (comp in allComponents2) {
                if (!comp.isExported) continue

                val compSmali = "L${comp.name.replace('.', '/')};"
                val scanSmali = if (comp.targetActivity != null) {
                    "L${comp.targetActivity.replace('.', '/')};"
                } else {
                    compSmali
                }

                // Skip if this component already has URI results with params
                val alreadyHasParams = uriResults.any {
                    (it.sourceClass == compSmali || it.sourceClass == scanSmali) &&
                        it.queryParameters.isNotEmpty()
                }
                if (alreadyHasParams) continue

                // Collect all hosts from the component's intent filters
                val filterHosts = comp.intentFilters
                    .flatMap { it.dataAuthorities }
                    .map { it.substringBefore(':') } // strip port
                    .toSet()
                if (filterHosts.isEmpty()) continue

                // Find orphaned classes whose validated hosts match
                val params = mutableSetOf<String>()
                val paramValues = mutableMapOf<String, MutableSet<String>>()
                val paramDefaults = mutableMapOf<String, String>()
                for ((cls, orphaned) in orphanedParams) {
                    val classHosts = validatedHosts[cls] ?: continue
                    if (classHosts.intersect(filterHosts).isEmpty()) continue

                    params.addAll(orphaned.params)
                    orphaned.values.forEach { (k, v) ->
                        paramValues.getOrPut(k) { mutableSetOf() }.addAll(v)
                    }
                    paramDefaults.putAll(orphaned.defaults)
                }
                if (params.isEmpty()) continue

                DexDebugLog.log("[DexAnalyzer] Host-based param propagation: " +
                    "${comp.name} ← ${params.size} params (hosts: $filterHosts)")

                // Build URIs from intent filter data
                for (filter in comp.intentFilters) {
                    for (scheme in filter.dataSchemes) {
                        for (host in filter.dataAuthorities) {
                            val paths = filter.dataPaths.ifEmpty { listOf("") }
                            for (path in paths) {
                                val uri = if (path.isNotEmpty()) "$scheme://$host$path"
                                    else "$scheme://$host"
                                uriResults.add(
                                    ContentProviderInfo(
                                        authority = host,
                                        uriPattern = uri,
                                        matchCode = null,
                                        associatedColumns = emptyList(),
                                        queryParameters = params.sorted().toList(),
                                        queryParameterValues = paramValues
                                            .filter { it.value.isNotEmpty() }
                                            .mapValues { (_, v) -> v.sorted().toList() },
                                        queryParameterDefaults = paramDefaults,
                                        sourceClass = compSmali,
                                        sourceMethod = null
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        DexDebugLog.log("[DexAnalyzer] Analysis complete: " +
            "${uriResults.size} URIs, ${resolvedExtras.size} extras, " +
            "${fileProviderExtractor.getResults().size} fileProvider paths")
        // Log query params summary for debugging
        for (uri in uriResults) {
            if (uri.queryParameters.isNotEmpty() || uri.queryParameterValues.isNotEmpty()) {
                DexDebugLog.logFiltered(uri.sourceClass, null,
                    "[DexAnalyzer] URI \"${uri.uriPattern}\" params=${uri.queryParameters} " +
                    "values=${uri.queryParameterValues} defaults=${uri.queryParameterDefaults}")
            }
        }

        // Add literal URLs as fallback for any not already found by richer strategies
        urlExtractor.addLiteralUrls(stringCollector.getAllUrlStringsWithSource())
        val apiEndpoints = urlExtractor.getResults()

        // CFG-based inter-procedural dataflow: track where sensitive strings actually
        // flow through bytecode to HTTP sinks (e.g., addHeader value arguments),
        // then associate with the URLs used in those same methods.
        val rawSensitiveStrings = stringCollector.getSensitiveStrings()
        val sensitiveValues = rawSensitiveStrings.map { it.value }.toSet()
        val flowAnalyzer = SensitiveStringFlowAnalyzer(classIndex)
        val flowAssociations = flowAnalyzer.analyze(sensitiveValues)
        val sensitiveStrings = rawSensitiveStrings.map { secret ->
            val urls = flowAssociations[secret.value]
            if (urls != null && urls.isNotEmpty()) {
                secret.copy(associatedUrls = urls.sorted().toList())
            } else {
                secret
            }
        }

        DexAnalysis(
            packageName = manifestAnalysis.packageName,
            contentProviderUris = uriResults,
            intentExtras = extrasWithActions,
            fileProviderPaths = fileProviderExtractor.getResults(),
            rawContentUriStrings = stringCollector.getContentUriStrings(),
            deepLinkUriStrings = stringCollector.getDeepLinkUriStrings(),
            contentProviderCalls = callExtractor.getResults(),
            allUrlStrings = stringCollector.getAllUrlStrings(),
            sensitiveStrings = sensitiveStrings,
            apiEndpoints = apiEndpoints
        )
    }

    private fun loadDexFiles(apkPath: String): List<DexBackedDexFile> {
        val results = mutableListOf<DexBackedDexFile>()
        val zipFile = ZipFile(File(apkPath))
        val opcodes = Opcodes.forApi(35)

        try {
            var index = 1
            while (true) {
                val entryName = if (index == 1) "classes.dex" else "classes${index}.dex"
                val entry = zipFile.getEntry(entryName) ?: break
                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                results.add(DexBackedDexFile(opcodes, bytes))
                index++
            }
        } finally {
            zipFile.close()
        }

        return results
    }

    /**
     * Collect non-framework classes referenced in a component's bytecode via BFS,
     * walking the superclass chain at each level and following references up to
     * [maxDepth] hops to catch helper-of-helper delegation chains
     * (e.g. ViewerActivity → bzva → bzwe).
     */
    private fun collectClassReferences(
        compSmali: String,
        classHierarchy: Map<String, String>,
        classIndex: Map<String, DexBackedClassDef>,
        maxDepth: Int = 3
    ): Set<String> {
        val allRefs = mutableSetOf<String>()
        val scanned = mutableSetOf<String>()

        // Seed: the component class + its superclass chain
        var frontier = mutableSetOf(compSmali)
        var superclass = classHierarchy[compSmali]
        while (superclass != null && !isFrameworkClass(superclass)) {
            frontier.add(superclass)
            superclass = classHierarchy[superclass]
        }

        for (depth in 0 until maxDepth) {
            val nextFrontier = mutableSetOf<String>()
            for (cls in frontier) {
                if (cls in scanned) continue
                scanned.add(cls)
                val classDef = classIndex[cls] ?: continue

                for (method in classDef.methods) {
                    val impl = method.implementation ?: continue
                    for (instr in impl.instructions) {
                        if (instr !is ReferenceInstruction) continue
                        when (val ref = instr.reference) {
                            is MethodReference -> {
                                val dc = ref.definingClass
                                if (!isFrameworkClass(dc)) {
                                    allRefs.add(dc)
                                    if (dc !in scanned) nextFrontier.add(dc)
                                }
                                val ret = ref.returnType
                                if (ret.startsWith("L") && !isFrameworkClass(ret)) {
                                    allRefs.add(ret)
                                    if (ret !in scanned) nextFrontier.add(ret)
                                }
                            }
                            is FieldReference -> {
                                val dc = ref.definingClass
                                if (!isFrameworkClass(dc)) {
                                    allRefs.add(dc)
                                    if (dc !in scanned) nextFrontier.add(dc)
                                }
                                val ft = ref.type
                                if (ft.startsWith("L") && !isFrameworkClass(ft)) {
                                    allRefs.add(ft)
                                    if (ft !in scanned) nextFrontier.add(ft)
                                }
                            }
                        }
                    }
                }
            }
            if (nextFrontier.isEmpty()) break
            frontier = nextFrontier
        }

        allRefs.remove(compSmali)
        return allRefs
    }

    private fun isFrameworkClass(type: String): Boolean {
        return type.startsWith("Landroid/") ||
                type.startsWith("Landroidx/") ||
                type.startsWith("Lkotlin/") ||
                type.startsWith("Lkotlinx/") ||
                type.startsWith("Ljava/") ||
                type.startsWith("Ljavax/") ||
                type.startsWith("Ldalvik/") ||
                type.startsWith("Lsun/") ||
                type.startsWith("Lorg/json/") ||
                type.startsWith("Lorg/xmlpull/") ||
                type.startsWith("Lorg/xml/") ||
                type.startsWith("Lorg/w3c/") ||
                type.startsWith("Lorg/apache/") ||
                // Google support/framework libraries (NOT first-party app code)
                type.startsWith("Lcom/google/android/material/") ||
                type.startsWith("Lcom/google/android/gms/") ||
                type.startsWith("Lcom/google/android/play/") ||
                type.startsWith("Lcom/google/android/exoplayer") ||
                type.startsWith("Lcom/google/android/datatransport/") ||
                type.startsWith("Lcom/google/android/libraries/") ||
                // HTTP client libraries (scan annotations/calls but skip internal classes)
                type.startsWith("Lretrofit2/") ||
                type.startsWith("Lokhttp3/") ||
                type.startsWith("Lcom/squareup/okhttp/")
    }
}
