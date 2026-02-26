package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

class StringConstantCollector {

    private val allStrings = mutableSetOf<String>()

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (instruction in impl.instructions) {
                if (instruction is ReferenceInstruction) {
                    val ref = instruction.reference
                    if (ref is StringReference) {
                        allStrings.add(ref.string)
                    }
                }
            }
        }
    }

    fun getContentUriStrings(): List<String> =
        allStrings.filter { it.startsWith("content://") }.sorted()

    fun getColumnNameCandidates(): List<String> =
        allStrings.filter { isLikelyColumnName(it) }.sorted()

    fun getIntentActionCandidates(): List<String> =
        allStrings.filter { isLikelyIntentAction(it) }.sorted()

    fun getIntentExtraKeyCandidates(): List<String> =
        allStrings.filter { isLikelyExtraKey(it) }.sorted()

    fun getAllStrings(): Set<String> = allStrings

    private fun isLikelyColumnName(s: String): Boolean {
        if (s.length < 2 || s.length > 100) return false
        if (s.contains(' ') || s.contains('/') || s.contains(':')) return false
        // Common column name patterns: _id, _count, snake_case identifiers
        return s == "_id" || s == "_count" ||
                (s.matches(Regex("^[a-z][a-z0-9_]*$")) && s.contains('_'))
    }

    private fun isLikelyIntentAction(s: String): Boolean {
        return s.contains(".action.") ||
                s.contains(".intent.action.") ||
                s.startsWith("android.intent.action.")
    }

    private fun isLikelyExtraKey(s: String): Boolean {
        return s.contains(".extra.") ||
                s.contains(".EXTRA_") ||
                s.contains("_EXTRA") ||
                s.startsWith("android.intent.extra.")
    }
}
