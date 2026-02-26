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
import com.droidprobe.app.data.model.ContentProviderInfo
import com.droidprobe.app.data.model.ManifestAnalysis

/**
 * Extracts content provider URI patterns from DEX bytecode using multiple strategies:
 * 1. UriMatcher.addURI() calls — authority + path + match code
 * 2. Uri.parse("content://...") calls
 * 3. ContentUris.withAppendedId() calls
 * 4. Static fields named CONTENT_URI or *_URI
 * 5. Raw "content://" string constants as fallback
 */
class UriPatternExtractor(private val manifestAnalysis: ManifestAnalysis) {

    private val results = mutableListOf<ContentProviderInfo>()
    private val seenUris = mutableSetOf<String>()
    private val knownAuthorities = manifestAnalysis.providers.mapNotNull { it.authority }.toSet()

    fun process(classDef: DexBackedClassDef) {
        // Check static fields for CONTENT_URI patterns
        checkStaticFields(classDef)

        // Scan methods
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanMethod(classDef, method, instructions)
        }
    }

    fun getResults(): List<ContentProviderInfo> = results.toList()

    private fun checkStaticFields(classDef: DexBackedClassDef) {
        for (field in classDef.staticFields) {
            if ((field.name.endsWith("URI") || field.name.endsWith("_URI") || field.name == "CONTENT_URI") &&
                field.type == "Landroid/net/Uri;"
            ) {
                // The actual URI value is initialized in <clinit>, we'll try to find it there
                findUriInClinit(classDef, field.name)
            }
        }
    }

    private fun findUriInClinit(classDef: DexBackedClassDef, fieldName: String) {
        val clinit = classDef.methods.find { it.name == "<clinit>" } ?: return
        val impl = clinit.implementation ?: return
        val instructions = impl.instructions.toList()

        for ((i, instr) in instructions.withIndex()) {
            if (instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is MethodReference &&
                    ref.definingClass == "Landroid/net/Uri;" &&
                    ref.name == "parse" &&
                    instr.opcode in listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
                ) {
                    // Look back for the string argument
                    val uri = resolveStringArg(instructions, i)
                    if (uri != null && uri.startsWith("content://")) {
                        addResult(uri, null, classDef, "<clinit>")
                    }
                }
            }
        }
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
                // Strategy 1: UriMatcher.addURI(authority, path, code)
                isUriMatcherAddUri(ref) -> handleAddUri(instructions, i, classDef, method)

                // Strategy 2: Uri.parse(string)
                isUriParse(ref) -> handleUriParse(instructions, i, classDef, method)

                // Strategy 3: ContentUris.withAppendedId(uri, id)
                isContentUrisWithAppendedId(ref) -> handleContentUris(instructions, i, classDef, method)
            }
        }
    }

    // --- Strategy 1: UriMatcher.addURI ---

    private fun isUriMatcherAddUri(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/UriMatcher;" &&
                ref.name == "addURI"
    }

    private fun handleAddUri(
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val instr = instructions[callIndex]
        if (instr !is Instruction35c) return

        // addURI(authority: String, path: String, code: int)
        // Registers: v_obj(this), v_authority, v_path, v_code
        val authorityReg = instr.registerD
        val pathReg = instr.registerE
        val codeReg = instr.registerF

        val authority = resolveStringFromRegister(instructions, callIndex, authorityReg)
        val path = resolveStringFromRegister(instructions, callIndex, pathReg)
        val code = resolveIntFromRegister(instructions, callIndex, codeReg)

        if (authority != null && path != null) {
            val uri = "content://$authority/$path"
            addResult(uri, code, classDef, method.name)
        } else if (path != null) {
            // Try matching with known authorities
            for (knownAuth in knownAuthorities) {
                val uri = "content://$knownAuth/$path"
                addResult(uri, code, classDef, method.name)
            }
        }
    }

    // --- Strategy 2: Uri.parse ---

    private fun isUriParse(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/net/Uri;" &&
                ref.name == "parse"
    }

    private fun handleUriParse(
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        val uri = resolveStringArg(instructions, callIndex)
        if (uri != null && uri.startsWith("content://")) {
            addResult(uri, null, classDef, method.name)
        }
    }

    // --- Strategy 3: ContentUris.withAppendedId ---

    private fun isContentUrisWithAppendedId(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/ContentUris;" &&
                ref.name == "withAppendedId"
    }

    private fun handleContentUris(
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        // The first arg is a Uri - trace back to find a Uri.parse or sget for a CONTENT_URI
        // This is harder to resolve statically, so we just note that a withAppendedId pattern exists
        val uri = resolveStringArg(instructions, callIndex)
        if (uri != null && uri.startsWith("content://")) {
            addResult("$uri/#", null, classDef, method.name)
        }
    }

    // --- Register resolution helpers ---

    /**
     * Resolve a string constant that was loaded into any register right before a method call.
     * Walks backward from the call to find the most recent const-string.
     */
    private fun resolveStringArg(instructions: List<Instruction>, callIndex: Int): String? {
        val callInstr = instructions[callIndex]
        if (callInstr is Instruction35c) {
            // For invoke-static Uri.parse(String), the string is in registerC
            val reg = callInstr.registerC
            return resolveStringFromRegister(instructions, callIndex, reg)
        }
        return null
    }

    /**
     * Walk backward from callIndex to find a const-string that loads into the given register.
     */
    private fun resolveStringFromRegister(
        instructions: List<Instruction>,
        callIndex: Int,
        register: Int
    ): String? {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 30)) {
            val prev = instructions[j]
            if (prev is OneRegisterInstruction && prev is ReferenceInstruction) {
                if (prev.registerA == register && prev.reference is StringReference) {
                    return (prev.reference as StringReference).string
                }
            }
            // Also handle two-register const-string
            if (prev.opcode == Opcode.CONST_STRING || prev.opcode == Opcode.CONST_STRING_JUMBO) {
                if (prev is OneRegisterInstruction && prev.registerA == register) {
                    val ref = (prev as? ReferenceInstruction)?.reference
                    if (ref is StringReference) return ref.string
                }
            }
        }
        return null
    }

    /**
     * Walk backward to find an integer constant loaded into the given register.
     */
    private fun resolveIntFromRegister(
        instructions: List<Instruction>,
        callIndex: Int,
        register: Int
    ): Int? {
        for (j in callIndex - 1 downTo maxOf(0, callIndex - 20)) {
            val prev = instructions[j]
            if (prev is NarrowLiteralInstruction && prev is OneRegisterInstruction) {
                if (prev.registerA == register) {
                    return prev.narrowLiteral
                }
            }
        }
        return null
    }

    // --- Result management ---

    private fun addResult(
        uri: String,
        matchCode: Int?,
        classDef: DexBackedClassDef,
        methodName: String
    ) {
        if (uri in seenUris) return
        seenUris.add(uri)

        results.add(
            ContentProviderInfo(
                authority = extractAuthority(uri),
                uriPattern = uri,
                matchCode = matchCode,
                associatedColumns = emptyList(), // Populated later by cross-referencing StringConstantCollector
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    private fun extractAuthority(uri: String): String? {
        if (!uri.startsWith("content://")) return null
        val afterScheme = uri.removePrefix("content://")
        return afterScheme.substringBefore('/')
    }
}
