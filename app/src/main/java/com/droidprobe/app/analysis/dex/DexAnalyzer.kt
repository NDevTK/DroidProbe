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

                stringCollector.process(classDef)
                uriExtractor.process(classDef)
                intentExtractor.process(classDef)
                fileProviderExtractor.process(classDef)
            }
        }

        // Resolve extras in superclasses to their component descendants
        val resolvedExtras = intentExtractor.resolveSuperclassExtras()

        DexAnalysis(
            packageName = manifestAnalysis.packageName,
            contentProviderUris = uriExtractor.getResults(),
            intentExtras = resolvedExtras,
            fileProviderPaths = fileProviderExtractor.getResults(),
            rawContentUriStrings = stringCollector.getContentUriStrings()
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
                type.startsWith("Lcom/google/android/") ||
                type.startsWith("Ldalvik/") ||
                type.startsWith("Lsun/") ||
                type.startsWith("Lorg/json/") ||
                type.startsWith("Lorg/xmlpull/") ||
                type.startsWith("Lorg/xml/") ||
                type.startsWith("Lorg/w3c/") ||
                type.startsWith("Lorg/apache/")
    }
}
