package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/**
 * Inter-procedural CFG-based dataflow analyzer for sensitive strings.
 *
 * Instead of heuristically associating sensitive strings with URLs from the same class,
 * this analyzer tracks where each sensitive string value actually flows through bytecode:
 *
 * 1. Finds static fields initialized to sensitive values
 * 2. Scans all methods for loads of those values (sget-object or const-string)
 * 3. Runs forward taint analysis through the method's CFG to detect if the value
 *    reaches an HTTP sink (e.g., Request.Builder.addHeader value argument)
 * 4. When a flow to an HTTP sink is confirmed, associates the sensitive string
 *    with URLs used in the same method (from Request.Builder.url() etc.)
 */
class SensitiveStringFlowAnalyzer(
    private val classIndex: Map<String, DexBackedClassDef>
) {
    // Field key ("Lclass;->name:type") → sensitive string value
    private val sensitiveFields = mutableMapOf<String, String>()

    /**
     * Analyze dataflow for each sensitive string value.
     * Returns map: sensitive string value → set of associated endpoint URLs.
     */
    fun analyze(sensitiveValues: Set<String>): Map<String, Set<String>> {
        // Step 1: Find static fields initialized to sensitive values.
        // Check both DEX static field initial values AND <clinit> bytecode
        // (Kotlin objects initialize @JvmField vals in <clinit> via const-string → sput-object)
        for ((_, classDef) in classIndex) {
            // Check encoded initial values
            for (field in classDef.staticFields) {
                val initValue = field.initialValue
                if (initValue is StringEncodedValue && initValue.value in sensitiveValues) {
                    val key = "${classDef.type}->${field.name}:${field.type}"
                    sensitiveFields[key] = initValue.value
                }
            }

            // Scan <clinit> for const-string → sput-object patterns
            for (method in classDef.methods) {
                if (method.name != "<clinit>") continue
                val impl = method.implementation ?: continue
                val instrs = impl.instructions.toList()
                val cfg = MethodCFG(instrs, impl.tryBlocks)
                val stringRegs = cfg.computeStringRegisters()
                for ((i, instr) in instrs.withIndex()) {
                    if (instr.opcode != Opcode.SPUT_OBJECT) continue
                    if (instr !is OneRegisterInstruction || instr !is ReferenceInstruction) continue
                    val fieldRef = instr.reference as? FieldReference ?: continue
                    val reg = instr.registerA
                    val value = stringRegs[i]?.get(reg) ?: continue
                    if (value in sensitiveValues) {
                        val key = "${fieldRef.definingClass}->${fieldRef.name}:${fieldRef.type}"
                        sensitiveFields[key] = value
                    }
                }
            }
        }

        // Step 2: Scan all methods for sensitive string → HTTP sink dataflow
        val result = mutableMapOf<String, MutableSet<String>>()

        for ((_, classDef) in classIndex) {
            if (isFrameworkClass(classDef.type)) continue
            for (method in classDef.methods) {
                val impl = method.implementation ?: continue
                val instructions = impl.instructions.toList()
                if (instructions.isEmpty()) continue

                analyzeMethod(instructions, impl.tryBlocks, sensitiveValues, result)
            }
        }

        return result
    }

    private fun analyzeMethod(
        instructions: List<Instruction>,
        tryBlocks: Iterable<com.android.tools.smali.dexlib2.iface.TryBlock<out com.android.tools.smali.dexlib2.iface.ExceptionHandler>>?,
        sensitiveValues: Set<String>,
        result: MutableMap<String, MutableSet<String>>
    ) {
        // Quick pre-check: method must contain both sensitive value loads AND HTTP sink calls
        var hasSensitive = false
        var hasHttpSink = false
        for (instr in instructions) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            when {
                ref is StringReference && ref.string in sensitiveValues -> hasSensitive = true
                ref is FieldReference -> {
                    val key = "${ref.definingClass}->${ref.name}:${ref.type}"
                    if (key in sensitiveFields) hasSensitive = true
                }
                ref is MethodReference && isHttpSinkMethod(ref) -> hasHttpSink = true
            }
        }
        if (!hasSensitive || !hasHttpSink) return

        // Build CFG
        val cfg = MethodCFG(instructions, tryBlocks)
        val stringRegs = cfg.computeStringRegisters()

        // Find all URLs used in HTTP requests in this method
        val methodUrls = findMethodUrls(instructions, stringRegs)
        if (methodUrls.isEmpty()) return

        // For each sensitive string entry point, run forward taint analysis
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference

            val sensitiveValue: String
            val targetReg: Int

            when {
                // const-string loading a sensitive value directly
                ref is StringReference && ref.string in sensitiveValues -> {
                    sensitiveValue = ref.string
                    targetReg = (instr as? OneRegisterInstruction)?.registerA ?: continue
                }
                // sget-object reading a field initialized to a sensitive value
                ref is FieldReference -> {
                    val key = "${ref.definingClass}->${ref.name}:${ref.type}"
                    val value = sensitiveFields[key] ?: continue
                    sensitiveValue = value
                    targetReg = (instr as? OneRegisterInstruction)?.registerA ?: continue
                }
                else -> continue
            }

            // Forward taint analysis: does targetReg reach an HTTP sink?
            if (taintReachesHttpSink(i, targetReg, instructions, cfg.successors)) {
                result.getOrPut(sensitiveValue) { mutableSetOf() }.addAll(methodUrls)
            }
        }
    }

    /**
     * Forward taint analysis using CFG BFS.
     *
     * Tracks which registers hold the tainted (sensitive) value at each instruction.
     * Uses "may" semantics (union at merges): if ANY path has the register tainted,
     * it's considered tainted at the merge point. This is standard for security
     * taint analysis — we want to detect if a sensitive value CAN reach a sink.
     *
     * Stops tracking a register when it's overwritten by a non-move instruction.
     */
    private fun taintReachesHttpSink(
        startIdx: Int,
        startReg: Int,
        instructions: List<Instruction>,
        successors: Array<IntArray>
    ): Boolean {
        val n = instructions.size

        // taintedRegs[i] = set of registers tainted at instruction i (null = not visited)
        val taintedRegs = arrayOfNulls<MutableSet<Int>>(n)

        // Initialize: startReg is tainted at the instruction that loads it
        taintedRegs[startIdx] = mutableSetOf(startReg)

        val worklist = ArrayDeque<Int>()
        // Seed with successors of the start instruction
        for (s in successors[startIdx]) {
            taintedRegs[s] = mutableSetOf(startReg)
            worklist.add(s)
        }

        while (worklist.isNotEmpty()) {
            val idx = worklist.removeFirst()
            val regs = taintedRegs[idx] ?: continue
            if (regs.isEmpty()) continue

            val instr = instructions[idx]

            // Check: does this instruction use a tainted register as an HTTP sink value?
            if (isHttpSinkWithTaintedValue(instr, regs)) return true

            // Compute post-state: which registers are still tainted after this instruction
            val postRegs = regs.toMutableSet()

            // Handle move-object: if source is tainted, destination becomes tainted too
            if (isMoveObject(instr.opcode) && instr is TwoRegisterInstruction) {
                if (instr.registerB in postRegs) {
                    postRegs.add(instr.registerA)
                } else {
                    // Destination overwritten with non-tainted value
                    postRegs.remove(instr.registerA)
                }
            } else if (instr.opcode.setsRegister() && instr is OneRegisterInstruction) {
                // Any other write to a register removes taint
                postRegs.remove(instr.registerA)
            }

            // Also handle move-result-object after invoke: if the invoke returns the
            // tainted value (e.g., method chaining), we can't know without IPA,
            // so conservatively remove taint from the result register.
            // (move-result is handled by setsRegister above)

            if (postRegs.isEmpty()) continue

            // Propagate to successors
            for (s in successors[idx]) {
                val existing = taintedRegs[s]
                if (existing == null) {
                    taintedRegs[s] = postRegs.toMutableSet()
                    worklist.add(s)
                } else {
                    // May-analysis: union — add any newly tainted registers
                    val before = existing.size
                    existing.addAll(postRegs)
                    if (existing.size > before) {
                        worklist.add(s)
                    }
                }
            }
        }

        return false
    }

    /**
     * Check if an instruction uses a tainted register as the VALUE argument
     * of an HTTP sink method (not the name/key argument).
     */
    private fun isHttpSinkWithTaintedValue(
        instr: Instruction,
        taintedRegs: Set<Int>
    ): Boolean {
        if (instr !is ReferenceInstruction) return false
        val ref = instr.reference as? MethodReference ?: return false

        // Request.Builder.addHeader(name, value) / .header(name, value)
        // Instance method: args are [this, name, value] → value is arg index 2
        if (ref.definingClass == "Lokhttp3/Request\$Builder;" &&
            (ref.name == "addHeader" || ref.name == "header") &&
            ref.parameterTypes.size == 2
        ) {
            val valueReg = getArgReg(instr, 2) ?: return false
            return valueReg in taintedRegs
        }

        // HttpUrl.Builder.addQueryParameter(name, value)
        if (ref.definingClass == "Lokhttp3/HttpUrl\$Builder;" &&
            ref.name == "addQueryParameter" &&
            ref.parameterTypes.size == 2
        ) {
            val valueReg = getArgReg(instr, 2) ?: return false
            return valueReg in taintedRegs
        }

        return false
    }

    /** Find all HTTP endpoint URLs used in this method via Request.Builder.url() etc. */
    private fun findMethodUrls(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>
    ): Set<String> {
        val urls = mutableSetOf<String>()
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            val url: String? = when {
                // Request.Builder.url(String)
                ref.definingClass == "Lokhttp3/Request\$Builder;" &&
                    ref.name == "url" &&
                    ref.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;" -> {
                    val argReg = getArgReg(instr, 1) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                // HttpUrl.parse(String) — static
                ref.definingClass == "Lokhttp3/HttpUrl;" &&
                    ref.name == "parse" -> {
                    val argReg = getArgReg(instr, 0) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                // HttpUrl.Companion.parse / get
                ref.definingClass == "Lokhttp3/HttpUrl\$Companion;" &&
                    (ref.name == "parse" || ref.name == "get") -> {
                    val argReg = getArgReg(instr, 1) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                else -> null
            }

            if (url != null && (url.startsWith("http://") || url.startsWith("https://")) && url.length > 10) {
                urls.add(url)
            }
        }
        return urls
    }

    private fun isHttpSinkMethod(ref: MethodReference): Boolean {
        return when {
            ref.definingClass == "Lokhttp3/Request\$Builder;" &&
                (ref.name == "addHeader" || ref.name == "header") -> true
            ref.definingClass == "Lokhttp3/HttpUrl\$Builder;" &&
                ref.name == "addQueryParameter" -> true
            else -> false
        }
    }

    private fun isMoveObject(opcode: Opcode): Boolean {
        return opcode == Opcode.MOVE_OBJECT ||
            opcode == Opcode.MOVE_OBJECT_FROM16 ||
            opcode == Opcode.MOVE_OBJECT_16
    }

    /** Get the register for argument at position argIdx (0-based, including `this` for instance methods). */
    private fun getArgReg(instr: Instruction, argIdx: Int): Int? {
        return when (instr) {
            is Instruction35c -> when (argIdx) {
                0 -> if (instr.registerCount > 0) instr.registerC else null
                1 -> if (instr.registerCount > 1) instr.registerD else null
                2 -> if (instr.registerCount > 2) instr.registerE else null
                3 -> if (instr.registerCount > 3) instr.registerF else null
                4 -> if (instr.registerCount > 4) instr.registerG else null
                else -> null
            }
            is Instruction3rc -> {
                if (argIdx < instr.registerCount) instr.startRegister + argIdx else null
            }
            else -> null
        }
    }

    private fun isFrameworkClass(type: String): Boolean {
        return type.startsWith("Landroid/") ||
            type.startsWith("Landroidx/") ||
            type.startsWith("Lkotlin/") ||
            type.startsWith("Lkotlinx/") ||
            type.startsWith("Ljava/") ||
            type.startsWith("Ljavax/") ||
            type.startsWith("Ldalvik/") ||
            type.startsWith("Lsun/") ||
            type.startsWith("Lorg/json/") ||
            type.startsWith("Lorg/xmlpull/") ||
            type.startsWith("Lorg/xml/") ||
            type.startsWith("Lorg/w3c/") ||
            type.startsWith("Lorg/apache/") ||
            type.startsWith("Lcom/google/android/material/") ||
            type.startsWith("Lcom/google/android/gms/") ||
            type.startsWith("Lcom/google/android/play/") ||
            type.startsWith("Lcom/google/android/exoplayer") ||
            type.startsWith("Lcom/google/android/datatransport/") ||
            type.startsWith("Lcom/google/android/libraries/") ||
            type.startsWith("Lretrofit2/") ||
            type.startsWith("Lokhttp3/") ||
            type.startsWith("Lcom/squareup/okhttp/")
    }
}
