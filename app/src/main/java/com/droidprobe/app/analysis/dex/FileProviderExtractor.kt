package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.analysis.AxmlParser
import com.droidprobe.app.data.model.FileProviderInfo
import com.droidprobe.app.data.model.ManifestAnalysis
import java.io.File
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
                val xmlEntries = zipFile.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith("res/xml/") && entry.name.endsWith(".xml")
                    }
                    .toList()

                for (entry in xmlEntries) {
                    try {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
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

    private val PATH_TYPES = setOf(
        "root-path", "files-path", "cache-path", "external-path",
        "external-files-path", "external-cache-path", "external-media-path"
    )

    /**
     * Parse binary XML resource using proper AXML parsing to extract
     * FileProvider path element names and attributes.
     */
    private fun parseResourceXml(bytes: ByteArray, fileName: String) {
        val events = AxmlParser().parse(bytes) ?: return
        val authority = fileProviderAuthorities.keys.firstOrNull() ?: "unknown"

        for (event in events) {
            if (event is AxmlParser.XmlEvent.StartElement && event.name in PATH_TYPES) {
                addResult(
                    authority = authority,
                    pathType = event.name,
                    path = event.attributes["path"] ?: ".",
                    name = event.attributes["name"] ?: event.name
                )
            }
        }
    }

    /**
     * Scan DEX for FileProvider.getUriForFile() calls to discover file access patterns.
     * getUriForFile is static: invoke-static {context, authority, file}
     *   arg0 = Context, arg1 = String authority, arg2 = File file
     */
    private fun scanForFileProviderUsage(classDef: DexBackedClassDef, instructions: List<Instruction>) {
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            if (ref.definingClass == "Landroidx/core/content/FileProvider;" &&
                ref.name == "getUriForFile"
            ) {
                // Handle both invoke-static (35c) and invoke-static/range (3rc)
                val authorityReg: Int
                val fileReg: Int
                when (instr) {
                    is Instruction35c -> {
                        authorityReg = instr.registerD
                        fileReg = instr.registerE
                    }
                    is Instruction3rc -> {
                        authorityReg = instr.startRegister + 1
                        fileReg = instr.startRegister + 2
                    }
                    else -> continue
                }

                val authority = resolveStringFromRegister(instructions, i, authorityReg)
                    ?: continue
                val fileRegResolved = resolveRegisterAlias(instructions, i, fileReg)
                val filePath = resolveFilePathFromRegister(instructions, i, fileRegResolved)

                addResult(
                    authority = authority,
                    pathType = "code-reference",
                    path = ".",
                    name = filePath ?: "getUriForFile",
                    filePath = filePath
                )
            }
        }
    }

    /**
     * Trace backward through move-object instructions to find the original register.
     * e.g. if v6 was set by `move-object v6, v4`, returns v4.
     */
    private fun resolveRegisterAlias(
        instructions: List<Instruction>,
        callIndex: Int,
        register: Int
    ): Int {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 30)) {
            val instr = instructions[j]
            if (instr.opcode == Opcode.MOVE_OBJECT ||
                instr.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                instr.opcode == Opcode.MOVE_OBJECT_16
            ) {
                if (instr is TwoRegisterInstruction && instr.registerA == register) {
                    return instr.registerB
                }
            }
        }
        return register
    }

    /**
     * Resolve the filename from a File object register by scanning backward for
     * the File constructor call and extracting its String argument.
     *
     * Handles:
     *   File(File parent, String child) — (Ljava/io/File;Ljava/lang/String;)V
     *   File(String path)               — (Ljava/lang/String;)V
     *   File(String parent, String child)— (Ljava/lang/String;Ljava/lang/String;)V
     */
    private fun resolveFilePathFromRegister(
        instructions: List<Instruction>,
        callIndex: Int,
        fileReg: Int
    ): String? {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 40)) {
            val instr = instructions[j]
            if (instr.opcode != Opcode.INVOKE_DIRECT) continue
            if (instr !is ReferenceInstruction) continue

            val ref = instr.reference
            if (ref !is MethodReference) continue
            if (ref.definingClass != "Ljava/io/File;" || ref.name != "<init>") continue

            // Handle both invoke-direct (35c) and invoke-direct/range (3rc)
            val thisReg: Int
            val arg1Reg: Int
            val arg2Reg: Int
            when (instr) {
                is Instruction35c -> {
                    thisReg = instr.registerC
                    arg1Reg = instr.registerD
                    arg2Reg = instr.registerE
                }
                is Instruction3rc -> {
                    thisReg = instr.startRegister
                    arg1Reg = instr.startRegister + 1
                    arg2Reg = instr.startRegister + 2
                }
                else -> continue
            }
            if (thisReg != fileReg) continue

            val params = ref.parameterTypes.map { it.toString() }
            return when (params) {
                // File(File parent, String child) → child is arg2
                listOf("Ljava/io/File;", "Ljava/lang/String;") ->
                    resolveStringFromRegister(instructions, j, arg2Reg)

                // File(String path) → path is arg1
                listOf("Ljava/lang/String;") ->
                    resolveStringFromRegister(instructions, j, arg1Reg)

                // File(String parent, String child) → child is arg2
                listOf("Ljava/lang/String;", "Ljava/lang/String;") ->
                    resolveStringFromRegister(instructions, j, arg2Reg)

                else -> null
            }
        }
        return null
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

    private fun addResult(
        authority: String,
        pathType: String,
        path: String,
        name: String,
        filePath: String? = null
    ) {
        val key = "$authority:$pathType:$path:$name:${filePath ?: ""}"
        if (key in seenPaths) return
        seenPaths.add(key)

        results.add(
            FileProviderInfo(
                authority = authority,
                pathType = pathType,
                path = path,
                name = name,
                filePath = filePath
            )
        )
    }
}
