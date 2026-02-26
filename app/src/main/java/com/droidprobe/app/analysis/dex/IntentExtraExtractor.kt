package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.IntentInfo

/**
 * Extracts intent extra keys, types, and possible values from DEX bytecode.
 *
 * Detection patterns:
 * - Intent.putExtra(key, value) — extracts both key and literal value
 * - Intent.getXxxExtra(key) — extracts key, then scans forward for:
 *   - String.equals("value") / equalsIgnoreCase("value") comparisons
 *   - TextUtils.equals(extraValue, "value") comparisons
 *   - packed-switch / sparse-switch on int results
 * - Bundle.putXxx(key, value) and Bundle.getXxx(key) — same patterns
 * - Intent.setAction(action)
 *
 * Uses the class hierarchy to resolve which exported component each extra
 * actually belongs to — traces inheritance, not name guessing.
 */
class IntentExtraExtractor(
    private val classHierarchy: Map<String, String>,
    private val componentClasses: Set<String>
) {

    private val results = mutableListOf<IntentInfo>()
    // Maps dedup key -> index in results list, for merging values
    private val resultIndex = mutableMapOf<String, Int>()
    private val discoveredActions = mutableSetOf<String>()

    private val componentResolutionCache = mutableMapOf<String, String?>()

    private val getExtraTypeMap = mapOf(
        "getStringExtra" to "String",
        "getIntExtra" to "Int",
        "getLongExtra" to "Long",
        "getBooleanExtra" to "Boolean",
        "getFloatExtra" to "Float",
        "getDoubleExtra" to "Double",
        "getByteExtra" to "Byte",
        "getShortExtra" to "Short",
        "getCharExtra" to "Char",
        "getStringArrayExtra" to "String[]",
        "getIntArrayExtra" to "Int[]",
        "getStringArrayListExtra" to "ArrayList<String>",
        "getIntegerArrayListExtra" to "ArrayList<Int>",
        "getParcelableExtra" to "Parcelable",
        "getParcelableArrayExtra" to "Parcelable[]",
        "getParcelableArrayListExtra" to "ArrayList<Parcelable>",
        "getSerializableExtra" to "Serializable",
        "getBundleExtra" to "Bundle"
    )

    // String return types that support equals comparison scanning
    private val stringResultTypes = setOf(
        "getStringExtra", "getSerializableExtra"
    )

    // Int return types that support switch scanning
    private val intResultTypes = setOf(
        "getIntExtra", "getShortExtra", "getByteExtra"
    )

    private val putExtraTypeMap = mapOf(
        "(Ljava/lang/String;Ljava/lang/String;)" to "String",
        "(Ljava/lang/String;I)" to "Int",
        "(Ljava/lang/String;J)" to "Long",
        "(Ljava/lang/String;Z)" to "Boolean",
        "(Ljava/lang/String;F)" to "Float",
        "(Ljava/lang/String;D)" to "Double",
        "(Ljava/lang/String;B)" to "Byte",
        "(Ljava/lang/String;S)" to "Short",
        "(Ljava/lang/String;C)" to "Char",
        "(Ljava/lang/String;Landroid/os/Parcelable;)" to "Parcelable",
        "(Ljava/lang/String;Ljava/io/Serializable;)" to "Serializable",
        "(Ljava/lang/String;Landroid/os/Bundle;)" to "Bundle",
        "(Ljava/lang/String;[Ljava/lang/String;)" to "String[]",
        "(Ljava/lang/String;[I)" to "Int[]"
    )

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanMethod(classDef, method, instructions)
        }
    }

    fun getResults(): List<IntentInfo> = results.toList()

    fun resolveComponent(smaliType: String): String? {
        componentResolutionCache[smaliType]?.let { return it }

        if (smaliType in componentClasses) {
            componentResolutionCache[smaliType] = smaliType
            return smaliType
        }

        val dollarIdx = smaliType.indexOf('$')
        if (dollarIdx > 0) {
            val outer = smaliType.substring(0, dollarIdx) + ";"
            val resolved = resolveComponent(outer)
            if (resolved != null) {
                componentResolutionCache[smaliType] = resolved
                return resolved
            }
        }

        var current: String? = smaliType
        val visited = mutableSetOf<String>()
        while (current != null && current !in visited) {
            visited.add(current)
            if (current in componentClasses) {
                componentResolutionCache[smaliType] = current
                return current
            }
            current = classHierarchy[current]
        }

        componentResolutionCache[smaliType] = null
        return null
    }

    fun resolveSuperclassExtras(): List<IntentInfo> {
        val subclassMap = mutableMapOf<String, MutableSet<String>>()
        for (compClass in componentClasses) {
            var current: String? = classHierarchy[compClass]
            val visited = mutableSetOf(compClass)
            while (current != null && current !in visited) {
                visited.add(current)
                subclassMap.getOrPut(current) { mutableSetOf() }.add(compClass)
                current = classHierarchy[current]
            }
        }

        val additional = mutableListOf<IntentInfo>()
        val toRemove = mutableListOf<IntentInfo>()

        for (result in results) {
            if (result.associatedComponent != null) continue

            val descendants = subclassMap[result.sourceClass]
            if (descendants != null && descendants.isNotEmpty()) {
                toRemove.add(result)
                for (comp in descendants) {
                    val compJava = comp.removePrefix("L").removeSuffix(";").replace('/', '.')
                    additional.add(result.copy(associatedComponent = compJava))
                }
            }
        }

        results.removeAll(toRemove.toSet())
        results.addAll(additional)
        return results.toList()
    }

    private fun scanMethod(
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        instructions: List<Instruction>
    ) {
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            when {
                isIntentGetExtra(ref) -> handleGetExtra(ref, instructions, i, classDef, method)
                isIntentPutExtra(ref) -> handlePutExtra(ref, instructions, i, classDef, method)
                isBundleGet(ref) -> handleBundleGet(ref, instructions, i, classDef, method)
                isBundlePut(ref) -> handleBundlePut(ref, instructions, i, classDef, method)
                isSetAction(ref) -> handleSetAction(instructions, i)
            }
        }
    }

    // --- Detection methods ---

    private fun isIntentGetExtra(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/Intent;" &&
                ref.name in getExtraTypeMap
    }

    private fun isIntentPutExtra(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/Intent;" &&
                ref.name == "putExtra"
    }

    private fun isBundleGet(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/os/Bundle;" &&
                ref.name.startsWith("get") &&
                ref.parameterTypes.isNotEmpty() &&
                ref.parameterTypes[0] == "Ljava/lang/String;"
    }

    private fun isBundlePut(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/os/Bundle;" &&
                ref.name.startsWith("put") &&
                ref.parameterTypes.isNotEmpty() &&
                ref.parameterTypes[0] == "Ljava/lang/String;"
    }

    private fun isSetAction(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/Intent;" &&
                ref.name == "setAction"
    }

    // --- Handlers ---

    private fun handleGetExtra(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val type = getExtraTypeMap[ref.name] ?: "Unknown"
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg) ?: return

        // Scan forward for value comparisons on the result register
        val values = mutableListOf<String>()
        val resultReg = getMoveResultRegister(instructions, callIndex)
        if (resultReg != null) {
            if (ref.name in stringResultTypes) {
                values.addAll(scanForwardForStringValues(instructions, callIndex + 2, resultReg))
            } else if (ref.name in intResultTypes) {
                values.addAll(scanForwardForIntValues(instructions, callIndex + 2, resultReg))
            }
        }

        addResult(key, type, classDef, method, values)
    }

    private fun handlePutExtra(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val paramSig = ref.parameterTypes.joinToString(",", "(", ")")
        val type = putExtraTypeMap.entries.find { (sig, _) ->
            paramSig.contains(sig.substringAfter("(").substringBefore(")"))
        }?.value ?: "Unknown"

        val instr = instructions[callIndex] as? Instruction35c ?: return
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg) ?: return

        // Pattern A: resolve the value register (registerE) for string/int literals
        val value = when (type) {
            "String" -> resolveStringFromRegister(instructions, callIndex, instr.registerE)
            "Int", "Short", "Byte" -> resolveIntFromRegister(instructions, callIndex, instr.registerE)?.toString()
            "Boolean" -> resolveIntFromRegister(instructions, callIndex, instr.registerE)?.let {
                if (it == 0) "false" else "true"
            }
            else -> null
        }

        addResult(key, type, classDef, method, if (value != null) listOf(value) else emptyList())
    }

    private fun handleBundleGet(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val type = inferTypeFromBundleMethodName(ref.name, "get")
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val key = resolveStringFromRegister(instructions, callIndex, instr.registerD) ?: return

        val values = mutableListOf<String>()
        val resultReg = getMoveResultRegister(instructions, callIndex)
        if (resultReg != null) {
            if (type == "String") {
                values.addAll(scanForwardForStringValues(instructions, callIndex + 2, resultReg))
            } else if (type in listOf("Int", "Short", "Byte")) {
                values.addAll(scanForwardForIntValues(instructions, callIndex + 2, resultReg))
            }
        }

        addResult(key, type, classDef, method, values)
    }

    private fun handleBundlePut(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val type = inferTypeFromBundleMethodName(ref.name, "put")
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val key = resolveStringFromRegister(instructions, callIndex, instr.registerD) ?: return

        val value = when (type) {
            "String" -> resolveStringFromRegister(instructions, callIndex, instr.registerE)
            "Int", "Short", "Byte" -> resolveIntFromRegister(instructions, callIndex, instr.registerE)?.toString()
            "Boolean" -> resolveIntFromRegister(instructions, callIndex, instr.registerE)?.let {
                if (it == 0) "false" else "true"
            }
            else -> null
        }

        addResult(key, type, classDef, method, if (value != null) listOf(value) else emptyList())
    }

    private fun handleSetAction(instructions: List<Instruction>, callIndex: Int) {
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val action = resolveStringFromRegister(instructions, callIndex, instr.registerD)
        if (action != null) {
            discoveredActions.add(action)
        }
    }

    // --- Forward scanning for values ---

    /**
     * After a getStringExtra/getSerializableExtra call, scan forward for:
     * - String.equals(resultReg, constString) / String.equalsIgnoreCase(...)
     * - TextUtils.equals(resultReg, constString) or (constString, resultReg)
     */
    private fun scanForwardForStringValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int
    ): List<String> {
        val values = mutableSetOf<String>()
        val limit = minOf(instructions.size, startIndex + 40)

        for (i in startIndex until limit) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue
            if (instr !is Instruction35c) continue

            when {
                // String.equals(Object) or String.equalsIgnoreCase(String)
                ref.definingClass == "Ljava/lang/String;" &&
                        (ref.name == "equals" || ref.name == "equalsIgnoreCase") -> {
                    if (instr.registerC == resultReg) {
                        // resultReg.equals(vD) — resolve vD
                        resolveStringFromRegister(instructions, i, instr.registerD)?.let {
                            values.add(it)
                        }
                    } else if (instr.registerD == resultReg) {
                        // someConst.equals(resultReg) — resolve someConst (registerC)
                        resolveStringFromRegister(instructions, i, instr.registerC)?.let {
                            values.add(it)
                        }
                    }
                }
                // TextUtils.equals(CharSequence, CharSequence)
                ref.definingClass == "Landroid/text/TextUtils;" && ref.name == "equals" -> {
                    if (instr.registerC == resultReg) {
                        resolveStringFromRegister(instructions, i, instr.registerD)?.let {
                            values.add(it)
                        }
                    } else if (instr.registerD == resultReg) {
                        resolveStringFromRegister(instructions, i, instr.registerC)?.let {
                            values.add(it)
                        }
                    }
                }
            }
        }
        return values.toList()
    }

    /**
     * After a getIntExtra call, scan forward for packed-switch or sparse-switch
     * on the result register.
     */
    private fun scanForwardForIntValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int
    ): List<String> {
        val values = mutableSetOf<String>()
        val limit = minOf(instructions.size, startIndex + 20)

        for (i in startIndex until limit) {
            val instr = instructions[i]

            // packed-switch or sparse-switch on the result register
            if (instr.opcode == Opcode.PACKED_SWITCH || instr.opcode == Opcode.SPARSE_SWITCH) {
                if (instr is OneRegisterInstruction && instr.registerA == resultReg) {
                    // Find the switch payload
                    val payload = findSwitchPayload(instructions, i)
                    if (payload != null) {
                        values.addAll(payload.map { it.toString() })
                    }
                    break
                }
            }

            // Also catch if-eq / if-ne comparisons with constants
            if (instr.opcode == Opcode.IF_EQ || instr.opcode == Opcode.IF_NE) {
                // These are TwoRegisterInstruction with registerA and registerB
                // One register is resultReg, the other holds a const
                if (instr is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) {
                    val otherReg = when {
                        instr.registerA == resultReg -> instr.registerB
                        instr.registerB == resultReg -> instr.registerA
                        else -> continue
                    }
                    resolveIntFromRegister(instructions, i, otherReg)?.let {
                        values.add(it.toString())
                    }
                }
            }
        }
        return values.toList()
    }

    /**
     * Find switch payload values for a packed-switch or sparse-switch instruction.
     */
    private fun findSwitchPayload(instructions: List<Instruction>, switchIndex: Int): List<Int>? {
        // The switch instruction references a payload at an offset
        // In dexlib2, we need to scan for the payload instruction
        for (j in switchIndex + 1 until instructions.size) {
            val payload = instructions[j]
            when (payload) {
                is com.android.tools.smali.dexlib2.iface.instruction.formats.PackedSwitchPayload -> {
                    val elements = payload.switchElements
                    return elements.map { it.key }
                }
                is com.android.tools.smali.dexlib2.iface.instruction.formats.SparseSwitchPayload -> {
                    val elements = payload.switchElements
                    return elements.map { it.key }
                }
            }
        }
        return null
    }

    // --- Helpers ---

    /**
     * Get the register where move-result / move-result-object stores the return value.
     */
    private fun getMoveResultRegister(instructions: List<Instruction>, callIndex: Int): Int? {
        if (callIndex + 1 >= instructions.size) return null
        val next = instructions[callIndex + 1]
        if (next.opcode == Opcode.MOVE_RESULT || next.opcode == Opcode.MOVE_RESULT_OBJECT ||
            next.opcode == Opcode.MOVE_RESULT_WIDE
        ) {
            return (next as? OneRegisterInstruction)?.registerA
        }
        return null
    }

    private fun inferTypeFromBundleMethodName(methodName: String, prefix: String): String {
        val suffix = methodName.removePrefix(prefix)
        return when {
            suffix == "String" -> "String"
            suffix == "Int" || suffix == "Integer" -> "Int"
            suffix == "Long" -> "Long"
            suffix == "Boolean" -> "Boolean"
            suffix == "Float" -> "Float"
            suffix == "Double" -> "Double"
            suffix == "Bundle" -> "Bundle"
            suffix == "Parcelable" -> "Parcelable"
            suffix == "Serializable" -> "Serializable"
            suffix.contains("Array") -> "${suffix.replace("Array", "")}[]"
            else -> suffix.ifEmpty { "Unknown" }
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

    private fun resolveIntFromRegister(
        instructions: List<Instruction>,
        callIndex: Int,
        register: Int
    ): Int? {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 30)) {
            val prev = instructions[j]
            if (prev.opcode in listOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)) {
                if (prev is OneRegisterInstruction && prev.registerA == register &&
                    prev is NarrowLiteralInstruction
                ) {
                    return prev.narrowLiteral
                }
            }
        }
        return null
    }

    private fun addResult(
        key: String,
        type: String,
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        values: List<String> = emptyList()
    ) {
        val uniqueKey = "${classDef.type}:$key:$type"

        // If we've already seen this extra, merge any new values into it
        val existingIdx = resultIndex[uniqueKey]
        if (existingIdx != null) {
            if (values.isNotEmpty()) {
                val existing = results[existingIdx]
                val merged = (existing.possibleValues + values).distinct()
                results[existingIdx] = existing.copy(possibleValues = merged)
            }
            return
        }

        val resolvedComponent = resolveComponent(classDef.type)
        val componentJava = resolvedComponent
            ?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')

        resultIndex[uniqueKey] = results.size
        results.add(
            IntentInfo(
                extraKey = key,
                extraType = type,
                possibleValues = values.distinct(),
                associatedAction = null,
                associatedComponent = componentJava,
                sourceClass = classDef.type,
                sourceMethod = method.name
            )
        )
    }
}
