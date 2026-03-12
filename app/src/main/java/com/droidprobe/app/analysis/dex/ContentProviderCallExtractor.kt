package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.droidprobe.app.data.model.ContentProviderCallInfo
import com.droidprobe.app.data.model.ContentResolverQueryInfo

/**
 * Detects ContentResolver.call() and ContentResolver.query() invocations in DEX bytecode.
 *
 * ContentResolver.call() has two overloads:
 * - call(Uri uri, String method, String arg, Bundle extras)
 * - call(String authority, String method, String arg, Bundle extras)  (API 29+)
 *
 * ContentResolver.query():
 * - query(Uri, String[], String, String[], String) — 5 params, uses invoke-virtual/range
 *   Registers: this, uri, projection, selection, selectionArgs, sortOrder
 *
 * Uses CFG-based string register tracking for accurate cross-branch analysis.
 */
class ContentProviderCallExtractor {

    private val results = mutableListOf<ContentProviderCallInfo>()
    private val queryResults = mutableListOf<ContentResolverQueryInfo>()
    private val seenCalls = mutableSetOf<String>()
    private val seenQueries = mutableSetOf<String>()

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()
            scanMethod(classDef, method, instructions, cfgStrings)
        }
    }

    fun getResults(): List<ContentProviderCallInfo> = results.toList()
    fun getQueryResults(): List<ContentResolverQueryInfo> = queryResults.toList()

    private fun scanMethod(
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        for ((idx, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            if (isContentResolverCall(ref)) {
                handleCall(ref, cfgStrings, idx, instr, instructions, classDef, method)
            }
            if (isContentResolverQuery(ref)) {
                handleQuery(cfgStrings, instr, instructions, idx, classDef, method)
            }
        }
    }

    private fun isContentResolverCall(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/ContentResolver;" &&
                ref.name == "call"
    }

    private fun isContentResolverQuery(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/ContentResolver;" &&
                ref.name == "query" &&
                ref.parameterTypes.size >= 4
    }

    private fun handleCall(
        ref: MethodReference,
        cfgStrings: Array<Map<Int, String>?>,
        instrIdx: Int,
        instr: Instruction,
        instructions: List<Instruction>,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        if (instr !is Instruction35c) return

        val state = cfgStrings[instrIdx] ?: return

        // Both overloads: this(C), uri/authority(D), method(E), arg(F), extras(G)
        val methodName = state[instr.registerE] ?: return
        val arg = state[instr.registerF]

        // For the Uri overload, registerD holds a Uri object (not a string).
        // Trace back to find Uri.parse(string) that created it.
        val paramTypes = ref.parameterTypes.toList()
        val authorityOrUri = state[instr.registerD]
            ?: resolveUriString(instructions, cfgStrings, instrIdx, instr.registerD)

        val authority = when {
            paramTypes.isNotEmpty() && paramTypes[0] == "Ljava/lang/String;" -> authorityOrUri
            authorityOrUri != null && authorityOrUri.startsWith("content://") -> {
                authorityOrUri.removePrefix("content://").substringBefore('/')
            }
            else -> null
        }

        val dedupKey = "$authority:$methodName:${classDef.type}"
        if (dedupKey in seenCalls) return
        seenCalls.add(dedupKey)

        results.add(
            ContentProviderCallInfo(
                authority = authority,
                methodName = methodName,
                arg = arg,
                sourceClass = classDef.type,
                sourceMethod = method.name
            )
        )
    }

    private fun handleQuery(
        cfgStrings: Array<Map<Int, String>?>,
        instr: Instruction,
        instructions: List<Instruction>,
        instrIdx: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val uriReg: Int
        val projReg: Int
        val selReg: Int
        val sortReg: Int

        when (instr) {
            is Instruction3rc -> {
                val base = instr.startRegister
                uriReg = base + 1
                projReg = base + 2
                selReg = base + 3
                sortReg = base + 5
            }
            is Instruction35c -> {
                if (instr.registerCount < 5) return
                uriReg = instr.registerD
                projReg = instr.registerE
                selReg = instr.registerF
                sortReg = -1
            }
            else -> return
        }

        val state = cfgStrings[instrIdx] ?: return
        val uri = state[uriReg] ?: resolveUriString(instructions, cfgStrings, instrIdx, uriReg)
        val selection = state[selReg]
        val sortOrder = if (sortReg >= 0) state[sortReg] else null

        // Extract projection array
        val projection = extractStringArray(instructions, cfgStrings, instrIdx, projReg)

        if (projection.isEmpty() && selection == null && sortOrder == null) return

        val dedupKey = "${classDef.type}:${method.name}:$uri"
        if (dedupKey in seenQueries) return
        seenQueries.add(dedupKey)

        queryResults.add(
            ContentResolverQueryInfo(
                uri = uri,
                projection = projection,
                selection = selection,
                sortOrder = sortOrder,
                sourceClass = classDef.type,
                sourceMethod = method.name
            )
        )
    }

    /**
     * Resolve a URI string by tracing backward from a register that holds a Uri object.
     * Finds Uri.parse(string) calls where the result was move-result-object'd into the target register,
     * then returns the string argument from CFG state at the Uri.parse() call site.
     */
    private fun resolveUriString(
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>,
        fromIdx: Int,
        reg: Int
    ): String? {
        for (j in (fromIdx - 1) downTo 0) {
            val prev = instructions[j]

            // move-result-object vReg — find the invoke that preceded it
            if (prev.opcode == Opcode.MOVE_RESULT_OBJECT &&
                prev is OneRegisterInstruction && prev.registerA == reg
            ) {
                // The invoke is at j-1
                if (j > 0) {
                    val invoke = instructions[j - 1]
                    if (invoke is ReferenceInstruction) {
                        val ref = invoke.reference as? MethodReference
                        if (ref != null &&
                            ref.definingClass == "Landroid/net/Uri;" &&
                            ref.name == "parse" &&
                            ref.parameterTypes.size == 1
                        ) {
                            // Uri.parse(String) — get the string argument from CFG at the invoke
                            val invokeState = cfgStrings[j - 1]
                            val argReg = when (invoke) {
                                is Instruction35c -> invoke.registerC // static: first arg is registerC
                                is Instruction3rc -> invoke.startRegister
                                else -> return null
                            }
                            return invokeState?.get(argReg)
                        }
                    }
                }
                // Some other method produced this register — stop looking
                return null
            }

            // Stop if something else writes to this register
            if (prev is OneRegisterInstruction && prev.registerA == reg) return null
        }
        return null
    }

    /**
     * Extract string array contents by walking backward from the consuming instruction
     * to find new-array + aput-object pattern or filled-new-array.
     * Follows MOVE_OBJECT aliases and uses CFG for string resolution.
     */
    private fun extractStringArray(
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>,
        targetIdx: Int,
        arrayReg: Int
    ): List<String> {
        var currentReg = arrayReg

        for (j in (targetIdx - 1) downTo 0) {
            val prev = instructions[j]

            // Follow MOVE_OBJECT aliases
            if ((prev.opcode == Opcode.MOVE_OBJECT || prev.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                        prev.opcode == Opcode.MOVE_OBJECT_16) &&
                prev is TwoRegisterInstruction &&
                prev.registerA == currentReg
            ) {
                currentReg = prev.registerB
                continue
            }

            // filled-new-array {v0, v1, v2}, [Ljava/lang/String;
            if (prev.opcode == Opcode.FILLED_NEW_ARRAY && prev is Instruction35c && prev is ReferenceInstruction) {
                val typeRef = prev.reference as? TypeReference
                if (typeRef?.type == "[Ljava/lang/String;") {
                    if (j + 1 < instructions.size) {
                        val moveResult = instructions[j + 1]
                        if (moveResult.opcode == Opcode.MOVE_RESULT_OBJECT &&
                            moveResult is OneRegisterInstruction &&
                            moveResult.registerA == currentReg
                        ) {
                            return extractFilledNewArrayStrings(prev, cfgStrings, j)
                        }
                    }
                }
            }

            // new-array vArrayReg, vSize, [Ljava/lang/String;
            if (prev.opcode == Opcode.NEW_ARRAY && prev is ReferenceInstruction) {
                val typeRef = prev.reference as? TypeReference
                if (typeRef?.type == "[Ljava/lang/String;" &&
                    prev is OneRegisterInstruction && prev.registerA == currentReg
                ) {
                    return extractAputStrings(instructions, cfgStrings, j, targetIdx, currentReg)
                }
            }
        }

        return emptyList()
    }

    /**
     * Extract strings from a filled-new-array instruction's registers using CFG state.
     */
    private fun extractFilledNewArrayStrings(
        instr: Instruction35c,
        cfgStrings: Array<Map<Int, String>?>,
        instrIdx: Int
    ): List<String> {
        val state = cfgStrings[instrIdx] ?: return emptyList()
        val count = instr.registerCount
        val result = mutableListOf<String>()
        val regList = listOf(instr.registerC, instr.registerD, instr.registerE, instr.registerF, instr.registerG)
        for (i in 0 until count.coerceAtMost(5)) {
            val value = state[regList[i]]
            if (value != null) result.add(value)
        }
        return result
    }

    /**
     * Collect string values from aput-object instructions between new-array and target.
     * Uses CFG state at each aput-object for string resolution, and sequential ordering
     * for array indices (compiler always emits them in order).
     */
    private fun extractAputStrings(
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>,
        newArrayIdx: Int,
        targetIdx: Int,
        arrayReg: Int
    ): List<String> {
        val values = mutableListOf<String>()

        for (j in (newArrayIdx + 1) until targetIdx) {
            val instr = instructions[j]
            if (instr.opcode != Opcode.APUT_OBJECT) continue

            val i23x = instr as? com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction23x
                ?: continue

            if (i23x.registerB != arrayReg) continue

            val state = cfgStrings[j] ?: continue
            val value = state[i23x.registerA] ?: continue

            values.add(value)
        }

        return values
    }
}
