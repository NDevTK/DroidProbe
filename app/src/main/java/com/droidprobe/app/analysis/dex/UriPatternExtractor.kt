package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethod
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.droidprobe.app.data.model.ContentProviderInfo
import com.droidprobe.app.data.model.ManifestAnalysis

/**
 * Extracts content provider URI patterns from DEX bytecode using multiple strategies:
 * 1. UriMatcher.addURI() calls — authority + path + match code
 * 2. Uri.parse("content://...") calls
 * 3. ContentUris.withAppendedId() calls
 * 4. Static fields named CONTENT_URI or *_URI
 * 5. Raw "content://" string constants as fallback
 * 6. getQueryParameterNames() + Map.get("key") — bulk param reader pattern
 *
 * Uses forward register tracking so that string/int constants loaded once and reused
 * across many calls (e.g. an authority loaded once for 50+ addURI calls) are resolved
 * correctly regardless of distance from the call site.
 */
class UriPatternExtractor(
    private val manifestAnalysis: ManifestAnalysis,
    private val classIndex: Map<String, DexBackedClassDef> = emptyMap()
) {

    private val results = mutableListOf<ContentProviderInfo>()
    private val seenUris = mutableSetOf<String>()
    // For safe wildcard dedup: normalized URI → match code. Only dedup # vs * when match codes match.
    private val seenNormalizedByMatchCode = mutableMapOf<String, Int>()
    private val knownAuthorities = manifestAnalysis.providers.mapNotNull { it.authority }.toSet()
    // Unscoped: params from methods without UriMatcher dispatch (helper methods)
    private val queryParamsByClass = mutableMapOf<String, MutableSet<String>>()
    private val queryParamValuesByClass = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
    // Scoped: params associated with specific UriMatcher match codes via CFG reachability
    private val queryParamsByMatchCode = mutableMapOf<Int, MutableSet<String>>()
    private val queryParamValuesByMatchCode = mutableMapOf<Int, MutableMap<String, MutableSet<String>>>()
    // Inter-procedural: classes instantiated under specific match codes in dispatch methods.
    // Used to pull in class-level params from helper/handler classes (e.g. bwn for alarm/create).
    private val classesForMatchCode = mutableMapOf<Int, MutableSet<String>>()

    /**
     * Inter-procedural: wrapper method summaries.
     * Maps method signature → summary of which argument positions carry the
     * query parameter key and (optionally) the default value.
     */
    private data class WrapperSummary(
        val keyArgWordPos: Int,
        val defaultArgWordPos: Int? = null,
        val isBooleanWrapper: Boolean = false
    )
    private val wrapperSummaries = mutableMapOf<String, WrapperSummary>()

    // Default values for query parameters (param name → default value string)
    private val queryParamDefaultsByMatchCode = mutableMapOf<Int, MutableMap<String, String>>()
    private val queryParamDefaultsByClass = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * Strategy 6: Bulk param reader detection.
     * Methods that call Uri.getQueryParameterNames() and then read individual params
     * via Map.get("key") (after stream-collecting into a Map).
     */
    private data class BulkParamReaderInfo(
        val sourceClass: String,
        val reconstructedUri: String?,         // scheme://host/path from URI validation
        val sameMethodParams: MutableSet<String>,  // Map.get() keys found in same method
        val associatedClasses: MutableSet<String>, // classes instantiated with the collected map
        val paramValues: MutableMap<String, MutableSet<String>> = mutableMapOf(),
        val paramTypes: MutableMap<String, String> = mutableMapOf() // key → "int", "boolean", etc.
    )
    private val bulkParamReaders = mutableListOf<BulkParamReaderInfo>()
    // Classes that receive param maps via constructor (from bulk readers)
    private val paramMapReceiverClasses = mutableSetOf<String>()

    /**
     * Pre-scan pass: detect methods that are wrappers around getQueryParameter /
     * getBooleanQueryParameter and record which argument carries the param key.
     * Must be called for all classes BEFORE the main process() pass.
     */
    fun preScanForWrappers(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            // Check if this method has a Uri parameter
            val paramTypes = method.parameterTypes.toList()
            if (!paramTypes.contains("Landroid/net/Uri;")) continue

            // Build parameter register → explicit parameter index mapping
            val isStatic = (method.accessFlags and AccessFlags.STATIC.value) != 0
            val paramRegToIndex = buildParamRegisterMap(isStatic, paramTypes, impl.registerCount)

            // Scan for getQueryParameter/getBooleanQueryParameter calls
            for (instr in instructions) {
                if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
                val ref = instr.reference
                if (ref !is MethodReference) continue

                val isGQP = ref.definingClass == "Landroid/net/Uri;" &&
                        (ref.name == "getQueryParameter" || ref.name == "getQueryParameters")
                val isBQP = ref.definingClass == "Landroid/net/Uri;" &&
                        ref.name == "getBooleanQueryParameter"

                if (!isGQP && !isBQP) continue

                // getQueryParameter: invoke-virtual {uri, key} → key is registerD
                // getBooleanQueryParameter: invoke-virtual {uri, key, default} → key is registerD
                val keyReg = instr.registerD
                val paramIndex = paramRegToIndex[keyReg] ?: continue

                // Compute the argument word position in the invoke instruction at call sites.
                val argWord = computeArgWordPosition(isStatic, paramTypes, paramIndex)

                // Detect default value parameter position
                var defaultArgWord: Int? = null
                var isBooleanWrapper = false

                if (isBQP) {
                    // getBooleanQueryParameter: registerE is the default (boolean).
                    // Check if it maps to a method parameter.
                    isBooleanWrapper = true
                    val defaultParamIndex = paramRegToIndex[instr.registerE]
                    if (defaultParamIndex != null) {
                        defaultArgWord = computeArgWordPosition(isStatic, paramTypes, defaultParamIndex)
                    }
                } else {
                    // getQueryParameter: look for a third parameter (not Uri, not key)
                    // as a potential default value (common wrapper pattern).
                    val uriParamIndex = paramTypes.indexOfFirst { it.toString() == "Landroid/net/Uri;" }
                    val otherIndices = paramTypes.indices.filter { it != uriParamIndex && it != paramIndex }
                    if (otherIndices.size == 1) {
                        val defIdx = otherIndices[0]
                        defaultArgWord = computeArgWordPosition(isStatic, paramTypes, defIdx)
                    }
                }

                val sig = buildMethodSignature(method)
                wrapperSummaries[sig] = WrapperSummary(argWord, defaultArgWord, isBooleanWrapper)
                DexDebugLog.log("[WrapperSummary] ${classDef.type}->${method.name}: " +
                    "param[$paramIndex] is query key → argWord=$argWord, " +
                    "defaultArgWord=$defaultArgWord, boolean=$isBooleanWrapper")
                break // one summary per method is enough
            }
        }
    }

    /**
     * Pre-scan pass: detect methods that call Uri.getQueryParameterNames() and read
     * individual params via Map.get("key") after collecting into a Map.
     *
     * For each such method:
     * - Reconstructs the validated URI from getScheme/getHost/getPath comparisons
     * - Extracts Map.get("key") string constants as query parameter names
     * - Records classes instantiated in the same method as "param-map receivers"
     *
     * Must be called for all classes BEFORE the main process() pass.
     */
    fun preScanForBulkParamReaders(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            // Check for getQueryParameterNames() call
            var hasGetQueryParamNames = false
            for (instr in instructions) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference
                if (ref is MethodReference &&
                    ref.definingClass == "Landroid/net/Uri;" &&
                    ref.name == "getQueryParameterNames"
                ) {
                    hasGetQueryParamNames = true
                    break
                }
            }
            if (!hasGetQueryParamNames) continue

            // Build CFG and compute string registers for this method
            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()

            // Detect URI validation: getScheme/getHost/getPath → equals comparison
            var detectedScheme: String? = null
            var detectedHost: String? = null
            var detectedPath: String? = null
            val mapGetKeys = mutableSetOf<String>()
            val mapGetParamValues = mutableMapOf<String, MutableSet<String>>()
            val mapGetParamTypes = mutableMapOf<String, String>()
            val mapGetFieldMap = mutableMapOf<String, String>() // field ref → param key
            val instantiatedClasses = mutableSetOf<String>()

            for ((i, instr) in instructions.withIndex()) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference
                if (ref !is MethodReference) continue

                val regStrings = cfgStrings[i] ?: emptyMap()

                // Detect Uri.getScheme/getHost/getPath followed by equals comparison
                if (ref.definingClass == "Landroid/net/Uri;") {
                    when (ref.name) {
                        "getScheme" -> {
                            val compared = findEqualsComparisonTarget(instructions, i, cfgStrings)
                            if (compared != null) detectedScheme = compared
                        }
                        "getHost" -> {
                            val compared = findEqualsComparisonTarget(instructions, i, cfgStrings)
                            if (compared != null) detectedHost = compared
                        }
                        "getPath" -> {
                            val compared = findEqualsComparisonTarget(instructions, i, cfgStrings)
                            if (compared != null) detectedPath = compared
                        }
                    }
                }

                // Detect Map.get("stringConstant"): any .get(Object)Object call with string arg
                if (ref.name == "get" &&
                    ref.parameterTypes.size == 1 &&
                    ref.parameterTypes[0].toString() == "Ljava/lang/Object;" &&
                    ref.returnType == "Ljava/lang/Object;"
                ) {
                    if (instr is Instruction35c) {
                        val key = regStrings[instr.registerD]
                        if (key != null && key.isNotBlank() && !key.contains("://") && key.length < 50) {
                            mapGetKeys.add(key)
                            // Scan forward for type conversions and value comparisons
                            val resultReg = ForwardValueScanner.getMoveResultRegister(instructions, i)
                            if (resultReg != null) {
                                val scan = ForwardValueScanner.scanStringValues(instructions, i + 2, resultReg, cfgStrings, window = 40)
                                if (scan.detectedType != null) mapGetParamTypes[key] = scan.detectedType
                                if (scan.values.isNotEmpty()) mapGetParamValues.getOrPut(key) { mutableSetOf() }.addAll(scan.values)
                                // If parseInt detected, scan for int values locally + inter-procedurally (enum)
                                if (scan.detectedType == "int" && scan.convertedResultReg != null && scan.convertedResultIndex != null) {
                                    val vals = mapGetParamValues.getOrPut(key) { mutableSetOf() }
                                    vals.addAll(ForwardValueScanner.scanIntValues(instructions, scan.convertedResultIndex, scan.convertedResultReg))
                                    vals.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, scan.convertedResultIndex, scan.convertedResultReg))
                                }
                                if (scan.storedToField != null) {
                                    mapGetFieldMap[scan.storedToField] = key
                                }
                            }
                        }
                    }
                }

                // Detect constructor calls: new SomeClass(...)
                if (ref.name == "<init>" &&
                    instr.opcode in listOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
                ) {
                    val targetClass = ref.definingClass
                    if (!targetClass.startsWith("Ljava/") &&
                        !targetClass.startsWith("Landroid/") &&
                        !targetClass.startsWith("Lkotlin/")
                    ) {
                        instantiatedClasses.add(targetClass)
                    }
                }
            }

            // Field tracking: find iget-object reads of stored fields → forward-scan for values
            scanFieldReadsForValues(instructions, cfgStrings, mapGetFieldMap, mapGetParamValues, mapGetParamTypes)

            // Build reconstructed URI
            val reconstructedUri = if (detectedScheme != null && detectedHost != null) {
                val path = detectedPath ?: ""
                "$detectedScheme://$detectedHost$path"
            } else null

            if (mapGetKeys.isEmpty() && instantiatedClasses.isEmpty()) continue

            val info = BulkParamReaderInfo(
                sourceClass = classDef.type,
                reconstructedUri = reconstructedUri,
                sameMethodParams = mapGetKeys,
                associatedClasses = instantiatedClasses,
                paramValues = mapGetParamValues,
                paramTypes = mapGetParamTypes
            )
            bulkParamReaders.add(info)
            paramMapReceiverClasses.addAll(instantiatedClasses)

            DexDebugLog.log("[BulkParamReader] ${classDef.type}::${method.name}: " +
                "uri=$reconstructedUri params=$mapGetKeys " +
                "associated=${instantiatedClasses.size} classes")
        }
    }

    /**
     * After getScheme/getHost/getPath call at [callIndex], find the string constant
     * it is compared against via equals/Objects.equals within the next few instructions.
     */
    private fun findEqualsComparisonTarget(
        instructions: List<Instruction>,
        callIndex: Int,
        cfgStrings: Array<Map<Int, String>?>
    ): String? {
        // The result of getScheme/getHost/getPath goes into move-result-object
        val resultReg = getMoveResultRegister(instructions, callIndex) ?: return null

        // Scan forward for an equals comparison (up to 10 instructions)
        val limit = minOf(instructions.size, callIndex + 12)
        for (i in callIndex + 2 until limit) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            val isInstanceEquals = (ref.name == "equals" || ref.name == "equalsIgnoreCase") &&
                ref.parameterTypes.size == 1
            // Objects.equals(Object, Object) — desugared as j$.util.Objects or java.util.Objects
            val isStaticEquals = ref.name == "equals" &&
                ref.parameterTypes.size == 2 &&
                (ref.definingClass.endsWith("/Objects;") || ref.definingClass == "Ljava/util/Objects;")

            if (!isInstanceEquals && !isStaticEquals) continue

            val regStrings = cfgStrings[i] ?: continue

            if (isStaticEquals) {
                // invoke-static {a, b}, Objects.equals(Object, Object)Z
                // Both registerC and registerD are arguments
                if (instr.registerC == resultReg) {
                    val compared = regStrings[instr.registerD]
                    if (compared != null) return compared
                } else if (instr.registerD == resultReg) {
                    val compared = regStrings[instr.registerC]
                    if (compared != null) return compared
                }
            } else {
                // invoke-virtual {receiver, arg}, String.equals(Object)Z
                // registerC is receiver, registerD is the argument
                if (instr.registerC == resultReg) {
                    val compared = regStrings[instr.registerD]
                    if (compared != null) return compared
                } else if (instr.registerD == resultReg) {
                    val compared = regStrings[instr.registerC]
                    if (compared != null) return compared
                }
            }
        }
        return null
    }

    /** Maps register number → explicit parameter index (0-based, not counting `this`). */
    private fun buildParamRegisterMap(
        isStatic: Boolean,
        paramTypes: List<CharSequence>,
        registerCount: Int
    ): Map<Int, Int> {
        var totalParamWords = if (isStatic) 0 else 1
        for (type in paramTypes) {
            totalParamWords += if (type == "J" || type == "D") 2 else 1
        }

        val firstParamReg = registerCount - totalParamWords
        val map = mutableMapOf<Int, Int>()

        var reg = firstParamReg
        if (!isStatic) reg++ // skip `this`

        for (i in paramTypes.indices) {
            map[reg] = i
            reg += if (paramTypes[i] == "J" || paramTypes[i] == "D") 2 else 1
        }

        return map
    }

    /**
     * Compute the argument word position in an invoke instruction for a given
     * explicit parameter index. Accounts for `this` (virtual) and wide types.
     */
    private fun computeArgWordPosition(
        isStatic: Boolean,
        paramTypes: List<CharSequence>,
        paramIndex: Int
    ): Int {
        var pos = if (isStatic) 0 else 1 // skip `this` for virtual
        for (i in 0 until paramIndex) {
            pos += if (paramTypes[i] == "J" || paramTypes[i] == "D") 2 else 1
        }
        return pos
    }

    /** Build a unique method signature string from a DexBackedMethod. */
    private fun buildMethodSignature(method: DexBackedMethod): String {
        val params = method.parameterTypes.joinToString("")
        return "${method.definingClass}->${method.name}($params)${method.returnType}"
    }

    /** Build a unique method signature string from a MethodReference. */
    private fun buildMethodSignatureFromRef(ref: MethodReference): String {
        val params = ref.parameterTypes.joinToString("")
        return "${ref.definingClass}->${ref.name}($params)${ref.returnType}"
    }

    fun process(classDef: DexBackedClassDef) {
        checkStaticFields(classDef)
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            scanMethod(classDef, method, impl, instructions)
        }

        // Cross-class Map.get() detection for param-map receiver classes
        if (classDef.type in paramMapReceiverClasses) {
            scanForMapGetKeys(classDef)
        }
    }

    /**
     * For each field in [fieldParamMap], find iget-object reads in [instructions] and
     * forward-scan from each read to detect comparison values.
     * Values found are associated back to the original Map.get() param key.
     */
    private fun scanFieldReadsForValues(
        instructions: List<Instruction>,
        cfgStrings: Array<Map<Int, String>?>,
        fieldParamMap: Map<String, String>,
        paramValues: MutableMap<String, MutableSet<String>>,
        paramTypes: MutableMap<String, String>
    ) {
        if (fieldParamMap.isEmpty()) return
        for ((i, instr) in instructions.withIndex()) {
            if (instr.opcode != Opcode.IGET_OBJECT) continue
            if (instr !is TwoRegisterInstruction || instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is FieldReference) continue
            val fieldStr = "${ref.definingClass}->${ref.name}:${ref.type}"
            val paramKey = fieldParamMap[fieldStr] ?: continue

            val readReg = instr.registerA
            val scan = ForwardValueScanner.scanStringValues(
                instructions, i + 1, readReg, cfgStrings, window = 30
            )
            if (scan.values.isNotEmpty()) {
                paramValues.getOrPut(paramKey) { mutableSetOf() }.addAll(scan.values)
            }
            if (scan.detectedType != null) {
                paramTypes.putIfAbsent(paramKey, scan.detectedType)
            }
        }
    }

    /**
     * Scan a param-map receiver class for Map.get("key") calls.
     * These are classes instantiated by a bulk param reader method — the Map
     * passed to their constructor holds collected URI query parameters.
     */
    private fun scanForMapGetKeys(classDef: DexBackedClassDef) {
        val keys = mutableSetOf<String>()
        val detectedParamValues = mutableMapOf<String, MutableSet<String>>()
        val detectedParamTypes = mutableMapOf<String, String>()
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val cfgStrings = cfg.computeStringRegisters()

            // Track field stores: param key → field reference string
            val fieldParamMap = mutableMapOf<String, String>()

            for ((i, instr) in instructions.withIndex()) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference
                if (ref !is MethodReference) continue

                // Match Map.get(Object) → Object
                if (ref.name == "get" &&
                    ref.parameterTypes.size == 1 &&
                    ref.parameterTypes[0].toString() == "Ljava/lang/Object;" &&
                    ref.returnType == "Ljava/lang/Object;" &&
                    instr is Instruction35c
                ) {
                    val regStrings = cfgStrings[i] ?: continue
                    val key = regStrings[instr.registerD]
                    if (key != null && key.isNotBlank() && !key.contains("://") && key.length < 50) {
                        keys.add(key)
                        val resultReg = ForwardValueScanner.getMoveResultRegister(instructions, i)
                        if (resultReg != null) {
                            val scan = ForwardValueScanner.scanStringValues(instructions, i + 2, resultReg, cfgStrings, window = 40)
                            if (scan.detectedType != null) detectedParamTypes[key] = scan.detectedType
                            if (scan.values.isNotEmpty()) detectedParamValues.getOrPut(key) { mutableSetOf() }.addAll(scan.values)
                            if (scan.detectedType == "int" && scan.convertedResultReg != null && scan.convertedResultIndex != null) {
                                val vals = detectedParamValues.getOrPut(key) { mutableSetOf() }
                                vals.addAll(ForwardValueScanner.scanIntValues(instructions, scan.convertedResultIndex, scan.convertedResultReg))
                                vals.addAll(ForwardValueScanner.resolveEnumValues(classIndex, instructions, scan.convertedResultIndex, scan.convertedResultReg))
                            }
                            if (scan.storedToField != null) {
                                fieldParamMap[scan.storedToField] = key
                            }
                        }
                    }
                }
            }

            // Field tracking: find iget-object reads of stored fields → forward-scan for values
            scanFieldReadsForValues(instructions, cfgStrings, fieldParamMap, detectedParamValues, detectedParamTypes)
        }

        if (keys.isEmpty()) return

        // Add these keys to the bulk reader(s) that reference this class
        for (reader in bulkParamReaders) {
            if (classDef.type in reader.associatedClasses) {
                reader.sameMethodParams.addAll(keys)
                detectedParamTypes.forEach { (k, v) -> reader.paramTypes.putIfAbsent(k, v) }
                detectedParamValues.forEach { (k, vals) ->
                    reader.paramValues.getOrPut(k) { mutableSetOf() }.addAll(vals)
                }
                DexDebugLog.log("[BulkParamReader] Cross-class Map.get() in ${classDef.type}: " +
                    "added keys=$keys types=$detectedParamTypes values=$detectedParamValues " +
                    "to reader ${reader.sourceClass} uri=${reader.reconstructedUri}")
            }
        }
    }

    fun getResults(): List<ContentProviderInfo> {
        // First, produce ContentProviderInfo entries from bulk param readers
        for (reader in bulkParamReaders) {
            if (reader.sameMethodParams.isEmpty()) continue
            val uri = reader.reconstructedUri ?: continue

            if (uri in seenUris) continue
            seenUris.add(uri)

            // Build queryParameterValues from detected values (real comparison constants only)
            val paramValuesMap = mutableMapOf<String, List<String>>()
            for (key in reader.sameMethodParams) {
                val vals = reader.paramValues[key]
                if (vals != null && vals.isNotEmpty()) {
                    paramValuesMap[key] = vals.toList().sorted()
                }
            }

            DexDebugLog.log("[BulkParamReader] Creating result: uri=$uri " +
                "params=${reader.sameMethodParams} types=${reader.paramTypes} " +
                "values=$paramValuesMap source=${reader.sourceClass}")

            results.add(
                ContentProviderInfo(
                    authority = extractAuthority(uri),
                    uriPattern = uri,
                    matchCode = null,
                    associatedColumns = emptyList(),
                    queryParameters = reader.sameMethodParams.toList().sorted(),
                    queryParameterValues = paramValuesMap,
                    sourceClass = reader.sourceClass,
                    sourceMethod = null
                )
            )
        }

        return results.map { info ->
            // Skip bulk-reader results that already have queryParameters set
            if (info.queryParameters.isNotEmpty()) return@map info

            val params = mutableSetOf<String>()
            val paramValues = mutableMapOf<String, MutableSet<String>>()
            val defaults = mutableMapOf<String, String>()

            // 1. Match-code-scoped params (from dispatch methods with UriMatcher)
            if (info.matchCode != null) {
                queryParamsByMatchCode[info.matchCode]?.let { params.addAll(it) }
                queryParamValuesByMatchCode[info.matchCode]?.forEach { (param, vals) ->
                    paramValues.getOrPut(param) { mutableSetOf() }.addAll(vals)
                }
                queryParamDefaultsByMatchCode[info.matchCode]?.let { defaults.putAll(it) }

                // 1b. Inter-procedural: params from classes instantiated under this match code.
                classesForMatchCode[info.matchCode]?.forEach { assocClass ->
                    queryParamsByClass[assocClass]?.let { params.addAll(it) }
                    queryParamValuesByClass[assocClass]?.forEach { (param, vals) ->
                        paramValues.getOrPut(param) { mutableSetOf() }.addAll(vals)
                    }
                    queryParamDefaultsByClass[assocClass]?.let { defaults.putAll(it) }
                }
            }

            // 2. Class-level params (from helper methods without dispatch context)
            queryParamsByClass[info.sourceClass]?.let { params.addAll(it) }
            queryParamValuesByClass[info.sourceClass]?.forEach { (param, vals) ->
                paramValues.getOrPut(param) { mutableSetOf() }.addAll(vals)
            }
            queryParamDefaultsByClass[info.sourceClass]?.let { defaults.putAll(it) }

            if (params.isNotEmpty()) {
                info.copy(
                    queryParameters = params.toList().sorted(),
                    queryParameterValues = paramValues
                        .mapValues { (_, values) -> values.toList().sorted() },
                    queryParameterDefaults = defaults
                )
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

        // Use CFG-based tracking for <clinit> too
        val cfg = MethodCFG(instructions, impl.tryBlocks)
        val cfgStrings = cfg.computeStringRegisters()

        for ((i, instr) in instructions.withIndex()) {
            if (instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is MethodReference &&
                    ref.definingClass == "Landroid/net/Uri;" &&
                    ref.name == "parse" &&
                    instr.opcode in listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
                ) {
                    if (instr is Instruction35c) {
                        val regStrings = cfgStrings[i] ?: continue
                        val uri = regStrings[instr.registerC]
                        if (uri != null && uri.contains("://")) {
                            addResultWithInlineParams(uri, classDef, "<clinit>")
                        }
                    }
                }
            }
        }
    }

    private fun scanMethod(
        classDef: DexBackedClassDef,
        method: DexBackedMethod,
        impl: MethodImplementation,
        instructions: List<Instruction>
    ) {
        // Build CFG and compute string register state via forward dataflow.
        // This properly handles branches: registers overwritten on dead paths
        // (after return/throw) don't corrupt the state at live instructions.
        val cfg = MethodCFG(instructions, impl.tryBlocks)
        val cfgStrings = cfg.computeStringRegisters()

        // Detect UriMatcher dispatch: match() → switch/if-eq on match register.
        // This lets us scope getQueryParameter calls to specific match codes.
        val matchDispatch = detectMatchDispatch(instructions, cfg)

        val regInts = mutableMapOf<Int, Int>()

        // Strategy 7: Deep link URI detection from manual getScheme/getHost/getPath validation
        val deepLinkSchemes = mutableSetOf<String>()
        val deepLinkHosts = mutableSetOf<String>()
        val deepLinkPaths = mutableSetOf<String>()

        for ((i, instr) in instructions.withIndex()) {
            trackIntRegisters(instr, regInts)

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            // Use CFG-computed string state at this instruction index
            val regStrings = cfgStrings[i] ?: emptyMap()

            when {
                isUriMatcherAddUri(ref) -> handleAddUri(regStrings, regInts, instr, classDef, method)
                isUriParse(ref) -> handleUriParse(regStrings, instr, classDef, method)
                isContentUrisWithAppendedId(ref) -> handleContentUris(regStrings, instr, classDef, method)
                isGetQueryParameter(ref) -> handleGetQueryParameter(ref, regStrings, instr, instructions, i, classDef, cfgStrings, matchDispatch)
                isGetBooleanQueryParameter(ref) -> handleGetBooleanQueryParameter(regStrings, regInts, instr, i, classDef, matchDispatch)
                else -> handleWrapperCallSite(ref, regStrings, regInts, instr, i, classDef, matchDispatch)
            }

            // Detect Uri.getPath/getHost/getScheme → equals comparisons for deep link URIs
            if (ref.definingClass == "Landroid/net/Uri;" &&
                ref.name in DEEP_LINK_COMPONENT_METHODS && ref.parameterTypes.isEmpty()
            ) {
                val resultReg = ForwardValueScanner.getMoveResultRegister(instructions, i)
                if (resultReg != null) {
                    val values = collectDeepLinkComponentValues(instructions, i + 2, resultReg, cfgStrings)
                    when (ref.name) {
                        "getPath" -> deepLinkPaths.addAll(values)
                        "getHost" -> deepLinkHosts.addAll(values)
                        "getScheme" -> deepLinkSchemes.addAll(values)
                    }
                }
            }

            // Inter-procedural: track classes instantiated under specific match codes.
            // When a dispatch method does `new Foo(uri, ...)` under match code X,
            // Foo's class-level params should be associated with match code X.
            if (matchDispatch != null && ref.name == "<init>" &&
                instr.opcode in listOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
            ) {
                val targetClass = ref.definingClass
                // Only track non-framework classes that take a Uri parameter
                if (ref.parameterTypes.any { it.toString() == "Landroid/net/Uri;" }) {
                    val matchCodes = findMatchCodesForInstruction(i, matchDispatch)
                    if (matchCodes.isNotEmpty()) {
                        for (code in matchCodes) {
                            classesForMatchCode.getOrPut(code) { mutableSetOf() }.add(targetClass)
                        }
                        DexDebugLog.log("[UriParam] Constructor association: $targetClass " +
                            "instantiated under matchCodes=$matchCodes")
                    }
                }
            }
        }

        // Construct deep link URIs from detected scheme/host/path validation
        if (deepLinkHosts.isNotEmpty() && deepLinkPaths.isNotEmpty()) {
            val scheme = deepLinkSchemes.firstOrNull() ?: "https"
            for (host in deepLinkHosts) {
                if (!host.contains('.')) continue
                for (path in deepLinkPaths) {
                    if (!path.startsWith('/')) continue
                    addResult("$scheme://$host$path", null, classDef, method.name)
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

    // --- Strategy 7: Deep link URI from manual scheme/host/path validation ---

    private val DEEP_LINK_COMPONENT_METHODS = setOf("getPath", "getHost", "getScheme")

    /**
     * Forward-scan for string comparisons on a URI component result register.
     * Returns raw compared values (e.g., "/gasearch", "www.google.com", "https").
     */
    private fun collectDeepLinkComponentValues(
        instructions: List<Instruction>,
        startIndex: Int,
        componentReg: Int,
        cfgStrings: Array<Map<Int, String>?>
    ): List<String> {
        val values = mutableListOf<String>()
        val limit = minOf(instructions.size, startIndex + 20)
        for (i in startIndex until limit) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue
            val regStrings = cfgStrings[i] ?: continue

            val isEquals = (ref.definingClass == "Ljava/lang/String;" &&
                    (ref.name == "equals" || ref.name == "equalsIgnoreCase")) ||
                    (ref.definingClass == "Landroid/text/TextUtils;" && ref.name == "equals") ||
                    (ref.name == "equals" && ref.parameterTypes.size == 2 &&
                            ref.definingClass.endsWith("/Objects;"))
            if (!isEquals) continue

            val value = when {
                instr.registerC == componentReg -> regStrings[instr.registerD]
                instr.registerD == componentReg -> regStrings[instr.registerC]
                else -> null
            }
            if (value != null) values.add(value)
        }
        return values
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
            addResultWithInlineParams(uri, classDef, method.name)
        }
    }

    /** Add a URI result, extracting inline query params from the URL if present. */
    private fun addResultWithInlineParams(
        uri: String,
        classDef: DexBackedClassDef,
        methodName: String
    ) {
        val parsed = parseUrlQueryParams(uri)
        if (parsed != null) {
            val (baseUrl, paramMap) = parsed
            addResult(
                baseUrl, null, classDef, methodName,
                queryParameters = paramMap.keys.toList().sorted(),
                queryParameterValues = paramMap
                    .filter { it.value.any { v -> v.isNotEmpty() } }
                    .mapValues { (_, values) -> values.filter { it.isNotEmpty() }.sorted() }
            )
        } else {
            addResult(uri, null, classDef, methodName)
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

    // --- Strategy 4: Uri.getQueryParameter / getQueryParameters / getBooleanQueryParameter ---

    private fun isGetQueryParameter(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/net/Uri;" &&
                (ref.name == "getQueryParameter" || ref.name == "getQueryParameters")
    }

    private fun isGetBooleanQueryParameter(ref: MethodReference): Boolean {
        return ref.definingClass == "Landroid/net/Uri;" &&
                ref.name == "getBooleanQueryParameter"
    }

    private fun handleGetQueryParameter(
        ref: MethodReference,
        regStrings: Map<Int, String>,
        instr: Instruction,
        instructions: List<Instruction>,
        callIndex: Int,
        classDef: DexBackedClassDef,
        cfgStrings: Array<Map<Int, String>?>,
        matchDispatch: MatchDispatchInfo?
    ) {
        if (instr !is Instruction35c) return
        val paramName = regStrings[instr.registerD] ?: return
        if (paramName.isBlank()) return

        val resultReg = getMoveResultRegister(instructions, callIndex) ?: return
        val isPlural = ref.name == "getQueryParameters"

        // Determine which match codes can reach this getQueryParameter via CFG
        val matchCodes = if (matchDispatch != null) {
            findMatchCodesForInstruction(callIndex, matchDispatch)
        } else {
            emptySet()
        }

        DexDebugLog.logFiltered(classDef.type, paramName,
            "[UriParam] ${ref.name}(\"$paramName\") at index=$callIndex " +
            "resultReg=v$resultReg plural=$isPlural matchCodes=$matchCodes class=${classDef.type}")

        val values = if (isPlural) {
            scanForwardForListElementValues(instructions, callIndex + 2, resultReg, cfgStrings, classDef.type, paramName)
        } else {
            scanForwardForStringValues(instructions, callIndex + 2, resultReg, cfgStrings).values
        }

        if (matchCodes.isNotEmpty()) {
            // Scoped: associate param with specific match codes
            for (code in matchCodes) {
                queryParamsByMatchCode.getOrPut(code) { mutableSetOf() }.add(paramName)
                if (values.isNotEmpty()) {
                    queryParamValuesByMatchCode
                        .getOrPut(code) { mutableMapOf() }
                        .getOrPut(paramName) { mutableSetOf() }
                        .addAll(values)
                }
            }
        } else {
            // Unscoped: class-level fallback (helper methods without dispatch context)
            queryParamsByClass.getOrPut(classDef.type) { mutableSetOf() }.add(paramName)
            if (values.isNotEmpty()) {
                queryParamValuesByClass
                    .getOrPut(classDef.type) { mutableMapOf() }
                    .getOrPut(paramName) { mutableSetOf() }
                    .addAll(values)
            }
        }

        if (values.isNotEmpty()) {
            DexDebugLog.logFiltered(classDef.type, paramName,
                "[UriParam] FINAL values for \"$paramName\": $values (matchCodes=$matchCodes)")
        }
    }

    /**
     * Handle Uri.getBooleanQueryParameter(key, defaultValue).
     * Extracts the parameter name and the boolean default value.
     */
    private fun handleGetBooleanQueryParameter(
        regStrings: Map<Int, String>,
        regInts: Map<Int, Int>,
        instr: Instruction,
        callIndex: Int,
        classDef: DexBackedClassDef,
        matchDispatch: MatchDispatchInfo?
    ) {
        if (instr !is Instruction35c) return
        // getBooleanQueryParameter(String key, boolean default)
        // registerC = uri, registerD = key, registerE = default
        val paramName = regStrings[instr.registerD] ?: return
        if (paramName.isBlank()) return

        // Extract the boolean default value (0 = false, 1 = true)
        val defaultInt = regInts[instr.registerE]
        val defaultStr = if (defaultInt != null) (defaultInt != 0).toString() else null

        val matchCodes = if (matchDispatch != null) {
            findMatchCodesForInstruction(callIndex, matchDispatch)
        } else {
            emptySet()
        }

        DexDebugLog.logFiltered(classDef.type, paramName,
            "[UriParam] getBooleanQueryParameter(\"$paramName\", default=$defaultStr) at index=$callIndex " +
            "matchCodes=$matchCodes class=${classDef.type}")

        if (matchCodes.isNotEmpty()) {
            for (code in matchCodes) {
                queryParamsByMatchCode.getOrPut(code) { mutableSetOf() }.add(paramName)
                if (defaultStr != null) {
                    queryParamDefaultsByMatchCode
                        .getOrPut(code) { mutableMapOf() }[paramName] = defaultStr
                }
            }
        } else {
            queryParamsByClass.getOrPut(classDef.type) { mutableSetOf() }.add(paramName)
            if (defaultStr != null) {
                queryParamDefaultsByClass
                    .getOrPut(classDef.type) { mutableMapOf() }[paramName] = defaultStr
            }
        }
    }

    // --- Strategy 5: Wrapper method call sites (inter-procedural) ---

    /**
     * Check if this invoke calls a summarized wrapper method. If so, resolve the
     * query param key and optional default value from the caller's registers.
     */
    private fun handleWrapperCallSite(
        ref: MethodReference,
        regStrings: Map<Int, String>,
        regInts: Map<Int, Int>,
        instr: Instruction,
        callIndex: Int,
        classDef: DexBackedClassDef,
        matchDispatch: MatchDispatchInfo?
    ) {
        val sig = buildMethodSignatureFromRef(ref)
        val summary = wrapperSummaries[sig] ?: return

        // Extract the register at the given argument word position
        val keyReg = getInvokeRegisterAtPosition(instr, summary.keyArgWordPos) ?: return
        val paramName = regStrings[keyReg] ?: return
        if (paramName.isBlank()) return

        // Resolve default value if available
        var defaultStr: String? = null
        if (summary.defaultArgWordPos != null) {
            val defaultReg = getInvokeRegisterAtPosition(instr, summary.defaultArgWordPos)
            if (defaultReg != null) {
                // Try string first (for getQueryParameter wrappers with String default)
                defaultStr = regStrings[defaultReg]
                if (defaultStr == null) {
                    // Try int/boolean (for getBooleanQueryParameter wrappers)
                    val intVal = regInts[defaultReg]
                    if (intVal != null) {
                        defaultStr = if (summary.isBooleanWrapper) (intVal != 0).toString() else intVal.toString()
                    }
                }
            }
        }

        val matchCodes = if (matchDispatch != null) {
            findMatchCodesForInstruction(callIndex, matchDispatch)
        } else {
            emptySet()
        }

        DexDebugLog.logFiltered(classDef.type, paramName,
            "[UriParam] wrapper ${ref.definingClass}->${ref.name}(\"$paramName\", default=$defaultStr) " +
            "at index=$callIndex matchCodes=$matchCodes class=${classDef.type}")

        if (matchCodes.isNotEmpty()) {
            for (code in matchCodes) {
                queryParamsByMatchCode.getOrPut(code) { mutableSetOf() }.add(paramName)
                if (defaultStr != null) {
                    queryParamDefaultsByMatchCode
                        .getOrPut(code) { mutableMapOf() }[paramName] = defaultStr
                }
            }
        } else {
            queryParamsByClass.getOrPut(classDef.type) { mutableSetOf() }.add(paramName)
            if (defaultStr != null) {
                queryParamDefaultsByClass
                    .getOrPut(classDef.type) { mutableMapOf() }[paramName] = defaultStr
            }
        }
    }

    /**
     * Extract the register number at a given argument word position from an invoke instruction.
     * For Instruction35c: positions 0-4 map to registerC through registerG.
     * For Instruction3rc (invoke-range): position maps to startRegister + position.
     */
    private fun getInvokeRegisterAtPosition(instr: Instruction, argWordPos: Int): Int? {
        return when (instr) {
            is Instruction35c -> when (argWordPos) {
                0 -> instr.registerC
                1 -> instr.registerD
                2 -> instr.registerE
                3 -> instr.registerF
                4 -> instr.registerG
                else -> null
            }
            is Instruction3rc -> {
                if (argWordPos < instr.registerCount) {
                    instr.startRegister + argWordPos
                } else null
            }
            else -> null
        }
    }

    private fun getMoveResultRegister(instructions: List<Instruction>, callIndex: Int): Int? =
        ForwardValueScanner.getMoveResultRegister(instructions, callIndex)

    /** Delegates to [ForwardValueScanner.scanStringValues]. */
    private fun scanForwardForStringValues(
        instructions: List<Instruction>,
        startIndex: Int,
        resultReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        window: Int = 30
    ): ForwardValueScanner.ValueScanResult {
        return ForwardValueScanner.scanStringValues(instructions, startIndex, resultReg, cfgStrings, window)
    }

    /**
     * Scan forward from a getQueryParameters (plural) result for string values.
     *
     * The result register holds a List<String>. Individual elements are accessed
     * via List.get(int) or Iterator.next(). We track those element registers and
     * then match String.equals / TextUtils.equals on them, using cfgStrings to
     * resolve the compared constant at each call site.
     */
    private fun scanForwardForListElementValues(
        instructions: List<Instruction>,
        startIndex: Int,
        listReg: Int,
        cfgStrings: Array<Map<Int, String>?>,
        sourceClass: String? = null,
        paramName: String? = null
    ): List<String> {
        val values = mutableSetOf<String>()
        val elementRegs = mutableSetOf<Int>()
        val iteratorRegs = mutableSetOf<Int>()

        for (i in startIndex until instructions.size) {
            val instr = instructions[i]
            if (instr !is ReferenceInstruction || instr !is Instruction35c) continue
            val ref = instr.reference
            if (ref !is MethodReference) continue

            // List.get(int) on the list register → element register
            if (ref.name == "get" && ref.definingClass == "Ljava/util/List;" &&
                instr.registerC == listReg
            ) {
                val elemReg = getMoveResultRegister(instructions, i)
                if (elemReg != null) {
                    elementRegs.add(elemReg)
                    DexDebugLog.logFiltered(sourceClass, paramName,
                        "[UriParam]   List.get() at +${i - startIndex} → elementReg=v$elemReg")
                }
            }

            // List.iterator() / Collection.iterator() on the list register
            if (ref.name == "iterator" && instr.registerC == listReg) {
                val iterReg = getMoveResultRegister(instructions, i)
                if (iterReg != null) {
                    iteratorRegs.add(iterReg)
                    DexDebugLog.logFiltered(sourceClass, paramName,
                        "[UriParam]   iterator() at +${i - startIndex} → iterReg=v$iterReg")
                }
            }

            // Iterator.next() on a tracked iterator → element register
            if (ref.name == "next" && instr.registerC in iteratorRegs) {
                val elemReg = getMoveResultRegister(instructions, i)
                if (elemReg != null) {
                    elementRegs.add(elemReg)
                    DexDebugLog.logFiltered(sourceClass, paramName,
                        "[UriParam]   Iterator.next() at +${i - startIndex} → elementReg=v$elemReg")
                }
            }

            if (elementRegs.isEmpty()) continue

            // String.equals / equalsIgnoreCase on an element register
            val isEquals = (ref.definingClass == "Ljava/lang/String;" &&
                    (ref.name == "equals" || ref.name == "equalsIgnoreCase")) ||
                    (ref.definingClass == "Landroid/text/TextUtils;" && ref.name == "equals")
            if (!isEquals) continue

            val strings = cfgStrings[i] ?: continue
            if (instr.registerC in elementRegs) {
                val matched = strings[instr.registerD]
                DexDebugLog.logFiltered(sourceClass, paramName,
                    "[UriParam]   +${i - startIndex}: ${ref.name}(v${instr.registerC}=elem, " +
                    "v${instr.registerD}=${matched ?: "??"}) → ${if (matched != null) "HIT" else "miss"}")
                if (matched != null) values.add(matched)
            } else if (instr.registerD in elementRegs) {
                val matched = strings[instr.registerC]
                DexDebugLog.logFiltered(sourceClass, paramName,
                    "[UriParam]   +${i - startIndex}: ${ref.name}(v${instr.registerC}=${matched ?: "??"}, " +
                    "v${instr.registerD}=elem) → ${if (matched != null) "HIT" else "miss"}")
                if (matched != null) values.add(matched)
            }
        }

        return values.toList()
    }

    // --- Match code dispatch detection (CFG-based) ---

    /**
     * Holds the CFG predecessor edges and the mapping from dispatch target
     * instruction indices to the match code values that reach them.
     */
    private class MatchDispatchInfo(
        val predecessors: Array<List<Int>>,
        val dispatchTargets: Map<Int, Set<Int>>  // targetInstructionIndex → Set<matchCode>
    )

    /**
     * Detects UriMatcher.match() → packed-switch/sparse-switch/if-eq dispatch
     * in a method, and builds a map of which instruction indices are dispatch
     * targets for which match code values.
     *
     * Returns null if no UriMatcher dispatch is found in this method.
     */
    private fun detectMatchDispatch(
        instructions: List<Instruction>,
        cfg: MethodCFG
    ): MatchDispatchInfo? {
        val n = instructions.size

        // Find UriMatcher.match() → move-result matchReg
        var matchReg: Int? = null
        for ((i, instr) in instructions.withIndex()) {
            if (instr is ReferenceInstruction) {
                val ref = instr.reference
                if (ref is MethodReference &&
                    ref.definingClass == "Landroid/content/UriMatcher;" &&
                    ref.name == "match"
                ) {
                    matchReg = getMoveResultRegister(instructions, i)
                    break
                }
            }
        }
        if (matchReg == null) return null

        // Scan linearly to find dispatch instructions on matchReg.
        // Linear int tracking works here because the if-eq/switch chain
        // is on the fall-through path with const loads interleaved.
        val localRegInts = mutableMapOf<Int, Int>()
        val dispatchTargets = mutableMapOf<Int, MutableSet<Int>>()

        for ((i, instr) in instructions.withIndex()) {
            trackIntRegisters(instr, localRegInts)
            val op = instr.opcode

            // packed-switch / sparse-switch on matchReg
            if ((op == Opcode.PACKED_SWITCH || op == Opcode.SPARSE_SWITCH) &&
                instr is OneRegisterInstruction && instr.registerA == matchReg &&
                instr is OffsetInstruction
            ) {
                val payloadAddr = cfg.instructionAddress(i) + instr.codeOffset
                val payloadIdx = cfg.instructionIndex(payloadAddr)
                if (payloadIdx >= 0) {
                    val payload = instructions[payloadIdx]
                    if (payload is SwitchPayload) {
                        for (elem in payload.switchElements) {
                            val targetAddr = cfg.instructionAddress(i) + elem.offset
                            val targetIdx = cfg.instructionIndex(targetAddr)
                            if (targetIdx >= 0) {
                                dispatchTargets.getOrPut(targetIdx) { mutableSetOf() }.add(elem.key)
                            }
                        }
                    }
                }
            }

            // if-eq matchReg, constReg, :target → branch target = constValue
            if (op == Opcode.IF_EQ && instr is TwoRegisterInstruction && instr is OffsetInstruction) {
                val constValue: Int? = when {
                    instr.registerA == matchReg -> localRegInts[instr.registerB]
                    instr.registerB == matchReg -> localRegInts[instr.registerA]
                    else -> null
                }
                if (constValue != null) {
                    val targetAddr = cfg.instructionAddress(i) + instr.codeOffset
                    val targetIdx = cfg.instructionIndex(targetAddr)
                    if (targetIdx >= 0) {
                        dispatchTargets.getOrPut(targetIdx) { mutableSetOf() }.add(constValue)
                    }
                }
            }

            // if-ne matchReg, constReg, :target → fall-through (i+1) = constValue
            if (op == Opcode.IF_NE && instr is TwoRegisterInstruction && instr is OffsetInstruction) {
                val constValue: Int? = when {
                    instr.registerA == matchReg -> localRegInts[instr.registerB]
                    instr.registerB == matchReg -> localRegInts[instr.registerA]
                    else -> null
                }
                if (constValue != null && i + 1 < n) {
                    dispatchTargets.getOrPut(i + 1) { mutableSetOf() }.add(constValue)
                }
            }
        }

        if (dispatchTargets.isEmpty()) return null

        // Build predecessor edges from CFG successors
        val preds = Array(n) { mutableListOf<Int>() }
        for (j in 0 until n) {
            for (s in cfg.successors[j]) {
                preds[s].add(j)
            }
        }
        val predecessors: Array<List<Int>> = Array(n) { preds[it] as List<Int> }

        DexDebugLog.log("[UriParam] Detected UriMatcher dispatch: matchReg=v$matchReg, " +
            "${dispatchTargets.size} dispatch targets, " +
            "codes=${dispatchTargets.values.flatten().toSortedSet()}")

        return MatchDispatchInfo(predecessors, dispatchTargets)
    }

    /**
     * Backward BFS from an instruction to find which match code dispatch
     * targets can reach it. Returns the set of match codes.
     */
    private fun findMatchCodesForInstruction(
        instrIndex: Int,
        info: MatchDispatchInfo
    ): Set<Int> {
        val visited = BooleanArray(info.predecessors.size)
        val queue = ArrayDeque<Int>()
        queue.add(instrIndex)
        visited[instrIndex] = true

        val matchCodes = mutableSetOf<Int>()

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            info.dispatchTargets[idx]?.let { matchCodes.addAll(it) }
            for (pred in info.predecessors[idx]) {
                if (!visited[pred]) {
                    visited[pred] = true
                    queue.add(pred)
                }
            }
        }

        return matchCodes
    }

    // --- Result management ---

    private fun addResult(
        uri: String,
        matchCode: Int?,
        classDef: DexBackedClassDef,
        methodName: String,
        queryParameters: List<String> = emptyList(),
        queryParameterValues: Map<String, List<String>> = emptyMap()
    ) {
        // Exact string dedup
        if (uri in seenUris) return

        // Safe wildcard dedup: only when both URIs share the same non-null match code
        // (proving they go to the same handler). E.g. alarm/#/view and alarm/*/view
        // with matchCode=101 are provably the same endpoint.
        if (matchCode != null) {
            val normalized = normalizeWildcards(uri)
            val existingCode = seenNormalizedByMatchCode[normalized]
            if (existingCode == matchCode) return  // same handler, skip duplicate
            seenNormalizedByMatchCode[normalized] = matchCode
        }

        seenUris.add(uri)

        DexDebugLog.logFiltered(classDef.type, null,
            "[UriPattern] Found URI \"$uri\" matchCode=$matchCode " +
            "class=${classDef.type} method=$methodName" +
            if (queryParameters.isNotEmpty()) " inlineParams=$queryParameters" else "")

        results.add(
            ContentProviderInfo(
                authority = extractAuthority(uri),
                uriPattern = uri,
                matchCode = matchCode,
                associatedColumns = emptyList(),
                queryParameters = queryParameters,
                queryParameterValues = queryParameterValues,
                sourceClass = classDef.type,
                sourceMethod = methodName
            )
        )
    }

    /** Replace both # and * with a common placeholder for wildcard dedup comparison. */
    private fun normalizeWildcards(uri: String): String {
        return uri.split('/').joinToString("/") { segment ->
            if (segment == "#" || segment == "*") "?" else segment
        }
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

    /**
     * Parse query parameters from a URL string containing '?key=value&key2=value2'.
     * Returns (baseUrl, paramMap) or null if no query string present.
     */
    private fun parseUrlQueryParams(url: String): Pair<String, Map<String, List<String>>>? {
        val urlNoFragment = url.substringBefore('#')
        val queryIndex = urlNoFragment.indexOf('?')
        if (queryIndex < 0) return null

        val baseUrl = urlNoFragment.substring(0, queryIndex)
        val queryString = urlNoFragment.substring(queryIndex + 1)
        if (queryString.isBlank()) return null

        val params = mutableMapOf<String, MutableList<String>>()
        for (pair in queryString.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
                val key = decodeUrlComponent(pair.substring(0, eqIdx))
                val value = decodeUrlComponent(pair.substring(eqIdx + 1))
                params.getOrPut(key) { mutableListOf() }.add(value)
            } else if (pair.isNotBlank()) {
                params.getOrPut(decodeUrlComponent(pair)) { mutableListOf() }
            }
        }

        return if (params.isNotEmpty()) baseUrl to params.mapValues { it.value.toList() } else null
    }

    private fun decodeUrlComponent(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) { s }
}
