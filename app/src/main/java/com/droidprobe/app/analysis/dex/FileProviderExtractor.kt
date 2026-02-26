package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.FileProviderInfo
import com.droidprobe.app.data.model.ManifestAnalysis
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Extracts FileProvider path configurations from:
 * 1. APK resources (file_paths.xml or similar XML configs)
 * 2. DEX bytecode (FileProvider.getUriForFile() call patterns)
 */
class FileProviderExtractor(private val manifestAnalysis: ManifestAnalysis) {

    private val results = mutableListOf<FileProviderInfo>()
    private val seenPaths = mutableSetOf<String>()

    // FileProvider authorities from manifest
    private val fileProviderAuthorities: Map<String, String> = run {
        manifestAnalysis.providers
            .filter { provider ->
                provider.name.contains("FileProvider", ignoreCase = true)
            }
            .mapNotNull { provider ->
                provider.authority?.let { auth -> auth to provider.name }
            }
            .toMap()
    }

    /**
     * Parse file_paths.xml from the APK's res/xml/ directory.
     * FileProvider configs are stored as XML resources referenced by meta-data.
     */
    fun processApkResources(apkPath: String) {
        try {
            val zipFile = ZipFile(File(apkPath))
            try {
                // Look for XML files in res/xml/ that might be file provider configs
                val xmlEntries = zipFile.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith("res/xml/") && entry.name.endsWith(".xml")
                    }
                    .toList()

                for (entry in xmlEntries) {
                    try {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        // Android binary XML (AXML) starts with specific magic bytes
                        // We can try to detect file_paths content by checking for known tag names
                        // in the binary. Binary XML parsing is complex, so we look for string patterns.
                        parseResourceXml(bytes, entry.name)
                    } catch (_: Exception) {
                        // Skip unparseable XML files
                    }
                }
            } finally {
                zipFile.close()
            }
        } catch (_: Exception) {
            // APK not accessible
        }
    }

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanForFileProviderUsage(classDef, instructions)
        }
    }

    fun getResults(): List<FileProviderInfo> = results.toList()

    /**
     * Parse binary XML resources looking for FileProvider path patterns.
     * Android binary XML encodes strings in a string pool — we can search for
     * known FileProvider XML element names in the raw bytes.
     */
    private fun parseResourceXml(bytes: ByteArray, fileName: String) {
        // Known FileProvider path element types
        val pathTypes = listOf(
            "root-path", "files-path", "cache-path", "external-path",
            "external-files-path", "external-cache-path", "external-media-path"
        )

        // Check if this XML likely contains FileProvider paths by searching for known strings
        val content = String(bytes, Charsets.UTF_8) // Won't fully work for binary XML but catches strings
        val containsPathTypes = pathTypes.any { content.contains(it) }

        if (!containsPathTypes) return

        // Extract string patterns from binary XML
        // Binary XML stores strings in a pool at the beginning of the file.
        // We scan for readable UTF-16LE encoded strings that match known patterns.
        val extractedStrings = extractStringsFromBinaryXml(bytes)

        // Reconstruct FileProvider paths from extracted strings
        val authority = fileProviderAuthorities.keys.firstOrNull() ?: "unknown"

        for (pathType in pathTypes) {
            if (pathType in extractedStrings) {
                // Look for associated "name" and "path" attributes
                val nameAttrs = extractedStrings.filter {
                    it.length < 100 && !it.contains('/') && !it.contains('.') &&
                            it != pathType && it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
                }
                val pathAttrs = extractedStrings.filter {
                    it.length < 200 && (it == "." || it == "/" || it.contains('/') ||
                            it.matches(Regex("[a-zA-Z0-9_./]+"))) && it != pathType
                }

                val name = nameAttrs.firstOrNull() ?: pathType
                val path = pathAttrs.firstOrNull() ?: "."

                addResult(authority, pathType, path, name)
            }
        }
    }

    /**
     * Extract readable strings from Android binary XML.
     * The string pool in binary XML contains UTF-16LE encoded strings.
     */
    private fun extractStringsFromBinaryXml(bytes: ByteArray): Set<String> {
        val strings = mutableSetOf<String>()

        // Look for UTF-8 readable strings in the byte array
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() || c in ".-_/") {
                sb.append(c)
            } else {
                if (sb.length >= 2) {
                    strings.add(sb.toString())
                }
                sb.clear()
            }
        }
        if (sb.length >= 2) {
            strings.add(sb.toString())
        }

        return strings
    }

    /**
     * Scan DEX for FileProvider.getUriForFile() calls to discover file access patterns.
     */
    private fun scanForFileProviderUsage(classDef: DexBackedClassDef, instructions: List<Instruction>) {
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            if (ref.definingClass == "Landroidx/core/content/FileProvider;" &&
                ref.name == "getUriForFile"
            ) {
                // getUriForFile(context, authority, file)
                val callInstr = instr as? Instruction35c ?: continue
                val authorityReg = callInstr.registerE
                val authority = resolveStringFromRegister(instructions, i, authorityReg)

                if (authority != null && authority !in seenPaths) {
                    addResult(
                        authority = authority,
                        pathType = "code-reference",
                        path = ".",
                        name = "getUriForFile"
                    )
                }
            }
        }
    }

    private fun resolveStringFromRegister(
        instructions: List<Instruction>,
        callIndex: Int,
        register: Int
    ): String? {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 30)) {
            val prev = instructions[j]
            if (prev.opcode == Opcode.CONST_STRING || prev.opcode == Opcode.CONST_STRING_JUMBO) {
                if (prev is OneRegisterInstruction && prev.registerA == register) {
                    val ref = (prev as? ReferenceInstruction)?.reference
                    if (ref is StringReference) return ref.string
                }
            }
        }
        return null
    }

    private fun addResult(authority: String, pathType: String, path: String, name: String) {
        val key = "$authority:$pathType:$path:$name"
        if (key in seenPaths) return
        seenPaths.add(key)

        results.add(
            FileProviderInfo(
                authority = authority,
                pathType = pathType,
                path = path,
                name = name
            )
        )
    }
}
