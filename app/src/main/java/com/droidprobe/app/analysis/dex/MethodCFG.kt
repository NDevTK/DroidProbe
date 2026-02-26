package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Lightweight control flow graph for a method's instruction list.
 *
 * Builds successor edges from branch/jump/switch/exception semantics,
 * then runs forward dataflow to compute which const-string values each
 * register provably holds at every reachable instruction.
 *
 * Merge semantics are "must-agree" (intersection): a register has a
 * known value only when ALL predecessor paths agree on the same string.
 */
class MethodCFG(
    private val instructions: List<Instruction>,
    tryBlocks: Iterable<TryBlock<out ExceptionHandler>>? = null
) {
    /** For each instruction index → array of successor indices. */
    val successors: Array<IntArray>

    /** Code-unit address → instruction index. */
    private val addressToIndex: IntArray   // indexed by code-unit address

    /** Instruction index → code-unit address. */
    private val indexToAddress: IntArray

    init {
        val n = instructions.size

        // Build bidirectional address ↔ index mappings
        val idxToAddr = IntArray(n)
        var maxAddr = 0
        run {
            var addr = 0
            for (i in 0 until n) {
                idxToAddr[i] = addr
                addr += instructions[i].codeUnits
            }
            maxAddr = addr
        }
        indexToAddress = idxToAddr

        // Sparse map: address → index  (-1 = no instruction at that address)
        val addrToIdx = IntArray(maxAddr + 1) { -1 }
        for (i in 0 until n) {
            addrToIdx[idxToAddr[i]] = i
        }
        addressToIndex = addrToIdx

        // Build successor edges
        val succ = Array(n) { mutableSetOf<Int>() }

        for (i in 0 until n) {
            val instr = instructions[i]
            val op = instr.opcode

            // Fall-through: if the instruction can continue to the next one
            if (op.canContinue() && i + 1 < n) {
                succ[i].add(i + 1)
            }

            // Branch/jump target
            if (instr is OffsetInstruction) {
                when (op) {
                    // Switch instructions: target is the payload, but we need to
                    // resolve the actual case targets from the payload
                    Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                        val payloadAddr = idxToAddr[i] + instr.codeOffset
                        if (payloadAddr in addrToIdx.indices) {
                            val payloadIdx = addrToIdx[payloadAddr]
                            if (payloadIdx >= 0) {
                                val payload = instructions[payloadIdx]
                                if (payload is SwitchPayload) {
                                    for (elem in payload.switchElements) {
                                        // Switch element offsets are relative to
                                        // the switch instruction, not the payload
                                        val targetAddr = idxToAddr[i] + elem.offset
                                        if (targetAddr in addrToIdx.indices) {
                                            val targetIdx = addrToIdx[targetAddr]
                                            if (targetIdx >= 0) succ[i].add(targetIdx)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // fill-array-data: target is data payload, not a branch
                    Opcode.FILL_ARRAY_DATA -> { /* payload, not a branch target */ }
                    // All other offset instructions: goto, if-*
                    else -> {
                        val targetAddr = idxToAddr[i] + instr.codeOffset
                        if (targetAddr in addrToIdx.indices) {
                            val targetIdx = addrToIdx[targetAddr]
                            if (targetIdx >= 0) succ[i].add(targetIdx)
                        }
                    }
                }
            }
        }

        // Exception handler edges: every instruction in a try range can jump
        // to each associated catch handler
        if (tryBlocks != null) {
            for (tryBlock in tryBlocks) {
                val startAddr = tryBlock.startCodeAddress
                val endAddr = startAddr + tryBlock.codeUnitCount

                // Collect handler target indices once per try block
                val handlerIndices = mutableListOf<Int>()
                for (handler in tryBlock.exceptionHandlers) {
                    val hAddr = handler.handlerCodeAddress
                    if (hAddr in addrToIdx.indices) {
                        val hIdx = addrToIdx[hAddr]
                        if (hIdx >= 0) handlerIndices.add(hIdx)
                    }
                }
                if (handlerIndices.isEmpty()) continue

                // Add edges from each instruction in the try range
                for (i in 0 until n) {
                    val instrAddr = idxToAddr[i]
                    if (instrAddr in startAddr until endAddr) {
                        // Only instructions that CAN_THROW should have exception edges,
                        // but being conservative (adding for all) is correct for dataflow
                        val instrOp = instructions[i].opcode
                        if (instrOp.canThrow()) {
                            succ[i].addAll(handlerIndices)
                        }
                    }
                }
            }
        }

        successors = Array(n) { succ[it].toIntArray() }
    }

    /**
     * Runs forward "must-agree" dataflow to compute const-string register
     * values at every reachable instruction index.
     *
     * Returns an array indexed by instruction index. Each entry is a map
     * from register number to the known string value. Null entry means
     * the instruction is unreachable or has no known string registers.
     *
     * A register has a known value at instruction I only when EVERY CFG
     * path reaching I agrees on the same const-string value for that
     * register (with no intervening non-const-string write).
     */
    fun computeStringRegisters(): Array<Map<Int, String>?> {
        val n = instructions.size
        if (n == 0) return emptyArray()

        // pre-state at each instruction: null = not yet reached
        val preState = arrayOfNulls<MutableMap<Int, String>>(n)

        // Entry point: all registers unknown (empty map)
        preState[0] = mutableMapOf()

        // Worklist
        val worklist = ArrayDeque<Int>(n)
        worklist.add(0)
        val inWorklist = BooleanArray(n)
        inWorklist[0] = true

        // Post-state cache to detect changes
        val postState = arrayOfNulls<Map<Int, String>>(n)

        while (worklist.isNotEmpty()) {
            val i = worklist.removeFirst()
            inWorklist[i] = false

            val pre = preState[i] ?: continue

            // Compute post-state: apply transfer function
            val post = applyTransfer(instructions[i], pre)

            // Skip if post-state hasn't changed
            val oldPost = postState[i]
            if (oldPost != null && oldPost == post) continue
            postState[i] = post

            // Propagate to each successor
            for (s in successors[i]) {
                val oldPre = preState[s]
                if (oldPre == null) {
                    // First visit: copy incoming state
                    preState[s] = post.toMutableMap()
                } else {
                    // Merge: intersect — keep only registers where both agree
                    var changed = false
                    val toRemove = mutableListOf<Int>()
                    for ((reg, value) in oldPre) {
                        val incoming = post[reg]
                        if (incoming != value) {
                            // Predecessor disagrees (different value or absent)
                            toRemove.add(reg)
                            changed = true
                        }
                    }
                    for (reg in toRemove) {
                        oldPre.remove(reg)
                    }
                    if (!changed) continue
                }

                if (!inWorklist[s]) {
                    worklist.add(s)
                    inWorklist[s] = true
                }
            }
        }

        // Convert to immutable result
        @Suppress("UNCHECKED_CAST")
        return Array(n) { preState[it]?.toMap() }
    }

    /**
     * Apply the transfer function for a single instruction.
     * Returns the post-state (new map, does not mutate input).
     */
    private fun applyTransfer(
        instr: Instruction,
        pre: Map<Int, String>
    ): Map<Int, String> {
        val op = instr.opcode

        // const-string / const-string/jumbo: register gets a known value
        if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) {
            if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is StringReference) {
                    val result = pre.toMutableMap()
                    result[instr.registerA] = ref.string
                    return result
                }
            }
        }

        // Any other instruction that writes to registerA: value becomes unknown
        if (op.setsRegister() && instr is OneRegisterInstruction) {
            val reg = instr.registerA
            if (reg in pre) {
                val result = pre.toMutableMap()
                result.remove(reg)
                return result
            }
        }

        // No change
        return pre
    }

    /** Resolve a code-unit address to its instruction index, or -1. */
    fun instructionIndex(address: Int): Int =
        if (address in addressToIndex.indices) addressToIndex[address] else -1

    /** Get the code-unit address of the instruction at the given index. */
    fun instructionAddress(index: Int): Int = indexToAddress[index]
}
