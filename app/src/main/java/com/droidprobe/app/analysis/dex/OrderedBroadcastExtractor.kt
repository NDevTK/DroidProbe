package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.droidprobe.app.data.model.OrderedBroadcastInfo

/**
 * Detects ordered broadcast patterns in BroadcastReceiver subclasses:
 * - setResultCode() / resultCode assignment
 * - setResultData() / resultData assignment
 * - setResultExtras(Bundle) with Bundle.putXxx() key extraction
 * - abortBroadcast()
 *
 * Only processes classes that extend BroadcastReceiver.
 */
class OrderedBroadcastExtractor(
    private val classHierarchy: Map<String, String>
) {
    private val results = mutableListOf<OrderedBroadcastInfo>()

    fun process(classDef: DexBackedClassDef) {
        if (!isBroadcastReceiverClass(classDef.type)) return

        var setsResultCode = false
        var setsResultData = false
        var setsResultExtras = false
        var abortsBroadcast = false
        val resultExtrasKeys = mutableSetOf<String>()

        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()

            for ((i, instr) in instructions.withIndex()) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference as? MethodReference ?: continue

                when {
                    // setResultCode(int) — may be called on BroadcastReceiver or subclass
                    isBroadcastReceiverClass(ref.definingClass) &&
                        ref.name == "setResultCode" -> {
                        setsResultCode = true
                    }

                    // setResultData(String)
                    isBroadcastReceiverClass(ref.definingClass) &&
                        ref.name == "setResultData" -> {
                        setsResultData = true
                    }

                    // setResultExtras(Bundle)
                    isBroadcastReceiverClass(ref.definingClass) &&
                        ref.name == "setResultExtras" -> {
                        setsResultExtras = true
                    }

                    // setResult(int, String, Bundle) — sets all three at once
                    isBroadcastReceiverClass(ref.definingClass) &&
                        ref.name == "setResult" -> {
                        setsResultCode = true
                        setsResultData = true
                        setsResultExtras = true
                    }

                    // abortBroadcast()
                    isBroadcastReceiverClass(ref.definingClass) &&
                        ref.name == "abortBroadcast" -> {
                        abortsBroadcast = true
                    }

                    // Bundle.putXxx(key, value) — extract keys from result extras bundle
                    ref.definingClass == "Landroid/os/Bundle;" &&
                        ref.name.startsWith("put") &&
                        ref.parameterTypes.isNotEmpty() &&
                        ref.parameterTypes[0] == "Ljava/lang/String;" -> {
                        val call = instr as? Instruction35c ?: continue
                        val key = cfgStrings[i]?.get(call.registerD) ?: continue
                        resultExtrasKeys.add(key)
                    }
                }

                // Kotlin property access: resultCode = X compiles to setResultCode
                // Also handle iput-* for resultCode/resultData fields (Kotlin uses setters)
            }
        }

        val isOrdered = setsResultCode || setsResultData || setsResultExtras || abortsBroadcast
        if (!isOrdered) return

        results.add(
            OrderedBroadcastInfo(
                setsResultCode = setsResultCode,
                setsResultData = setsResultData,
                setsResultExtras = setsResultExtras,
                resultExtrasKeys = resultExtrasKeys.sorted(),
                abortsbroadcast = abortsBroadcast,
                sourceClass = classDef.type
            )
        )
    }

    fun getResults(): List<OrderedBroadcastInfo> = results.toList()

    private fun isBroadcastReceiverClass(className: String): Boolean {
        var current: String? = className
        var depth = 0
        while (current != null && depth < 10) {
            if (current == "Landroid/content/BroadcastReceiver;") return true
            current = classHierarchy[current]
            depth++
        }
        return false
    }
}
