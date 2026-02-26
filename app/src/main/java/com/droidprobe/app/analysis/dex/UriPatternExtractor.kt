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
 *
 * Uses forward register tracking so that string/int constants loaded once and reused
 * across many calls (e.g. an authority loaded once for 50+ addURI calls) are resolved
 * correctly regardless of distance from the call site.
 */
class UriPatternExtractor(private val manifestAnalysis: ManifestAnalysis) {

    private val results = mutableListOf<ContentProviderInfo>()
    private val seenUris = mutableSetOf<String>()
    private val knownAuthorities = manifestAnalysis.providers.mapNotNull { it.authority }.toSet()
    private val queryParamsByClass = mutableMapOf<String, MutableSet<String>>()

    fun process(classDef: DexBackedClassDef) {
        checkStaticFields(classDef)
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanMethod(classDef, method, instructions)
        }
    }

    fun getResults(): List<ContentProviderInfo> {
        return results.map { info ->
            val params = queryParamsByClass[info.sourceClass]
            if (params != null && params.isNotEmpty()) {
                info.copy(queryParameters = params.toList().sorted())
            } else {
                info
            }
        }
    }

    private fun checkStaticFields(classDef: DexBackedClassDef) {
        for (field in classDef.staticFields) {
            if ((field.name.endsWith("URI") || field.name.endsWith("_URI") || field.name == "CONTENT_URI") &&
                field.type == "Landroid/net/Uri;"
            ) {
                findUriInClinit(classDef, field.name)
            }
        }
    }

    private fun findUriInClinit(classDef: DexBackedClassDef, fieldName: String) {
        val clinit = classDef.methods.find { it.name == "<clinit>" } ?: return
        val impl = clinit.implementation ?: return
        val instructions = impl.instructions.toList()

        // Forward tracking for <clinit> too
        val regStrings = mutableMapOf<Int, String>()
        for (instr in instructions) {
            trackStringRegisters(instr, regStrings)
            if (instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is MethodReference &&
                    ref.definingClass == "Landroid/net/Uri;" &&
                    ref.name == "parse" &&
                    instr.opcode in listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
                ) {
                    if (instr is Instruction35c) {
                        val uri = regStrings[instr.registerC]
                        if (uri != null && uri.contains("://")) {
                            addResult(uri, null, classDef, "<clinit>")
                        }
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
        val regStrings = mutableMapOf<Int, String>()
        val regInts = mutableMapOf<Int, Int>()

        for (instr in instructions) {
            trackStringRegisters(instr, regStrings)
            trackIntRegisters(instr, regInts)

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            when {
                isUriMatcherAddUri(ref) -> handleAddUri(regStrings, regInts, instr, classDef, method)
                isUriParse(ref) -> handleUriParse(regStrings, instr, classDef, method)
                isContentUrisWithAppendedId(ref) -> handleContentUris(regStrings, instr, classDef, method)
                isGetQueryParameter(ref) -> handleGetQueryParameter(regStrings, instr, classDef)
            }
        }
    }

    /**
     * Track const-string / const-string/jumbo instructions into the register→string map.
     */
    private fun trackStringRegisters(instr: Instruction, regStrings: MutableMap<Int, String>) {
        if (instr.opcode == Opcode.CONST_STRING || instr.opcode == Opcode.CONST_STRING_JUMBO) {
            if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is StringReference) {
                    regStrings[instr.registerA] = ref.string
                }
            }
        }
    }

    /**
     * Track const/4, const/16, const, const/high16 into the register→int map.
     */
    private fun trackIntRegisters(instr: Instruction, regInts: MutableMap<Int, Int>) {
        if (instr is NarrowLiteralInstruction && instr is OneRegisterInstruction) {
            if (instr.opcode in listOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)) {
                regInts[instr.registerA] = instr.narrowLiteral
            }
        }
    }

    // --- Strategy 1: UriMatcher.addURI ---

    private fun isUriMatcherAddUri(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/UriMatcher;" &&
                ref.name == "addURI"
    }

    private fun handleAddUri(
        regStrings: Map<Int, String>,
        regInts: Map<Int, Int>,
        instr: Instruction,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        if (instr !is Instruction35c) return

        // addURI(authority: String, path: String, code: int)
        // Registers: v_obj(this), v_authority, v_path, v_code
        val authority = regStrings[instr.registerD]
        val path = regStrings[instr.registerE]
        val code = regInts[instr.registerF]

        if (authority != null && path != null) {
            val uri = if (authority in knownAuthorities) {
                "content://$authority/$path"
            } else {
                "$authority/$path"
            }
            addResult(uri, code, classDef, method.name)
        } else if (path != null) {
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
        regStrings: Map<Int, String>,
        instr: Instruction,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        if (instr !is Instruction35c) return
        val uri = regStrings[instr.registerC]
        if (uri != null && uri.contains("://")) {
            addResult(uri, null, classDef, method.name)
        }
    }

    // --- Strategy 3: ContentUris.withAppendedId ---

    private fun isContentUrisWithAppendedId(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/content/ContentUris;" &&
                ref.name == "withAppendedId"
    }

    private fun handleContentUris(
        regStrings: Map<Int, String>,
        instr: Instruction,
        classDef: DexBackedClassDef,
        method: DexBackedMethod
    ) {
        if (instr !is Instruction35c) return
        val uri = regStrings[instr.registerC]
        if (uri != null && uri.contains("://")) {
            addResult("$uri/#", null, classDef, method.name)
        }
    }

    // --- Strategy 4: Uri.getQueryParameter / getQueryParameters ---

    private fun isGetQueryParameter(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/net/Uri;" &&
                (ref.name == "getQueryParameter" || ref.name == "getQueryParameters")
    }

    private fun handleGetQueryParameter(
        regStrings: Map<Int, String>,
        instr: Instruction,
        classDef: DexBackedClassDef
    ) {
        if (instr !is Instruction35c) return
        // getQueryParameter(name: String) — name is in registerD (first arg after this)
        val paramName = regStrings[instr.registerD] ?: return
        if (paramName.isBlank()) return
        queryParamsByClass.getOrPut(classDef.type) { mutableSetOf() }.add(paramName)
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
                associatedColumns = emptyList(),
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    private fun extractAuthority(uri: String): String? {
        val schemeIndex = uri.indexOf("://")
        if (schemeIndex >= 0) {
            val afterScheme = uri.substring(schemeIndex + 3)
            return afterScheme.substringBefore('/')
        }
        // No scheme — authority/path format from UriMatcher
        return uri.substringBefore('/')
    }
}
