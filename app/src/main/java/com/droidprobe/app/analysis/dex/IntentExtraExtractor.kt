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
import com.droidprobe.app.data.model.IntentInfo

/**
 * Extracts intent extra keys and types from DEX bytecode by scanning for:
 * - Intent.putExtra(key, value) — all overloads
 * - Intent.getXxxExtra(key) — getStringExtra, getIntExtra, etc.
 * - Bundle.putXxx(key, value) and Bundle.getXxx(key)
 * - Intent.setAction(action) and Intent(action) constructor
 *
 * Uses the class hierarchy to resolve which exported component each extra
 * actually belongs to — traces inheritance, not name guessing.
 */
class IntentExtraExtractor(
    private val classHierarchy: Map<String, String>,  // class -> superclass (smali types)
    private val componentClasses: Set<String>          // known exported components (smali types)
) {

    private val results = mutableListOf<IntentInfo>()
    private val seenKeys = mutableSetOf<String>()
    private val discoveredActions = mutableSetOf<String>()

    // Cache: smali class type -> resolved component smali type (or null)
    private val componentResolutionCache = mutableMapOf<String, String?>()

    // Maps method name → extra type for get*Extra methods
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

    // Maps putExtra method signatures to extra type
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

    /**
     * Resolve which exported component a class belongs to by walking up
     * the inheritance chain. Also handles inner/anonymous classes by
     * checking the outer class.
     *
     * Lcom/example/LoginActivity; → itself (if it's a component)
     * Lcom/example/LoginActivity$1; → Lcom/example/LoginActivity;
     * Lcom/example/BaseActivity; → any subclass that's a component? No — we
     *   reverse-map: for each class, walk UP to find if it IS or extends a component.
     *
     * Actually we need the reverse: if extras are in BaseActivity, they belong
     * to ALL components that extend BaseActivity. We handle this by building
     * a subclass map and checking descendants.
     */
    fun resolveComponent(smaliType: String): String? {
        componentResolutionCache[smaliType]?.let { return it }

        // 1. Direct: this class IS a component
        if (smaliType in componentClasses) {
            componentResolutionCache[smaliType] = smaliType
            return smaliType
        }

        // 2. Inner/anonymous class: Lcom/example/Foo$Bar; → check Lcom/example/Foo;
        val dollarIdx = smaliType.indexOf('$')
        if (dollarIdx > 0) {
            val outer = smaliType.substring(0, dollarIdx) + ";"
            val resolved = resolveComponent(outer)
            if (resolved != null) {
                componentResolutionCache[smaliType] = resolved
                return resolved
            }
        }

        // 3. Walk UP the inheritance chain — if this class extends a component
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

    /**
     * For classes that are superclasses of components (e.g. BaseActivity),
     * find ALL component descendants. Called after all classes are processed.
     */
    fun resolveSuperclassExtras(): List<IntentInfo> {
        // Build reverse map: superclass -> list of component subclasses
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

        // For any result where associatedComponent is null but sourceClass
        // is an ancestor of components, duplicate for each descendant component
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
                // Intent.get*Extra(key)
                isIntentGetExtra(ref) -> {
                    handleGetExtra(ref, instructions, i, classDef, method)
                }
                // Intent.putExtra(key, value)
                isIntentPutExtra(ref) -> {
                    handlePutExtra(ref, instructions, i, classDef, method)
                }
                // Bundle.get*(key)
                isBundleGet(ref) -> {
                    handleBundleGet(ref, instructions, i, classDef, method)
                }
                // Bundle.put*(key, value)
                isBundlePut(ref) -> {
                    handleBundlePut(ref, instructions, i, classDef, method)
                }
                // Intent.setAction(action)
                isSetAction(ref) -> {
                    handleSetAction(instructions, i, classDef, method)
                }
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
        // For instance methods: registerC = this (Intent), registerD = key string
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg)

        if (key != null) {
            addResult(key, type, classDef, method)
        }
    }

    private fun handlePutExtra(
        ref: MethodReference,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        // Determine type from method signature
        val paramSig = ref.parameterTypes.joinToString(",", "(", ")")
        val type = putExtraTypeMap.entries.find { (sig, _) ->
            paramSig.contains(sig.substringAfter("(").substringBefore(")"))
        }?.value ?: "Unknown"

        val instr = instructions[callIndex] as? Instruction35c ?: return
        // registerC = this (Intent), registerD = key
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg)

        if (key != null) {
            addResult(key, type, classDef, method)
        }
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
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg)

        if (key != null) {
            addResult(key, type, classDef, method)
        }
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
        val keyReg = instr.registerD
        val key = resolveStringFromRegister(instructions, callIndex, keyReg)

        if (key != null) {
            addResult(key, type, classDef, method)
        }
    }

    private fun handleSetAction(
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val instr = instructions[callIndex] as? Instruction35c ?: return
        val actionReg = instr.registerD
        val action = resolveStringFromRegister(instructions, callIndex, actionReg)
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
        key: String,
        type: String,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        // Include source class in dedup key — same extra in different classes = separate entries
        val uniqueKey = "${classDef.type}:$key:$type"
        if (uniqueKey in seenKeys) return
        seenKeys.add(uniqueKey)

        // Resolve which component this class belongs to via inheritance
        val resolvedComponent = resolveComponent(classDef.type)
        val componentJava = resolvedComponent
            ?.removePrefix("L")?.removeSuffix(";")?.replace('/', '.')

        results.add(
            IntentInfo(
                extraKey = key,
                extraType = type,
                associatedAction = null,
                associatedComponent = componentJava,
                sourceClass = classDef.type,
                sourceMethod = method.name
            )
        )
    }
}
