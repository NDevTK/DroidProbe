package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
    private val componentClasses: Set<String>,
    private val classIndex: Map<String, DexBackedClassDef> = emptyMap()
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
            scanMethod(classDef, method, impl, instructions)
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
        impl: MethodImplementation,
        instructions: List<Instruction>
    ) {
        // Build CFG and compute string register state via forward dataflow
        val cfg = MethodCFG(instructions, impl.tryBlocks)
        val cfgStrings = cfg.computeStringRegisters()

        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            when {
                isIntentGetExtra(ref) -> handleGetExtra(ref, instructions, i, classDef, method, cfgStrings, cfg.successors)
                isIntentPutExtra(ref) -> handlePutExtra(ref, instructions, i, classDef, method, cfgStrings)
                isBundleGet(ref) -> handleBundleGet(ref, instructions, i, classDef, method, cfgStrings, cfg.successors)
                isBundlePut(ref) -> handleBundlePut(ref, instructions, i, classDef, method, cfgStrings)
                isSetAction(ref) -> handleSetAction(instructions, i, cfgStrings)
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
        method: DexBackedMethod,
        cfgStrings: Array<Map<Int, String>?>,
        successors: Array<IntArray>
    ) {
        val type = getExtraTypeMap[ref.name] ?: "Unknown"
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val keyReg = instr.registerD
        val key = cfgStrings[callIndex]?.get(keyReg) ?: return

        // Scan forward for value comparisons on the result register
        val values = mutableListOf<String>()
        val resultReg = ForwardValueScanner.getMoveResultRegister(instructions, callIndex)

        DexDebugLog.logFiltered(classDef.type, key,
            "[IntentExtra] ${ref.name}(\"$key\") type=$type at index=$callIndex " +
            "resultReg=${resultReg?.let { "v$it" } ?: "none"} " +
            "class=${classDef.type} method=${method.name}")

        if (resultReg != null) {
            if (ref.name in stringResultTypes) {
                val scan = ForwardValueScanner.scanStringValues(instructions, callIndex + 2, resultReg, cfgStrings, successors)
                values.addAll(scan.values)
                // Chain: if parseInt detected on string result, scan for int values + enum
                if (scan.detectedType == "int" && scan.convertedResultReg != null && scan.convertedResultIndex != null) {
                    values.addAll(ForwardValueScanner.scanIntValues(instructions, scan.convertedResultIndex, scan.convertedResultReg, successors))
                    values.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, scan.convertedResultIndex, scan.convertedResultReg, successors))
                }
                // Inter-procedural: follow string extra into called methods
                if (values.isEmpty()) {
                    values.addAll(ForwardValueScanner.resolveStringEnumValues(
                        classIndex, instructions, callIndex + 2, resultReg, cfgStrings, successors
                    ))
                }
            } else if (ref.name in intResultTypes) {
                values.addAll(ForwardValueScanner.scanIntValues(instructions, callIndex + 2, resultReg, successors))
                values.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, callIndex + 2, resultReg, successors))
            }
        }

        addResult(key, type, classDef, method, values)
    }

    private fun handlePutExtra(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        val paramSig = ref.parameterTypes.joinToString("", "(", ")")
        val type = putExtraTypeMap.entries.find { (sig, _) ->
            paramSig.contains(sig.substringAfter("(").substringBefore(")"))
        }?.value ?: "Unknown"

        val instr = instructions[callIndex] as? Instruction35c ?: return
        val keyReg = instr.registerD
        val regState = cfgStrings[callIndex] ?: return
        val key = regState[keyReg] ?: return

        // Pattern A: resolve the value register (registerE) for string/int literals
        val value = when (type) {
            "String" -> regState[instr.registerE]
            "Int", "Short", "Byte" -> ForwardValueScanner.resolveIntFromRegister(instructions, callIndex, instr.registerE)?.toString()
            "Boolean" -> ForwardValueScanner.resolveIntFromRegister(instructions, callIndex, instr.registerE)?.let {
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
        method: DexBackedMethod,
        cfgStrings: Array<Map<Int, String>?>,
        successors: Array<IntArray>
    ) {
        val type = inferTypeFromBundleMethodName(ref.name, "get")
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val key = cfgStrings[callIndex]?.get(instr.registerD) ?: return

        val values = mutableListOf<String>()
        val resultReg = ForwardValueScanner.getMoveResultRegister(instructions, callIndex)
        if (resultReg != null) {
            if (type == "String") {
                val scan = ForwardValueScanner.scanStringValues(instructions, callIndex + 2, resultReg, cfgStrings, successors)
                values.addAll(scan.values)
                if (scan.detectedType == "int" && scan.convertedResultReg != null && scan.convertedResultIndex != null) {
                    values.addAll(ForwardValueScanner.scanIntValues(instructions, scan.convertedResultIndex, scan.convertedResultReg, successors))
                    values.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, scan.convertedResultIndex, scan.convertedResultReg, successors))
                }
                if (values.isEmpty()) {
                    values.addAll(ForwardValueScanner.resolveStringEnumValues(
                        classIndex, instructions, callIndex + 2, resultReg, cfgStrings, successors
                    ))
                }
            } else if (type in listOf("Int", "Short", "Byte")) {
                values.addAll(ForwardValueScanner.scanIntValues(instructions, callIndex + 2, resultReg, successors))
                values.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, callIndex + 2, resultReg, successors))
            }
        }

        addResult(key, type, classDef, method, values)
    }

    private fun handleBundlePut(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        cfgStrings: Array<Map<Int, String>?>
    ) {
        val type = inferTypeFromBundleMethodName(ref.name, "put")
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val regState = cfgStrings[callIndex] ?: return
        val key = regState[instr.registerD] ?: return

        val value = when (type) {
            "String" -> regState[instr.registerE]
            "Int", "Short", "Byte" -> ForwardValueScanner.resolveIntFromRegister(instructions, callIndex, instr.registerE)?.toString()
            "Boolean" -> ForwardValueScanner.resolveIntFromRegister(instructions, callIndex, instr.registerE)?.let {
                if (it == 0) "false" else "true"
            }
            else -> null
        }

        addResult(key, type, classDef, method, if (value != null) listOf(value) else emptyList())
    }

    private fun handleSetAction(instructions: List<Instruction>, callIndex: Int, cfgStrings: Array<Map<Int, String>?>) {
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val action = cfgStrings[callIndex]?.get(instr.registerD)
        if (action != null) {
            discoveredActions.add(action)
        }
    }

    // --- Helpers ---

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
                DexDebugLog.logFiltered(classDef.type, key,
                    "[IntentExtra] MERGE extra \"$key\" ($type): +$values → merged=${merged}")
            }
            return
        }

        val resolvedComponent = resolveComponent(classDef.type)
        val componentJava = resolvedComponent
            ?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')

        DexDebugLog.logFiltered(classDef.type, key,
            "[IntentExtra] ADD extra \"$key\" ($type) values=$values " +
            "component=$componentJava source=${classDef.type}::${method.name}")

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
