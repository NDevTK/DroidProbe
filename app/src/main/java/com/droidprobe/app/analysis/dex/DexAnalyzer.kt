package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
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
        val uriExtractor = UriPatternExtractor(manifestAnalysis)
        val fileProviderExtractor = FileProviderExtractor(manifestAnalysis)

        // Also extract FileProvider XML configs from the APK
        fileProviderExtractor.processApkResources(apkPath)

        // --- Pass 1: Build class hierarchy from all DEX classes ---
        val classHierarchy = mutableMapOf<String, String>() // class -> superclass
        for (dexFile in dexFiles) {
            for (classDef in dexFile.classes) {
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

        val intentExtractor = IntentExtraExtractor(classHierarchy, componentClasses)
        val callExtractor = ContentProviderCallExtractor()

        // --- Pass 1.5: Pre-scan for URI parameter wrapper methods ---
        // Detects methods that internally call getQueryParameter/getBooleanQueryParameter
        // with a parameter-sourced key, enabling inter-procedural param detection.
        for (dexFile in dexFiles) {
            for (classDef in dexFile.classes) {
                if (isFrameworkClass(classDef.type)) continue
                uriExtractor.preScanForWrappers(classDef)
            }
        }

        // --- Pass 2: Extract data from all classes ---
        for ((dexIndex, dexFile) in dexFiles.withIndex()) {
            val classes = dexFile.classes.toList()
            for ((classIndex, classDef) in classes.withIndex()) {
                // Skip framework classes for performance
                val className = classDef.type
                if (isFrameworkClass(className)) continue

                onProgress?.invoke(
                    ProgressUpdate(
                        currentDex = dexIndex + 1,
                        totalDex = dexFiles.size,
                        currentClass = classIndex + 1,
                        totalClasses = classes.size,
                        message = "DEX ${dexIndex + 1}/${dexFiles.size}: ${className.substringAfterLast('/')}"
                    )
                )

                DexDebugLog.logVerbose(className,
                    "[DexAnalyzer] Processing class $className (dex ${dexIndex + 1}, class ${classIndex + 1}/${classes.size})")

                stringCollector.process(classDef)
                uriExtractor.process(classDef)
                intentExtractor.process(classDef)
                fileProviderExtractor.process(classDef)
                callExtractor.process(classDef)
            }
        }

        // Resolve extras in superclasses to their component descendants
        val resolvedExtras = intentExtractor.resolveSuperclassExtras()

        val uriResults = uriExtractor.getResults()
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

        DexAnalysis(
            packageName = manifestAnalysis.packageName,
            contentProviderUris = uriResults,
            intentExtras = resolvedExtras,
            fileProviderPaths = fileProviderExtractor.getResults(),
            rawContentUriStrings = stringCollector.getContentUriStrings(),
            deepLinkUriStrings = stringCollector.getDeepLinkUriStrings(),
            contentProviderCalls = callExtractor.getResults(),
            allUrlStrings = stringCollector.getAllUrlStrings(),
            sensitiveStrings = stringCollector.getSensitiveStrings()
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
                type.startsWith("Lcom/google/android/libraries/")
    }
}
