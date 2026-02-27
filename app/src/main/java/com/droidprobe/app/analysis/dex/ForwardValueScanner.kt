package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.formats.PackedSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.SparseSwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Shared forward-scanning utility for detecting possible values after API calls.
 *
 * Used by both [UriPatternExtractor] (getQueryParameter, Map.get results)
 * and [IntentExtraExtractor] (getXxxExtra results).
 */
object ForwardValueScanner {

    data class ValueScanResult(
        val values: List<String>,
        val detectedType: String?,
        val convertedResultReg: Int?,
        val convertedResultIndex: Int?,
        /** Field reference if the tracked value was stored via iput-object (for field tracking). */
        val storedToField: String? = null
    )

    /**
     * Scan forward from [startIndex] tracking [resultReg] to detect:
     * - String comparisons: equals / equalsIgnoreCase / TextUtils.equals / Objects.equals
     * - String prefix checks: startsWith
     * - Type conversions: Integer.parseInt → type="int", Boolean.parseBoolean → type="boolean"
     * - URI component checks: Uri.getHost/getScheme/getPath → equals (qualified as full URLs)
     *
     * Tracks the register through move-object, check-cast, and method call results.
     * Stops at RETURN/THROW opcodes or when the tracked register is overwritten.
     */
    fun scanStringValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        window: Int = 30
    ): ValueScanResult {
        val values = mutableSetOf<String>()
        var detectedType: String? = null
        var convertedResultReg: Int? = null
        var convertedResultIndex: Int? = null
        var storedToField: String? = null
        var trackedReg = resultReg
        var skipNextMoveResult = false

        val limit = minOf(instructions.size, startIndex + window)
        for (i in startIndex until limit) {
            val instr = instructions[i]

            // Stop at control flow exits
            if (instr.opcode in CONTROL_FLOW_STOPS) break

            // Track move-object: follow the value to its new register
            if (instr.opcode == Opcode.MOVE_OBJECT || instr.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                instr.opcode == Opcode.MOVE_OBJECT_16
            ) {
                if (instr is TwoRegisterInstruction && instr.registerB == trackedReg) {
                    trackedReg = instr.registerA
                }
                continue
            }

            // check-cast: value stays in same register
            if (instr.opcode == Opcode.CHECK_CAST) continue

            // Track through move-result-object after method calls on the tracked register.
            // Follows value through transformations: Optional.ofNullable, Optional.get,
            // String.trim, etc. — including to different result registers.
            // Skipped after Uri component extraction (getHost etc.) to keep tracking the URI.
            if (instr.opcode == Opcode.MOVE_RESULT_OBJECT && instr is OneRegisterInstruction &&
                i > startIndex
            ) {
                if (skipNextMoveResult) {
                    skipNextMoveResult = false
                    continue
                }
                val prev = instructions[i - 1]
                if (prev is Instruction35c && prev.registerC == trackedReg) {
                    trackedReg = instr.registerA
                    continue
                }
            }

            // Record field stores of tracked value (for caller field-tracking pass)
            if (instr.opcode == Opcode.IPUT_OBJECT &&
                instr is TwoRegisterInstruction && instr.registerA == trackedReg &&
                instr is ReferenceInstruction
            ) {
                val ref = instr.reference
                if (ref is FieldReference && storedToField == null) {
                    storedToField = "${ref.definingClass}->${ref.name}:${ref.type}"
                }
                continue
            }

            // Stop if tracked register is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == trackedReg
            ) break

            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            val regStrings = cfgStrings[i] ?: continue

            // Integer.parseInt(String) or Integer.valueOf(String)
            if (ref.definingClass == "Ljava/lang/Integer;" &&
                (ref.name == "parseInt" || ref.name == "valueOf") &&
                ref.parameterTypes.size == 1 && instr.registerC == trackedReg
            ) {
                detectedType = "int"
                val intReg = getMoveResultRegister(instructions, i)
                if (intReg != null) {
                    convertedResultReg = intReg
                    convertedResultIndex = i + 2
                    trackedReg = intReg
                }
                continue
            }

            // Boolean.parseBoolean(String)
            if (ref.definingClass == "Ljava/lang/Boolean;" &&
                ref.name == "parseBoolean" && ref.parameterTypes.size == 1 &&
                instr.registerC == trackedReg
            ) {
                detectedType = "boolean"
                values.addAll(listOf("true", "false"))
                continue
            }

            // Uri.getHost / getScheme / getPath on a parsed Uri register:
            // Do a mini-scan for comparisons on the component result, qualify values,
            // and keep tracking the Uri register for more component calls.
            if (ref.definingClass == "Landroid/net/Uri;" &&
                ref.name in URI_COMPONENT_METHODS &&
                ref.parameterTypes.isEmpty() && instr.registerC == trackedReg
            ) {
                val componentReg = getMoveResultRegister(instructions, i)
                if (componentReg != null) {
                    collectUriComponentValues(
                        values, instructions, i + 2, componentReg, cfgStrings, ref.name
                    )
                    skipNextMoveResult = true
                }
                continue
            }

            // String.equals / equalsIgnoreCase
            if (ref.definingClass == "Ljava/lang/String;" &&
                (ref.name == "equals" || ref.name == "equalsIgnoreCase")
            ) {
                extractComparedValue(instr, trackedReg, regStrings)?.let { values.add(it) }
            }

            // TextUtils.equals
            if (ref.definingClass == "Landroid/text/TextUtils;" && ref.name == "equals") {
                extractComparedValue(instr, trackedReg, regStrings)?.let { values.add(it) }
            }

            // Objects.equals (desugared j$.util.Objects or java.util.Objects) — 2-param static
            if (ref.name == "equals" && ref.parameterTypes.size == 2 &&
                ref.definingClass.endsWith("/Objects;")
            ) {
                extractComparedValue(instr, trackedReg, regStrings)?.let { values.add(it) }
            }

            // String.startsWith
            if (ref.definingClass == "Ljava/lang/String;" && ref.name == "startsWith" &&
                ref.parameterTypes.size == 1 && instr.registerC == trackedReg
            ) {
                regStrings[instr.registerD]?.let { values.add(it) }
            }
        }

        return ValueScanResult(values.toList(), detectedType, convertedResultReg, convertedResultIndex, storedToField)
    }

    /**
     * Scan forward from [startIndex] for integer value comparisons on [resultReg]:
     * - packed-switch / sparse-switch → extract all case keys
     * - if-eq / if-ne vs int constant → extract compared value
     */
    fun scanIntValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int,
        window: Int = 20
    ): List<String> {
        val values = mutableSetOf<String>()
        val limit = minOf(instructions.size, startIndex + window)

        for (i in startIndex until limit) {
            val instr = instructions[i]

            // packed-switch / sparse-switch on result register
            if (instr.opcode == Opcode.PACKED_SWITCH || instr.opcode == Opcode.SPARSE_SWITCH) {
                if (instr is OneRegisterInstruction && instr.registerA == resultReg) {
                    findSwitchPayload(instructions, i)?.let { keys ->
                        values.addAll(keys.map { it.toString() })
                    }
                    break
                }
            }

            // if-eq / if-ne comparisons with int constants
            if (instr.opcode == Opcode.IF_EQ || instr.opcode == Opcode.IF_NE) {
                if (instr is TwoRegisterInstruction) {
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

            // if-eqz / if-nez: implicit comparison to 0
            if (instr.opcode == Opcode.IF_EQZ || instr.opcode == Opcode.IF_NEZ) {
                if (instr is OneRegisterInstruction && instr.registerA == resultReg) {
                    values.add("0")
                }
            }
        }
        return values.toList()
    }

    /**
     * Backward scan to resolve an int constant loaded into [register]
     * at or before [fromIndex].
     */
    fun resolveIntFromRegister(
        instructions: List<Instruction>,
        fromIndex: Int,
        register: Int,
        window: Int = 30
    ): Int? {
        for (j in fromIndex - 1 downTo maxOf(0, fromIndex - window)) {
            val prev = instructions[j]
            if (prev.opcode in CONST_INT_OPCODES &&
                prev is OneRegisterInstruction && prev.registerA == register &&
                prev is NarrowLiteralInstruction
            ) {
                return prev.narrowLiteral
            }
        }
        return null
    }

    /** Get move-result / move-result-object / move-result-wide register after a call. */
    fun getMoveResultRegister(instructions: List<Instruction>, callIndex: Int): Int? {
        if (callIndex + 1 >= instructions.size) return null
        val next = instructions[callIndex + 1]
        if (next.opcode == Opcode.MOVE_RESULT || next.opcode == Opcode.MOVE_RESULT_OBJECT ||
            next.opcode == Opcode.MOVE_RESULT_WIDE
        ) {
            return (next as? OneRegisterInstruction)?.registerA
        }
        return null
    }

    /**
     * Follow an int register into a called method and scan for int comparisons there.
     * Detects patterns like: invoke-static {intReg}, Lsome/Enum;->fromValue(I)Lsome/Enum;
     * and scans the target method for if-eqz/if-eq/switch patterns on the parameter.
     */
    fun resolveEnumValues(
        classIndex: Map<String, DexBackedClassDef>,
        instructions: List<Instruction>,
        startIndex: Int,
        intReg: Int,
        window: Int = 10
    ): List<String> {
        val limit = minOf(instructions.size, startIndex + window)
        for (i in startIndex until limit) {
            val instr = instructions[i]

            // Stop at control flow exits
            if (instr.opcode in CONTROL_FLOW_STOPS) break

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            // Skip framework classes
            if (isFrameworkClass(ref.definingClass)) continue

            // Find which argument position intReg occupies
            val argIndex = when (instr) {
                is Instruction35c -> {
                    when {
                        instr.registerCount >= 1 && instr.registerC == intReg -> 0
                        instr.registerCount >= 2 && instr.registerD == intReg -> 1
                        instr.registerCount >= 3 && instr.registerE == intReg -> 2
                        instr.registerCount >= 4 && instr.registerF == intReg -> 3
                        instr.registerCount >= 5 && instr.registerG == intReg -> 4
                        else -> continue
                    }
                }
                is Instruction3rc -> {
                    val offset = intReg - instr.startRegister
                    if (offset in 0 until instr.registerCount) offset else continue
                }
                else -> continue
            }

            // For virtual/interface calls, arg 0 is 'this' — the actual params start at index 1
            val isStatic = instr.opcode == Opcode.INVOKE_STATIC ||
                    instr.opcode == Opcode.INVOKE_STATIC_RANGE
            val paramIndex = if (isStatic) argIndex else argIndex - 1
            if (paramIndex < 0) continue // intReg is 'this', not a parameter

            // Verify the parameter at paramIndex is an int type
            if (paramIndex >= ref.parameterTypes.size) continue
            val paramType = ref.parameterTypes[paramIndex].toString()
            if (paramType != "I" && paramType != "S" && paramType != "B") continue

            // Resolve the target method
            val targetClass = classIndex[ref.definingClass] ?: continue
            val targetMethod = targetClass.methods.find { m ->
                m.name == ref.name &&
                        m.parameterTypes.size == ref.parameterTypes.size &&
                        m.parameterTypes.zip(ref.parameterTypes).all { (a, b) -> a.toString() == b.toString() }
            } ?: continue
            val impl = targetMethod.implementation ?: continue

            // Compute the parameter's register in the target method
            // Register layout: [locals...] [params...] where params include 'this' for virtual
            val targetIsStatic = targetMethod.accessFlags and 0x0008 != 0 // ACC_STATIC
            val paramSlotOffset = if (targetIsStatic) 0 else 1 // skip 'this' slot
            var regOffset = paramSlotOffset
            for (p in 0 until paramIndex) {
                val pType = ref.parameterTypes[p].toString()
                regOffset += if (pType == "J" || pType == "D") 2 else 1
            }
            val paramReg = impl.registerCount - computeParamSize(ref, targetIsStatic) + regOffset

            val targetInstructions = impl.instructions.toList()
            val result = scanIntValues(targetInstructions, 0, paramReg, window = 30)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    // --- Private helpers ---

    /** Extract the string constant being compared to [trackedReg] in an equals call. */
    private fun extractComparedValue(
        instr: Instruction35c,
        trackedReg: Int,
        regStrings: Map<Int, String>
    ): String? {
        return when {
            instr.registerC == trackedReg -> regStrings[instr.registerD]
            instr.registerD == trackedReg -> regStrings[instr.registerC]
            else -> null
        }
    }

    /**
     * Mini-scan for equals comparisons on a Uri component register (from getHost/getScheme/getPath).
     * Searches within a short window for all comparisons involving [componentReg],
     * qualifies them based on the component type, and adds to [values].
     */
    private fun collectUriComponentValues(
        values: MutableSet<String>,
        instructions: List<Instruction>,
        startIndex: Int,
        componentReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        componentName: String
    ) {
        val limit = minOf(instructions.size, startIndex + 20)
        for (i in startIndex until limit) {
            val instr = instructions[i]
            if (instr.opcode in CONTROL_FLOW_STOPS) break
            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            val regStrings = cfgStrings[i] ?: continue

            // String.equals / equalsIgnoreCase
            if (ref.definingClass == "Ljava/lang/String;" &&
                (ref.name == "equals" || ref.name == "equalsIgnoreCase")
            ) {
                extractComparedValue(instr, componentReg, regStrings)?.let {
                    values.add(qualifyUriComponent(it, componentName))
                }
            }

            // TextUtils.equals
            if (ref.definingClass == "Landroid/text/TextUtils;" && ref.name == "equals") {
                extractComparedValue(instr, componentReg, regStrings)?.let {
                    values.add(qualifyUriComponent(it, componentName))
                }
            }

            // Objects.equals
            if (ref.name == "equals" && ref.parameterTypes.size == 2 &&
                ref.definingClass.endsWith("/Objects;")
            ) {
                extractComparedValue(instr, componentReg, regStrings)?.let {
                    values.add(qualifyUriComponent(it, componentName))
                }
            }
        }
    }

    /** Qualify a raw comparison value based on which Uri component it came from. */
    private fun qualifyUriComponent(value: String, componentName: String): String {
        return when (componentName) {
            "getHost" -> "https://$value/"
            "getScheme" -> "$value://"
            else -> value
        }
    }

    /** Find switch payload values for a packed-switch or sparse-switch instruction. */
    private fun findSwitchPayload(instructions: List<Instruction>, switchIndex: Int): List<Int>? {
        for (j in switchIndex + 1 until instructions.size) {
            val payload = instructions[j]
            when (payload) {
                is PackedSwitchPayload -> return payload.switchElements.map { it.key }
                is SparseSwitchPayload -> return payload.switchElements.map { it.key }
            }
        }
        return null
    }

    private val CONTROL_FLOW_STOPS = setOf(
        Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE,
        Opcode.THROW
    )

    private val URI_COMPONENT_METHODS = setOf("getHost", "getScheme", "getPath")

    private val CONST_INT_OPCODES = listOf(
        Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16
    )

    /** Compute total parameter register slots for a method reference (including 'this' for virtual). */
    private fun computeParamSize(ref: MethodReference, isStatic: Boolean): Int {
        var size = if (isStatic) 0 else 1
        for (pType in ref.parameterTypes) {
            val t = pType.toString()
            size += if (t == "J" || t == "D") 2 else 1
        }
        return size
    }

    private fun isFrameworkClass(type: String): Boolean {
        return type.startsWith("Ljava/") || type.startsWith("Landroid/") ||
                type.startsWith("Lkotlin/") || type.startsWith("Ldalvik/") ||
                type.startsWith("Landroidx/") || type.startsWith("Lkotlinx/")
    }
}
