package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.CrudOperationInfo

/**
 * Detects ContentProvider CRUD operations beyond query():
 * - insert(): ContentValues.getAsString/getAsInteger/getAsLong/put key extraction
 * - update(): same ContentValues keys + selection string patterns
 * - delete(): selection string patterns
 * - getType(): MIME type return values
 *
 * Only processes classes that extend ContentProvider (via class hierarchy).
 */
class ContentProviderCrudExtractor(
    private val classHierarchy: Map<String, String>
) {
    private val results = mutableListOf<CrudOperationInfo>()
    private val seenOps = mutableSetOf<String>()

    fun process(classDef: DexBackedClassDef) {
        if (!isContentProviderClass(classDef.type)) return

        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()
            val methodName = method.name

            // Determine operation type from method name
            val operation = when (methodName) {
                "insert" -> "INSERT"
                "update" -> "UPDATE"
                "delete" -> "DELETE"
                "getType" -> "GET_TYPE"
                else -> null
            } ?: continue

            val contentValuesKeys = mutableSetOf<String>()
            val mimeTypes = mutableSetOf<String>()

            for ((i, instr) in instructions.withIndex()) {
                // getType(): scan all const-string instructions for MIME-type patterns
                // (CFG must-agree kills values at switch/branch merge points, so scan directly)
                if (operation == "GET_TYPE" && instr is ReferenceInstruction) {
                    val strRef = instr.reference as? StringReference
                    if (strRef != null) {
                        val str = strRef.string
                        if (str.startsWith("vnd.") || str.contains("/vnd.") ||
                            str.startsWith("application/") || str.startsWith("text/") ||
                            str.startsWith("image/") || str.startsWith("video/") ||
                            str.startsWith("audio/")) {
                            mimeTypes.add(str)
                        }
                    }
                }

                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference as? MethodReference ?: continue

                when {
                    // ContentValues.getAsXxx(key) — reading keys in insert/update
                    ref.definingClass == "Landroid/content/ContentValues;" &&
                        ref.name.startsWith("getAs") &&
                        ref.parameterTypes.size == 1 &&
                        ref.parameterTypes[0] == "Ljava/lang/String;" -> {
                        val call = instr as? Instruction35c ?: continue
                        val key = cfgStrings[i]?.get(call.registerD) ?: continue
                        contentValuesKeys.add(key)
                    }

                    // ContentValues.put(key, value) — writing keys
                    ref.definingClass == "Landroid/content/ContentValues;" &&
                        ref.name == "put" &&
                        ref.parameterTypes.size == 2 &&
                        ref.parameterTypes[0] == "Ljava/lang/String;" -> {
                        val call = instr as? Instruction35c ?: continue
                        val key = cfgStrings[i]?.get(call.registerD) ?: continue
                        contentValuesKeys.add(key)
                    }
                }
            }

            if (contentValuesKeys.isEmpty() && mimeTypes.isEmpty() && operation != "DELETE") continue

            val dedupKey = "${classDef.type}:$operation"
            if (dedupKey in seenOps) continue
            seenOps.add(dedupKey)

            results.add(
                CrudOperationInfo(
                    operation = operation,
                    contentValuesKeys = contentValuesKeys.sorted(),
                    mimeTypes = mimeTypes.sorted(),
                    sourceClass = classDef.type,
                    sourceMethod = methodName
                )
            )
        }
    }

    fun getResults(): List<CrudOperationInfo> = results.toList()

    private fun isContentProviderClass(className: String): Boolean {
        var current: String? = className
        var depth = 0
        while (current != null && depth < 10) {
            if (current == "Landroid/content/ContentProvider;") return true
            current = classHierarchy[current]
            depth++
        }
        return false
    }
}
