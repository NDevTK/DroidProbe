package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Detects security-relevant bytecode patterns during DEX pass 2.
 *
 * Patterns detected:
 * - WebView.setJavaScriptEnabled(true)
 * - WebSettings.setAllowFileAccess(true) / setAllowFileAccessFromFileURLs / setAllowUniversalAccessFromFileURLs
 * - Intent redirection: getParcelableExtra() result flows to startActivity/startService/sendBroadcast
 * - Path traversal: Uri.getLastPathSegment() used in ContentProvider subclasses
 * - Implicit intent launch: startActivity with no explicit component set on the Intent
 */
class SecurityPatternDetector(
    private val classHierarchy: Map<String, String>
) {
    data class DetectedPattern(
        val category: String,
        val detail: String,
        val sourceClass: String,
        val sourceMethod: String?
    )

    private val results = mutableListOf<DetectedPattern>()

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()

            for ((index, instr) in instructions.withIndex()) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference as? MethodReference ?: continue
                val methodName = ref.name
                val defClass = ref.definingClass

                when {
                    // WebView JavaScript enabled
                    defClass == "Landroid/webkit/WebSettings;" && methodName == "setJavaScriptEnabled" -> {
                        if (isPrecedingConstTrue(instructions, index)) {
                            results.add(DetectedPattern(
                                category = "WEBVIEW_JS_ENABLED",
                                detail = "setJavaScriptEnabled(true)",
                                sourceClass = classDef.type,
                                sourceMethod = method.name
                            ))
                        }
                    }

                    // WebView file access
                    defClass == "Landroid/webkit/WebSettings;" && methodName in FILE_ACCESS_METHODS -> {
                        if (isPrecedingConstTrue(instructions, index)) {
                            results.add(DetectedPattern(
                                category = "WEBVIEW_FILE_ACCESS",
                                detail = "$methodName(true)",
                                sourceClass = classDef.type,
                                sourceMethod = method.name
                            ))
                        }
                    }

                    // Intent redirection: getParcelableExtra → startActivity/startService/sendBroadcast
                    defClass == "Landroid/content/Intent;" && methodName == "getParcelableExtra" -> {
                        if (hasLaunchAfter(instructions, index)) {
                            results.add(DetectedPattern(
                                category = "INTENT_REDIRECTION",
                                detail = "getParcelableExtra result used in activity/service launch",
                                sourceClass = classDef.type,
                                sourceMethod = method.name
                            ))
                        }
                    }

                    // Path traversal via getLastPathSegment
                    defClass == "Landroid/net/Uri;" && methodName == "getLastPathSegment" -> {
                        if (isContentProviderClass(classDef.type)) {
                            results.add(DetectedPattern(
                                category = "PATH_TRAVERSAL",
                                detail = "Uri.getLastPathSegment() in ContentProvider (vulnerable to encoded path separators)",
                                sourceClass = classDef.type,
                                sourceMethod = method.name
                            ))
                        }
                    }
                }
            }
        }
    }

    fun getResults(): List<DetectedPattern> = results.toList()

    /**
     * Check if the instruction preceding a method call loads const/4 v?, 0x1 (true)
     * into the argument register. Works for setJavaScriptEnabled(boolean) etc.
     */
    private fun isPrecedingConstTrue(instructions: List<Instruction>, callIndex: Int): Boolean {
        val call = instructions[callIndex] as? Instruction35c ?: return false
        val argReg = call.registerD  // first arg (after receiver) for 2-arg invoke

        // Scan backward up to 5 instructions for const/4 vArg, 0x1
        for (i in (callIndex - 1) downTo maxOf(0, callIndex - 5)) {
            val prev = instructions[i]
            if (prev.opcode == Opcode.CONST_4 || prev.opcode == Opcode.CONST) {
                val prevOneReg = prev as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
                val prevNarrow = prev as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
                if (prevOneReg != null && prevNarrow != null) {
                    if (prevOneReg.registerA == argReg && prevNarrow.narrowLiteral == 1) {
                        return true
                    }
                }
            }
            // If something else writes to argReg, stop
            if (prev is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction &&
                prev.registerA == argReg) {
                return false
            }
        }
        return false
    }

    /**
     * After getParcelableExtra, check if within the next 15 instructions there's a
     * startActivity/startService/sendBroadcast call, indicating intent redirection.
     */
    private fun hasLaunchAfter(instructions: List<Instruction>, fromIndex: Int): Boolean {
        val end = minOf(instructions.size, fromIndex + 15)
        for (i in (fromIndex + 1) until end) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue
            if (ref.name in LAUNCH_METHODS) return true
        }
        return false
    }

    /** Check if this class extends ContentProvider (walking hierarchy). */
    private fun isContentProviderClass(className: String): Boolean {
        var current: String? = className
        var depth = 0
        while (current != null && depth < 10) {
            if (current == "Landroid/content/ContentProvider;") return true
            current = classHierarchy[current]
            depth++
        }
        return false
    }

    companion object {
        private val FILE_ACCESS_METHODS = setOf(
            "setAllowFileAccess",
            "setAllowFileAccessFromFileURLs",
            "setAllowUniversalAccessFromFileURLs"
        )

        private val LAUNCH_METHODS = setOf(
            "startActivity", "startActivityForResult", "startActivities",
            "startService", "startForegroundService",
            "sendBroadcast", "sendOrderedBroadcast", "sendStickyBroadcast"
        )
    }
}
