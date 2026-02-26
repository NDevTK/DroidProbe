package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.ContentProviderCallInfo

/**
 * Detects ContentResolver.call() invocations in DEX bytecode.
 *
 * ContentResolver.call() has two overloads:
 * - call(Uri uri, String method, String arg, Bundle extras)
 * - call(String authority, String method, String arg, Bundle extras)  (API 29+)
 *
 * In both cases the method name is the 2nd argument (registerE in Instruction35c).
 */
class ContentProviderCallExtractor {

    private val results = mutableListOf<ContentProviderCallInfo>()
    private val seenCalls = mutableSetOf<String>()

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanMethod(classDef, method, instructions)
        }
    }

    fun getResults(): List<ContentProviderCallInfo> = results.toList()

    private fun scanMethod(
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        instructions: List<Instruction>
    ) {
        val regStrings = mutableMapOf<Int, String>()

        for (instr in instructions) {
            trackStringRegisters(instr, regStrings)

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            if (isContentResolverCall(ref)) {
                handleCall(ref, regStrings, instr, classDef, method)
            }
        }
    }

    private fun isContentResolverCall(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/ContentResolver;" &&
                ref.name == "call"
    }

    private fun handleCall(
        ref: MethodReference,
        regStrings: Map<Int, String>,
        instr: Instruction,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        if (instr !is Instruction35c) return

        // Both overloads: this(C), uri/authority(D), method(E), arg(F), extras(G)
        val authorityOrUri = regStrings[instr.registerD]
        val methodName = regStrings[instr.registerE] ?: return

        // Determine authority based on the first parameter type
        val paramTypes = ref.parameterTypes.toList()
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
                sourceClass = classDef.type,
                sourceMethod = method.name
            )
        )
    }

    private fun trackStringRegisters(instr: Instruction, regStrings: MutableMap<Int, String>) {
        if (instr.opcode == Opcode.CONST_STRING || instr.opcode == Opcode.CONST_STRING_JUMBO) {
            if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is StringReference) {
                    regStrings[instr.registerA] = ref.string
                }
            }
        }
    }
}
