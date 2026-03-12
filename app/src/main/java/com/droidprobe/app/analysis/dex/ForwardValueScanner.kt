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
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.PackedSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.SparseSwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * CFG-aware forward-scanning utility for detecting possible values after API calls.
 *
 * Uses [MethodCFG] successor edges to walk all reachable instructions from a
 * starting point, following actual control flow rather than a fixed instruction
 * window. This eliminates arbitrary window limits and correctly handles branches,
 * loops, and non-linear control flow.
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
     * BFS state: instruction index + which register currently holds the tracked value.
     * Different CFG paths may rename the register (move-object, move-result-object),
     * so each path carries its own tracked register.
     */
    private data class RegState(val index: Int, val reg: Int)

    /**
     * Scan forward from [startIndex] using CFG [successors] edges, tracking [resultReg]
     * through all reachable paths to detect:
     * - String comparisons: equals / equalsIgnoreCase / TextUtils.equals / Objects.equals / Intrinsics.areEqual
     * - String prefix checks: startsWith
     * - Type conversions: Integer.parseInt → type="int", Boolean.parseBoolean → type="boolean"
     * - URI component checks: Uri.getHost/getScheme/getPath → equals (qualified as full URLs)
     *
     * Tracks the register through move-object, check-cast, and method call results.
     * Each CFG path stops when the tracked register is overwritten; terminal
     * instructions (RETURN/THROW) have no successors and stop naturally.
     */
    fun scanStringValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        successors: Array<IntArray>
    ): ValueScanResult {
        val values = mutableSetOf<String>()
        var detectedType: String? = null
        var convertedResultReg: Int? = null
        var convertedResultIndex: Int? = null
        var storedToField: String? = null

        val queue = ArrayDeque<RegState>()
        val visited = mutableSetOf<RegState>()

        if (startIndex in instructions.indices) {
            val initial = RegState(startIndex, resultReg)
            queue.add(initial)
            visited.add(initial)
        }

        while (queue.isNotEmpty()) {
            val (i, trackedReg) = queue.removeFirst()
            val instr = instructions[i]
            var nextReg = trackedReg
            var enqueue = true

            // Track move-object: follow the value to its new register
            if (instr.opcode == Opcode.MOVE_OBJECT || instr.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                instr.opcode == Opcode.MOVE_OBJECT_16
            ) {
                if (instr is TwoRegisterInstruction && instr.registerB == trackedReg) {
                    nextReg = instr.registerA
                }
                enqueueRegSuccessors(queue, visited, successors, i, nextReg)
                continue
            }

            // check-cast: value stays in same register
            if (instr.opcode == Opcode.CHECK_CAST) {
                enqueueRegSuccessors(queue, visited, successors, i, trackedReg)
                continue
            }

            // Track through move-result-object after method calls on the tracked register.
            // Skip update if the previous instruction was a Uri component call or hashCode
            // (we want to keep tracking the original Uri/String register).
            if (instr.opcode == Opcode.MOVE_RESULT_OBJECT && instr is OneRegisterInstruction && i > 0) {
                val prev = instructions[i - 1]
                val skipUpdate = prev is ReferenceInstruction && run {
                    val ref = prev.reference
                    ref is MethodReference && (
                        (ref.definingClass == "Landroid/net/Uri;" &&
                            ref.name in URI_COMPONENT_METHODS && ref.parameterTypes.isEmpty()) ||
                        (ref.definingClass == "Ljava/lang/String;" &&
                            ref.name == "hashCode" && ref.parameterTypes.isEmpty())
                    )
                }
                if (!skipUpdate && prev is Instruction35c && prev.registerC == trackedReg) {
                    nextReg = instr.registerA
                }
                enqueueRegSuccessors(queue, visited, successors, i, nextReg)
                continue
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
                enqueueRegSuccessors(queue, visited, successors, i, trackedReg)
                continue
            }

            // Stop THIS PATH if tracked register is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == trackedReg
            ) {
                // Don't enqueue successors — register is dead on this path
                continue
            }

            // Process method calls for value detection
            if (instr is ReferenceInstruction && instr is Instruction35c) {
                val ref = instr.reference
                if (ref is MethodReference) {
                    val regStrings = cfgStrings[i]
                    if (regStrings != null) {
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
                                nextReg = intReg
                            }
                        }

                        // Boolean.parseBoolean(String)
                        if (ref.definingClass == "Ljava/lang/Boolean;" &&
                            ref.name == "parseBoolean" && ref.parameterTypes.size == 1 &&
                            instr.registerC == trackedReg
                        ) {
                            detectedType = "boolean"
                            values.addAll(listOf("true", "false"))
                        }

                        // Uri.getHost / getScheme / getPath on a parsed Uri register
                        if (ref.definingClass == "Landroid/net/Uri;" &&
                            ref.name in URI_COMPONENT_METHODS &&
                            ref.parameterTypes.isEmpty() && instr.registerC == trackedReg
                        ) {
                            val componentReg = getMoveResultRegister(instructions, i)
                            if (componentReg != null) {
                                collectUriComponentValues(
                                    values, instructions, i + 2, componentReg, cfgStrings, ref.name, successors
                                )
                            }
                            // Keep tracking the Uri register (nextReg unchanged)
                        }

                        // String.hashCode() — compiled string switch: hashCode → switch → equals branches
                        if (ref.definingClass == "Ljava/lang/String;" && ref.name == "hashCode" &&
                            ref.parameterTypes.isEmpty() && instr.registerC == trackedReg
                        ) {
                            values.addAll(scanStringSwitchValues(instructions, i + 1, trackedReg, cfgStrings))
                            // Switch values collected exhaustively from entire method — stop this path
                            enqueue = false
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

                        // Kotlin == compiles to Intrinsics.areEqual(Object, Object) — 2-param static
                        if (ref.name == "areEqual" && ref.parameterTypes.size == 2 &&
                            ref.definingClass == "Lkotlin/jvm/internal/Intrinsics;"
                        ) {
                            extractComparedValue(instr, trackedReg, regStrings)?.let { values.add(it) }
                        }

                        // String.startsWith (Java direct call)
                        if (ref.definingClass == "Ljava/lang/String;" && ref.name == "startsWith" &&
                            ref.parameterTypes.size == 1 && instr.registerC == trackedReg
                        ) {
                            regStrings[instr.registerD]?.let { values.add(it) }
                        }
                        // Kotlin StringsKt.startsWith$default(self, prefix, ignoreCase, flags, handler)
                        if (ref.definingClass == "Lkotlin/text/StringsKt;" &&
                            ref.name == "startsWith\$default" &&
                            instr.registerC == trackedReg
                        ) {
                            regStrings[instr.registerD]?.let { values.add(it) }
                        }
                    }
                }
            }

            // Enqueue successors with current tracked register
            if (enqueue) {
                enqueueRegSuccessors(queue, visited, successors, i, nextReg)
            }
        }

        return ValueScanResult(values.toList(), detectedType, convertedResultReg, convertedResultIndex, storedToField)
    }

    /**
     * Scan forward from [startIndex] using CFG [successors] for integer value
     * comparisons on [resultReg]:
     * - packed-switch / sparse-switch → extract all case keys
     * - if-eq / if-ne vs int constant → extract compared value
     *
     * Stops each path when the result register is overwritten.
     */
    fun scanIntValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int,
        successors: Array<IntArray>
    ): List<String> {
        val values = mutableSetOf<String>()
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()

        if (startIndex in instructions.indices) {
            queue.add(startIndex)
            visited.add(startIndex)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val instr = instructions[i]

            // Stop this path if result register is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == resultReg
            ) {
                continue
            }

            // packed-switch / sparse-switch on result register
            if (instr.opcode == Opcode.PACKED_SWITCH || instr.opcode == Opcode.SPARSE_SWITCH) {
                if (instr is OneRegisterInstruction && instr.registerA == resultReg) {
                    findSwitchPayload(instructions, i)?.let { keys ->
                        values.addAll(keys.map { it.toString() })
                    }
                    // Found switch — don't continue on this path
                    continue
                }
            }

            // if-eq / if-ne comparisons with int constants
            if (instr.opcode == Opcode.IF_EQ || instr.opcode == Opcode.IF_NE) {
                if (instr is TwoRegisterInstruction) {
                    val otherReg = when {
                        instr.registerA == resultReg -> instr.registerB
                        instr.registerB == resultReg -> instr.registerA
                        else -> null
                    }
                    if (otherReg != null) {
                        resolveIntFromRegister(instructions, i, otherReg)?.let {
                            values.add(it.toString())
                        }
                    }
                }
            }

            // if-eqz / if-nez: implicit comparison to 0
            if (instr.opcode == Opcode.IF_EQZ || instr.opcode == Opcode.IF_NEZ) {
                if (instr is OneRegisterInstruction && instr.registerA == resultReg) {
                    values.add("0")
                }
            }

            // Enqueue successors
            for (s in successors[i]) {
                if (visited.add(s)) queue.add(s)
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
     *
     * Uses CFG [successors] to walk reachable instructions from [startIndex] to find
     * the call site, then builds a CFG for the target method for the inner scan.
     */
    fun resolveEnumValues(
        classIndex: Map<String, DexBackedClassDef>,
        instructions: List<Instruction>,
        startIndex: Int,
        intReg: Int,
        successors: Array<IntArray>
    ): List<String> {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()

        if (startIndex in instructions.indices) {
            queue.add(startIndex)
            visited.add(startIndex)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val instr = instructions[i]

            // Stop this path if intReg is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == intReg
            ) {
                continue
            }

            if (instr !is ReferenceInstruction) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val ref = instr.reference
            if (ref !is MethodReference) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Skip framework classes
            if (isFrameworkClass(ref.definingClass)) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Find which argument position intReg occupies
            val argIndex = when (instr) {
                is Instruction35c -> {
                    when {
                        instr.registerCount >= 1 && instr.registerC == intReg -> 0
                        instr.registerCount >= 2 && instr.registerD == intReg -> 1
                        instr.registerCount >= 3 && instr.registerE == intReg -> 2
                        instr.registerCount >= 4 && instr.registerF == intReg -> 3
                        instr.registerCount >= 5 && instr.registerG == intReg -> 4
                        else -> {
                            for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                            continue
                        }
                    }
                }
                is Instruction3rc -> {
                    val offset = intReg - instr.startRegister
                    if (offset in 0 until instr.registerCount) offset else {
                        for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                        continue
                    }
                }
                else -> {
                    for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                    continue
                }
            }

            // For virtual/interface calls, arg 0 is 'this' — the actual params start at index 1
            val isStatic = instr.opcode == Opcode.INVOKE_STATIC ||
                    instr.opcode == Opcode.INVOKE_STATIC_RANGE
            val paramIndex = if (isStatic) argIndex else argIndex - 1
            if (paramIndex < 0) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Verify the parameter at paramIndex is an int type
            if (paramIndex >= ref.parameterTypes.size) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val paramType = ref.parameterTypes[paramIndex].toString()
            if (paramType != "I" && paramType != "S" && paramType != "B") {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Resolve the target method
            val targetClass = classIndex[ref.definingClass]
            if (targetClass == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val targetMethod = targetClass.methods.find { m ->
                m.name == ref.name &&
                        m.parameterTypes.size == ref.parameterTypes.size &&
                        m.parameterTypes.zip(ref.parameterTypes).all { (a, b) -> a.toString() == b.toString() }
            }
            if (targetMethod == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val impl = targetMethod.implementation
            if (impl == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Compute the parameter's register in the target method
            val targetIsStatic = targetMethod.accessFlags and 0x0008 != 0 // ACC_STATIC
            val paramSlotOffset = if (targetIsStatic) 0 else 1 // skip 'this' slot
            var regOffset = paramSlotOffset
            for (p in 0 until paramIndex) {
                val pType = ref.parameterTypes[p].toString()
                regOffset += if (pType == "J" || pType == "D") 2 else 1
            }
            val paramReg = impl.registerCount - computeParamSize(ref, targetIsStatic) + regOffset

            val targetInstructions = impl.instructions.toList()
            val targetCfg = MethodCFG(targetInstructions, impl.tryBlocks)
            val result = scanIntValues(targetInstructions, 0, paramReg, targetCfg.successors)
            if (result.isNotEmpty()) return result

            // Continue BFS to find other potential call sites
            for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
        }
        return emptyList()
    }

    /**
     * Follow a string register into a called method and scan for string comparisons there.
     * Mirrors [resolveEnumValues] but for String parameters — detects equals() comparisons
     * and string switch (hashCode + equals) patterns in the target method.
     *
     * Uses CFG [successors] to walk reachable instructions from [startIndex] to find
     * the call site, then builds a CFG for the target method for the inner scan.
     */
    fun resolveStringEnumValues(
        classIndex: Map<String, DexBackedClassDef>,
        instructions: List<Instruction>,
        startIndex: Int,
        stringReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        successors: Array<IntArray>
    ): List<String> {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()

        if (startIndex in instructions.indices) {
            queue.add(startIndex)
            visited.add(startIndex)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val instr = instructions[i]

            // Stop this path if stringReg is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == stringReg
            ) {
                continue
            }

            if (instr !is ReferenceInstruction) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val ref = instr.reference
            if (ref !is MethodReference) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            if (isFrameworkClass(ref.definingClass)) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            // Find which argument position stringReg occupies
            val argIndex = when (instr) {
                is Instruction35c -> {
                    when {
                        instr.registerCount >= 1 && instr.registerC == stringReg -> 0
                        instr.registerCount >= 2 && instr.registerD == stringReg -> 1
                        instr.registerCount >= 3 && instr.registerE == stringReg -> 2
                        instr.registerCount >= 4 && instr.registerF == stringReg -> 3
                        instr.registerCount >= 5 && instr.registerG == stringReg -> 4
                        else -> {
                            for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                            continue
                        }
                    }
                }
                is Instruction3rc -> {
                    val offset = stringReg - instr.startRegister
                    if (offset in 0 until instr.registerCount) offset else {
                        for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                        continue
                    }
                }
                else -> {
                    for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                    continue
                }
            }

            val isStatic = instr.opcode == Opcode.INVOKE_STATIC ||
                    instr.opcode == Opcode.INVOKE_STATIC_RANGE
            val paramIndex = if (isStatic) argIndex else argIndex - 1
            if (paramIndex < 0) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            if (paramIndex >= ref.parameterTypes.size) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val paramType = ref.parameterTypes[paramIndex].toString()
            if (paramType != "Ljava/lang/String;") {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            val targetClass = classIndex[ref.definingClass]
            if (targetClass == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val targetMethod = targetClass.methods.find { m ->
                m.name == ref.name &&
                        m.parameterTypes.size == ref.parameterTypes.size &&
                        m.parameterTypes.zip(ref.parameterTypes).all { (a, b) -> a.toString() == b.toString() }
            }
            if (targetMethod == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }
            val impl = targetMethod.implementation
            if (impl == null) {
                for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
                continue
            }

            val targetIsStatic = targetMethod.accessFlags and 0x0008 != 0
            val paramSlotOffset = if (targetIsStatic) 0 else 1
            var regOffset = paramSlotOffset
            for (p in 0 until paramIndex) {
                val pType = ref.parameterTypes[p].toString()
                regOffset += if (pType == "J" || pType == "D") 2 else 1
            }
            val paramReg = impl.registerCount - computeParamSize(ref, targetIsStatic) + regOffset

            val targetInstructions = impl.instructions.toList()
            val targetCfg = MethodCFG(targetInstructions, impl.tryBlocks)
            val targetCfgStrings = targetCfg.computeStringRegisters()

            // First try: whole-method scan for string switch (hashCode + equals)
            val switchValues = scanStringSwitchValues(targetInstructions, 0, paramReg, targetCfgStrings)
            if (switchValues.isNotEmpty()) return switchValues

            // Fallback: CFG-aware scan for direct equals comparisons
            val scanResult = scanStringValues(targetInstructions, 0, paramReg, targetCfgStrings, targetCfg.successors)
            if (scanResult.values.isNotEmpty()) return scanResult.values

            // Continue BFS to find other potential call sites
            for (s in successors[i]) { if (visited.add(s)) queue.add(s) }
        }
        return emptyList()
    }

    // --- Private helpers ---

    /** Enqueue CFG successors with a tracked register into the BFS queue. */
    private fun enqueueRegSuccessors(
        queue: ArrayDeque<RegState>,
        visited: MutableSet<RegState>,
        successors: Array<IntArray>,
        index: Int,
        reg: Int
    ) {
        for (s in successors[index]) {
            val state = RegState(s, reg)
            if (visited.add(state)) queue.add(state)
        }
    }

    /**
     * Scan ALL remaining instructions for equals() on [stringReg].
     * Used after detecting hashCode() → string switch pattern, where equals() calls
     * are in switch branch targets (not linearly ahead).
     */
    private fun scanStringSwitchValues(
        instructions: List<Instruction>,
        startIndex: Int,
        stringReg: Int,
        cfgStrings: Array<Map<Int, String>?>
    ): List<String> {
        val values = mutableSetOf<String>()
        for (i in startIndex until instructions.size) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            if (ref.definingClass == "Ljava/lang/String;" &&
                (ref.name == "equals" || ref.name == "equalsIgnoreCase")
            ) {
                val regStrings = cfgStrings[i] ?: continue
                when {
                    instr.registerC == stringReg -> regStrings[instr.registerD]?.let { values.add(it) }
                    instr.registerD == stringReg -> regStrings[instr.registerC]?.let { values.add(it) }
                }
            }
        }
        return values.toList()
    }

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
     * CFG-aware mini-scan for equals comparisons on a Uri component register
     * (from getHost/getScheme/getPath). Walks reachable instructions via [successors],
     * qualifies values based on the component type, and adds to [values].
     */
    private fun collectUriComponentValues(
        values: MutableSet<String>,
        instructions: List<Instruction>,
        startIndex: Int,
        componentReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        componentName: String,
        successors: Array<IntArray>
    ) {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()

        if (startIndex in instructions.indices) {
            queue.add(startIndex)
            visited.add(startIndex)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val instr = instructions[i]

            // Stop this path if component register is overwritten
            if (instr.opcode.setsRegister() && instr is OneRegisterInstruction &&
                instr.registerA == componentReg
            ) {
                continue
            }

            if (instr is ReferenceInstruction && instr is Instruction35c) {
                val ref = instr.reference
                if (ref is MethodReference) {
                    val regStrings = cfgStrings[i]
                    if (regStrings != null) {
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

                        // Kotlin Intrinsics.areEqual
                        if (ref.name == "areEqual" && ref.parameterTypes.size == 2 &&
                            ref.definingClass == "Lkotlin/jvm/internal/Intrinsics;"
                        ) {
                            extractComparedValue(instr, componentReg, regStrings)?.let {
                                values.add(qualifyUriComponent(it, componentName))
                            }
                        }
                    }
                }
            }

            // Enqueue successors
            for (s in successors[i]) {
                if (visited.add(s)) queue.add(s)
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
        val switchInstr = instructions[switchIndex]
        if (switchInstr !is OffsetInstruction) return null

        // Use the code offset to find the exact payload instruction.
        // The offset is in code units from the switch instruction's address.
        val targetCodeOffset = switchInstr.codeOffset
        var codeAddr = 0
        var switchCodeAddr = 0
        for (i in instructions.indices) {
            if (i == switchIndex) switchCodeAddr = codeAddr
            codeAddr += instructions[i].codeUnits
        }
        val payloadAddr = switchCodeAddr + targetCodeOffset
        codeAddr = 0
        for (i in instructions.indices) {
            if (codeAddr == payloadAddr) {
                val payload = instructions[i]
                if (payload is PackedSwitchPayload) return payload.switchElements.map { it.key }
                if (payload is SparseSwitchPayload) return payload.switchElements.map { it.key }
            }
            codeAddr += instructions[i].codeUnits
        }
        return null
    }

    private val URI_COMPONENT_METHODS = setOf("getHost", "getScheme", "getPath")

    private val CONST_INT_OPCODES = listOf(
        Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16,
        Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32
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
