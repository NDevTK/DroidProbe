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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.droidprobe.app.data.model.ApiEndpoint

/**
 * Extracts API endpoints from DEX bytecode using multiple strategies:
 * 1. Retrofit annotations (@GET, @POST, etc.) combined with pre-scanned baseUrl
 * 2. OkHttp Request.Builder.url() and HttpUrl.parse() calls
 * 3. StringBuilder concatenation that produces http/https URLs
 * 4. String.format() calls with URL format templates (extracts path + query params from %s/%d placeholders)
 * 5. Literal URL strings (fallback, handled externally via StringConstantCollector)
 */
class UrlExtractor(
    private val classIndex: Map<String, DexBackedClassDef>
) {
    // Uri.Builder path segment: either a resolved string or an unresolved method call
    private sealed class UriPathSegment {
        data class Literal(val value: String) : UriPathSegment()
        data class MethodCall(val methodRef: MethodReference) : UriPathSegment()
    }

    private data class UriBuilderState(
        val baseUri: String,
        val pathSegments: MutableList<UriPathSegment> = mutableListOf(),
        val queryParams: MutableList<String> = mutableListOf(),
        val queryParamValues: MutableMap<String, MutableList<String>> = mutableMapOf()
    )

    // Retrofit base URLs discovered in Pass 1.5 (builder class → baseUrl)
    private val retrofitBaseUrls = mutableMapOf<String, String>()
    // Retrofit interface → baseUrl mapping (from create() call analysis)
    private val retrofitInterfaceBaseUrls = mutableMapOf<String, String>()

    // All discovered endpoints, keyed by fullUrl|sourceClass for dedup
    private val endpoints = mutableMapOf<String, ApiEndpoint>()

    private val retrofitAnnotations = mapOf(
        "Lretrofit2/http/GET;" to "GET",
        "Lretrofit2/http/POST;" to "POST",
        "Lretrofit2/http/PUT;" to "PUT",
        "Lretrofit2/http/DELETE;" to "DELETE",
        "Lretrofit2/http/PATCH;" to "PATCH",
        "Lretrofit2/http/HEAD;" to "HEAD",
        "Lretrofit2/http/OPTIONS;" to "OPTIONS",
        "Lretrofit2/http/HTTP;" to null // method comes from annotation element
    )

    // Source type priority for dedup: higher = preferred
    private val sourceTypePriority = mapOf(
        "retrofit" to 5,
        "okhttp" to 4,
        "string_format" to 3,
        "concatenation" to 2,
        "literal" to 1
    )

    /**
     * Pass 1.5: Pre-scan for Retrofit.Builder.baseUrl() calls to discover base URLs.
     * Also detects Retrofit.create(Class) to link interfaces to their base URLs.
     */
    fun preScanRetrofitBuilders(classDef: DexBackedClassDef) {
        val className = classDef.type
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val stringRegs = cfg.computeStringRegisters()

            // Per-method: track the baseUrl and create() target for linking
            var methodBaseUrl: String? = null
            var methodCreateTarget: String? = null

            for ((i, instr) in instructions.withIndex()) {
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference as? MethodReference ?: continue

                // Retrofit2 Builder.baseUrl(String)
                if (ref.definingClass == "Lretrofit2/Retrofit\$Builder;" &&
                    ref.name == "baseUrl" &&
                    ref.parameterTypes.size == 1
                ) {
                    val argReg = getFirstArgReg(instr) ?: continue
                    val url = stringRegs[i]?.get(argReg) ?: continue
                    retrofitBaseUrls[className] = url
                    methodBaseUrl = url
                    DexDebugLog.log("[UrlExtractor] Retrofit baseUrl in $className: $url")
                }

                // Retrofit.create(Class) — resolve the interface class from const-class
                if (ref.definingClass == "Lretrofit2/Retrofit;" &&
                    ref.name == "create" &&
                    ref.parameterTypes.size == 1
                ) {
                    // Look backward for const-class instruction to find the interface type
                    val argReg = getFirstArgReg(instr)
                    if (argReg != null) {
                        for (j in i - 1 downTo maxOf(0, i - 5)) {
                            val prev = instructions[j]
                            if (prev.opcode == com.android.tools.smali.dexlib2.Opcode.CONST_CLASS &&
                                prev is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction &&
                                prev.registerA == argReg &&
                                prev is ReferenceInstruction
                            ) {
                                val typeRef = prev.reference
                                if (typeRef is TypeReference) {
                                    methodCreateTarget = typeRef.type
                                }
                                break
                            }
                        }
                    }
                }
            }

            // Link the interface to the base URL found in the same method
            if (methodBaseUrl != null && methodCreateTarget != null) {
                retrofitInterfaceBaseUrls[methodCreateTarget] = methodBaseUrl
                DexDebugLog.log("[UrlExtractor] Retrofit interface $methodCreateTarget → $methodBaseUrl")
            }
        }
    }

    /**
     * Pass 2: Process a class for API endpoints.
     */
    fun process(classDef: DexBackedClassDef) {
        val className = classDef.type

        // Strategy 1: Retrofit annotations on interface methods
        processRetrofitAnnotations(classDef, className)

        // Strategy 2, 3 & 4: OkHttp + StringBuilder + String.format in method bodies
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val stringRegs = cfg.computeStringRegisters()

            processOkHttpCalls(instructions, stringRegs, className)
            processStringBuilderConcat(instructions, stringRegs, className)
            processStringFormatUrls(instructions, stringRegs, className)
            processUriBuilderCalls(instructions, stringRegs, className)
        }

        // Strategy 4b: Scan class/instance fields for URL format templates
        // Handles @JvmField val / static final fields where the format string
        // is initialized in <init>/<clinit> but read via iget/sget at call sites
        processFieldUrlTemplates(classDef, className)
    }

    private val retrofitParamAnnotations = mapOf(
        "Lretrofit2/http/Query;" to "query",
        "Lretrofit2/http/Path;" to "path",
        "Lretrofit2/http/Header;" to "header",
        "Lretrofit2/http/Field;" to "field",
        "Lretrofit2/http/Body;" to "body",
        "Lretrofit2/http/Part;" to "part",
        "Lretrofit2/http/FieldMap;" to "fieldmap",
        "Lretrofit2/http/QueryMap;" to "querymap"
    )

    /**
     * Strategy 1: Scan Retrofit interface methods for HTTP annotations and parameter annotations.
     */
    private fun processRetrofitAnnotations(classDef: DexBackedClassDef, className: String) {
        for (method in classDef.methods) {
            var path: String? = null
            var resolvedMethod: String? = null

            // Scan method annotations for HTTP method + path
            for (annotation in method.annotations) {
                val httpMethod = retrofitAnnotations[annotation.type] ?: continue
                resolvedMethod = httpMethod

                for (element in annotation.elements) {
                    when (element.name) {
                        "value" -> {
                            val value = element.value
                            if (value is StringEncodedValue) path = value.value
                        }
                        "method" -> {
                            val value = element.value
                            if (value is StringEncodedValue) resolvedMethod = value.value
                        }
                    }
                }
            }

            if (path == null) continue

            // Scan method-level @Headers annotation for static headers
            val queryParams = mutableListOf<String>()
            val pathParams = mutableListOf<String>()
            val headerParams = mutableListOf<String>()
            val headerExamples = mutableMapOf<String, MutableList<String>>()
            var hasBody = false

            for (annotation in method.annotations) {
                if (annotation.type == "Lretrofit2/http/Headers;") {
                    for (element in annotation.elements) {
                        if (element.name == "value") {
                            val value = element.value
                            val headerStrings = when (value) {
                                is ArrayEncodedValue -> value.value.filterIsInstance<StringEncodedValue>().map { it.value }
                                is StringEncodedValue -> listOf(value.value)
                                else -> emptyList()
                            }
                            for (headerStr in headerStrings) {
                                val colonIdx = headerStr.indexOf(':')
                                if (colonIdx <= 0) continue
                                val headerName = headerStr.substring(0, colonIdx).trim()
                                val headerValue = headerStr.substring(colonIdx + 1).trim()
                                if (headerName.isNotEmpty()) {
                                    headerParams.add(headerName)
                                    if (headerValue.isNotEmpty()) {
                                        headerExamples.getOrPut(headerName) { mutableListOf() }.add(headerValue)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Scan parameter annotations for @Query, @Path, @Header, @Body, @Field, @Part, @FieldMap, @QueryMap
            for (param in method.parameters) {
                for (annotation in param.annotations) {
                    val kind = retrofitParamAnnotations[annotation.type] ?: continue
                    if (kind == "body") {
                        hasBody = true
                        continue
                    }
                    // @FieldMap and @QueryMap have no "value" element (dynamic map params)
                    if (kind == "fieldmap") {
                        hasBody = true
                        continue
                    }
                    if (kind == "querymap") {
                        continue  // Cannot extract individual keys from map
                    }
                    // Extract param name from "value" element
                    val paramName = annotation.elements
                        .firstOrNull { it.name == "value" }
                        ?.value
                        ?.let { (it as? StringEncodedValue)?.value }
                        ?: continue

                    when (kind) {
                        "query", "field", "part" -> queryParams.add(paramName)
                        "path" -> pathParams.add(paramName)
                        "header" -> headerParams.add(paramName)
                    }
                }
            }

            val baseUrl = findRetrofitBaseUrl(className)
            val fullUrl = if (baseUrl != null) combineBaseAndPath(baseUrl, path) else path

            addEndpoint(
                ApiEndpoint(
                    fullUrl = fullUrl,
                    baseUrl = baseUrl ?: "",
                    path = path,
                    httpMethod = resolvedMethod,
                    sourceClass = className,
                    sourceType = "retrofit",
                    queryParams = queryParams,
                    pathParams = pathParams,
                    headerParams = headerParams,
                    hasBody = hasBody,
                    headerParamExamples = headerExamples.mapValues { it.value.distinct() }
                        .filterValues { it.isNotEmpty() }
                )
            )
        }
    }

    /**
     * Strategy 2: Detect OkHttp Request.Builder.url() and HttpUrl.parse() calls.
     * Also detects Request.Builder.addHeader() calls in the same method.
     */
    private fun processOkHttpCalls(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>,
        className: String
    ) {
        // First pass: collect all URLs, headers, and header values from this method
        val urls = mutableListOf<String>()
        val headers = mutableListOf<String>()
        val headerExamples = mutableMapOf<String, MutableList<String>>()

        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            val url: String? = when {
                // Request.Builder.url(String)
                ref.definingClass == "Lokhttp3/Request\$Builder;" &&
                    ref.name == "url" &&
                    ref.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;" -> {
                    val argReg = getFirstArgReg(instr) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                // HttpUrl.parse(String) — static method
                ref.definingClass == "Lokhttp3/HttpUrl;" &&
                    ref.name == "parse" &&
                    ref.parameterTypes.size == 1 -> {
                    val argReg = getStaticFirstArgReg(instr) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                // HttpUrl.Companion.parse or get (Kotlin extension)
                ref.definingClass == "Lokhttp3/HttpUrl\$Companion;" &&
                    (ref.name == "parse" || ref.name == "get") -> {
                    val argReg = getFirstArgReg(instr) ?: continue
                    stringRegs[i]?.get(argReg)
                }
                else -> null
            }

            if (url != null && isValidUrl(url)) {
                urls.add(url)
            }

            // Detect Request.Builder.addHeader(name, value) and header(name, value)
            if (ref.definingClass == "Lokhttp3/Request\$Builder;" &&
                (ref.name == "addHeader" || ref.name == "header") &&
                ref.parameterTypes.size == 2
            ) {
                val nameReg = getFirstArgReg(instr)
                if (nameReg != null) {
                    val headerName = stringRegs[i]?.get(nameReg)
                    if (headerName != null && headerName.isNotEmpty()) {
                        headers.add(headerName)
                        // Also extract header value
                        val valReg = when (instr) {
                            is Instruction35c -> instr.registerE
                            is Instruction3rc -> instr.startRegister + 2
                            else -> null
                        }
                        val headerValue = valReg?.let { stringRegs[i]?.get(it) }
                        if (headerValue != null && headerValue.length < 200) {
                            headerExamples.getOrPut(headerName) { mutableListOf() }.add(headerValue)
                        }
                    }
                }
            }
        }

        // Create endpoints: associate all headers found in this method with all URLs
        val headerEx = headerExamples.mapValues { it.value.distinct() }.filterValues { it.isNotEmpty() }
        for (url in urls) {
            val parsed = parseUrl(url)
            addEndpoint(
                ApiEndpoint(
                    fullUrl = url,
                    baseUrl = parsed.first,
                    path = parsed.second,
                    httpMethod = null,
                    sourceClass = className,
                    sourceType = "okhttp",
                    headerParams = headers,
                    headerParamExamples = headerEx
                )
            )
        }
    }

    /**
     * Strategy 3: Track StringBuilder chains that produce URLs.
     * Tracks new-instance → init → append(const-string)* → toString().
     */
    private fun processStringBuilderConcat(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>,
        className: String
    ) {
        // Quick pre-check: skip methods without StringBuilder references
        val hasStringBuilder = instructions.any { instr ->
            instr is ReferenceInstruction &&
                (instr.reference as? MethodReference)?.definingClass == "Ljava/lang/StringBuilder;"
        }
        if (!hasStringBuilder) return

        // Track StringBuilder instances: register → list of appended strings
        // We also track move-object aliases
        val sbBuilders = mutableMapOf<Int, MutableList<String>>()
        val registerAliases = mutableMapOf<Int, Int>() // alias → original

        for ((i, instr) in instructions.withIndex()) {
            val op = instr.opcode

            // Track move-object for register aliasing
            if (op == com.android.tools.smali.dexlib2.Opcode.MOVE_OBJECT ||
                op == com.android.tools.smali.dexlib2.Opcode.MOVE_OBJECT_FROM16 ||
                op == com.android.tools.smali.dexlib2.Opcode.MOVE_OBJECT_16
            ) {
                if (instr is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction) {
                    val dst = instr.registerA
                    val src = instr.registerB
                    val resolvedSrc = registerAliases[src] ?: src
                    if (resolvedSrc in sbBuilders) {
                        registerAliases[dst] = resolvedSrc
                    }
                }
                continue
            }

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            if (ref.definingClass != "Ljava/lang/StringBuilder;") continue

            when (ref.name) {
                "<init>" -> {
                    // StringBuilder.<init>() or StringBuilder.<init>(String)
                    val sbReg = when (instr) {
                        is Instruction35c -> instr.registerC
                        is Instruction3rc -> instr.startRegister
                        else -> continue
                    }

                    val parts = mutableListOf<String>()

                    // Check if init takes a String argument
                    if (ref.parameterTypes.size == 1 &&
                        ref.parameterTypes[0].toString() == "Ljava/lang/String;"
                    ) {
                        val argReg = when (instr) {
                            is Instruction35c -> instr.registerD
                            is Instruction3rc -> instr.startRegister + 1
                            else -> continue
                        }
                        val initStr = stringRegs[i]?.get(argReg)
                        if (initStr != null) {
                            parts.add(initStr)
                        } else {
                            // Unknown initial string — can't reconstruct
                            parts.add("\u0000") // sentinel for unknown
                        }
                    }

                    sbBuilders[sbReg] = parts
                    // Clear any aliases pointing to this reg
                    registerAliases.entries.removeAll { it.value == sbReg }
                }

                "append" -> {
                    if (ref.parameterTypes.size != 1) continue
                    val paramType = ref.parameterTypes[0].toString()

                    val sbReg = when (instr) {
                        is Instruction35c -> instr.registerC
                        is Instruction3rc -> instr.startRegister
                        else -> continue
                    }
                    val resolvedSb = registerAliases[sbReg] ?: sbReg
                    val parts = sbBuilders[resolvedSb] ?: continue

                    if (paramType == "Ljava/lang/String;") {
                        val argReg = when (instr) {
                            is Instruction35c -> instr.registerD
                            is Instruction3rc -> instr.startRegister + 1
                            else -> continue
                        }
                        val str = stringRegs[i]?.get(argReg)
                        if (str != null) {
                            parts.add(str)
                        } else {
                            parts.add("\u0000") // unknown segment
                        }
                    } else {
                        // Non-string append (int, char, etc.) — unknown
                        parts.add("\u0000")
                    }

                    // append returns `this`, update alias for return register
                    if (instr is Instruction35c) {
                        // move-result-object will capture the return
                    }
                }

                "toString" -> {
                    val sbReg = when (instr) {
                        is Instruction35c -> instr.registerC
                        is Instruction3rc -> instr.startRegister
                        else -> continue
                    }
                    val resolvedSb = registerAliases[sbReg] ?: sbReg
                    val parts = sbBuilders[resolvedSb] ?: continue

                    // Only reconstruct when ALL parts are known strings
                    if (parts.any { it == "\u0000" }) continue

                    val result = parts.joinToString("")
                    if (isValidUrl(result)) {
                        val parsed = parseUrl(result)
                        addEndpoint(
                            ApiEndpoint(
                                fullUrl = result,
                                baseUrl = parsed.first,
                                path = parsed.second,
                                httpMethod = null,
                                sourceClass = className,
                                sourceType = "concatenation"
                            )
                        )
                    }

                    // Clean up
                    sbBuilders.remove(resolvedSb)
                    registerAliases.entries.removeAll { it.value == resolvedSb }
                }
            }
        }
    }

    /**
     * Strategy 3b: Detect Uri.Builder pattern.
     * Tracks Uri.parse("base").buildUpon() → appendEncodedPath/appendPath/appendQueryParameter
     * chains through registers. Common in Google SDK code (Maps, Places, etc.).
     *
     * Handles both static string segments and dynamic segments from virtual/abstract method
     * calls by resolving return values across all implementations in classIndex.
     */
    private fun processUriBuilderCalls(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>,
        className: String
    ) {
        val builders = mutableMapOf<Int, UriBuilderState>()
        val registerAliases = mutableMapOf<Int, Int>()
        // Track what method call result each register holds
        val methodResultRegs = mutableMapOf<Int, MethodReference>()

        fun resolve(reg: Int): Int = registerAliases[reg] ?: reg

        for ((i, instr) in instructions.withIndex()) {
            if (instr.opcode == Opcode.MOVE_RESULT_OBJECT && instr is OneRegisterInstruction) {
                if (i > 0) {
                    val prev = instructions[i - 1]
                    if (prev is ReferenceInstruction) {
                        val prevRef = prev.reference as? MethodReference
                        if (prevRef != null) {
                            val defClass = prevRef.definingClass
                            val name = prevRef.name

                            // Track method return value in register for later resolution
                            methodResultRegs[instr.registerA] = prevRef

                            if (defClass == "Landroid/net/Uri;" && name == "parse") {
                                val argReg = when (prev) {
                                    is Instruction35c -> prev.registerC
                                    is Instruction3rc -> prev.startRegister
                                    else -> null
                                }
                                val baseUrl = argReg?.let { stringRegs[i - 1]?.get(it) }
                                if (baseUrl != null && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
                                    builders[instr.registerA] = UriBuilderState(baseUri = baseUrl)
                                }
                            }

                            if (defClass == "Landroid/net/Uri;" && name == "buildUpon") {
                                val thisReg = when (prev) {
                                    is Instruction35c -> prev.registerC
                                    is Instruction3rc -> prev.startRegister
                                    else -> null
                                }
                                if (thisReg != null) {
                                    val resolved = resolve(thisReg)
                                    val state = builders[resolved]
                                    if (state != null) {
                                        builders[instr.registerA] = state
                                        registerAliases[instr.registerA] = resolved
                                    }
                                }
                            }

                            if (defClass == "Landroid/net/Uri\$Builder;" &&
                                (name == "appendEncodedPath" || name == "appendPath" ||
                                    name == "appendQueryParameter")
                            ) {
                                val thisReg = when (prev) {
                                    is Instruction35c -> prev.registerC
                                    is Instruction3rc -> prev.startRegister
                                    else -> null
                                }
                                if (thisReg != null) {
                                    registerAliases[instr.registerA] = resolve(thisReg)
                                }
                            }
                        }
                    }
                }
                continue
            }

            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue
            val defClass = ref.definingClass

            if (defClass != "Landroid/net/Uri\$Builder;") continue

            val thisReg = when (instr) {
                is Instruction35c -> instr.registerC
                is Instruction3rc -> instr.startRegister
                else -> continue
            }
            val resolved = resolve(thisReg)
            val state = builders[resolved] ?: continue

            when (ref.name) {
                "appendEncodedPath", "appendPath" -> {
                    val argReg = when (instr) {
                        is Instruction35c -> instr.registerD
                        is Instruction3rc -> instr.startRegister + 1
                        else -> continue
                    }
                    val segment = stringRegs[i]?.get(argReg)
                    if (segment != null) {
                        state.pathSegments.add(UriPathSegment.Literal(segment.trim('/')))
                    } else {
                        val methodRef = methodResultRegs[argReg]
                        if (methodRef != null) {
                            state.pathSegments.add(UriPathSegment.MethodCall(methodRef))
                        }
                    }
                }

                "appendQueryParameter" -> {
                    val keyReg = when (instr) {
                        is Instruction35c -> instr.registerD
                        is Instruction3rc -> instr.startRegister + 1
                        else -> continue
                    }
                    val paramName = stringRegs[i]?.get(keyReg)
                    if (paramName != null) {
                        state.queryParams.add(paramName)
                        // Also extract the value register for example values
                        val valReg = when (instr) {
                            is Instruction35c -> instr.registerE
                            is Instruction3rc -> instr.startRegister + 2
                            else -> null
                        }
                        val paramValue = valReg?.let { stringRegs[i]?.get(it) }
                        if (paramValue != null && paramValue.length < 200) {
                            state.queryParamValues
                                .getOrPut(paramName) { mutableListOf() }
                                .add(paramValue)
                        }
                    }
                }

                "build" -> {
                    emitUriBuilderEndpoints(state, className)
                }
            }
        }
    }

    /**
     * Emit endpoints from a completed Uri.Builder state.
     * If all segments are literals, emit one endpoint.
     * If any segment is a MethodCall, resolve all concrete return values across
     * subclass implementations and emit one endpoint per resolved value.
     * Also scans subclasses for additional query params from Map.put() patterns.
     */
    private fun emitUriBuilderEndpoints(state: UriBuilderState, className: String) {
        val parsed = try { java.net.URI(state.baseUri.trimEnd('/')) } catch (_: Exception) { return }
        val baseUrl = "${parsed.scheme}://${parsed.host}" +
            (if (parsed.port > 0) ":${parsed.port}" else "")
        val basePath = (parsed.rawPath ?: "").trimEnd('/')

        // Check if any segment is a MethodCall that needs resolution
        val hasMethodCalls = state.pathSegments.any { it is UriPathSegment.MethodCall }

        if (!hasMethodCalls) {
            // All literals — emit single endpoint
            val pathSuffix = if (state.pathSegments.isNotEmpty()) {
                "/" + state.pathSegments.joinToString("/") { (it as UriPathSegment.Literal).value }
            } else ""
            val fullPath = basePath + pathSuffix
            if (fullPath.isEmpty() && state.queryParams.isEmpty()) return
            emitSingleUriBuilderEndpoint(baseUrl, fullPath, state.queryParams, state.queryParamValues, className)
            return
        }

        // Resolve MethodCall segments by finding all concrete implementations
        val methodCallSegments = state.pathSegments.filterIsInstance<UriPathSegment.MethodCall>()
        // For simplicity, resolve only the first MethodCall (the common pattern has exactly one)
        val targetMethod = methodCallSegments.firstOrNull()?.methodRef ?: return

        val resolvedValues = resolveMethodReturnStrings(targetMethod)

        if (resolvedValues.isEmpty()) {
            // Can't resolve — emit with known segments only, skipping the dynamic part
            val literalPath = state.pathSegments
                .filterIsInstance<UriPathSegment.Literal>()
                .joinToString("/") { it.value }
            val fullPath = if (literalPath.isNotEmpty()) "$basePath/$literalPath" else basePath
            if (fullPath.isNotEmpty() || state.queryParams.isNotEmpty()) {
                emitSingleUriBuilderEndpoint(baseUrl, fullPath, state.queryParams, state.queryParamValues, className)
            }
            return
        }

        // For each resolved value, also try to extract subclass-specific query params + values
        for ((implClass, returnValue) in resolvedValues) {
            val segments = state.pathSegments.map { segment ->
                when (segment) {
                    is UriPathSegment.Literal -> segment.value
                    is UriPathSegment.MethodCall -> returnValue.trim('/')
                }
            }
            val pathSuffix = "/" + segments.joinToString("/")
            val fullPath = basePath + pathSuffix

            // Collect query params + examples: base params + subclass-specific params
            val allParams = state.queryParams.toMutableList()
            val allExamples = state.queryParamValues.mapValues { it.value.toMutableList() }.toMutableMap()
            val (subclassParams, subclassExamples) = extractMapPutEntries(implClass)
            allParams.addAll(subclassParams)
            for ((k, v) in subclassExamples) {
                allExamples.getOrPut(k) { mutableListOf() }.addAll(v)
            }

            emitSingleUriBuilderEndpoint(baseUrl, fullPath, allParams, allExamples, implClass)
        }
    }

    private fun emitSingleUriBuilderEndpoint(
        baseUrl: String,
        fullPath: String,
        queryParams: List<String>,
        queryParamExamples: Map<String, List<String>> = emptyMap(),
        sourceClass: String
    ) {
        val fullUrl = "$baseUrl$fullPath"
        DexDebugLog.log("[UrlExtractor] Uri.Builder in $sourceClass: $fullUrl params=$queryParams examples=$queryParamExamples")

        addEndpoint(
            ApiEndpoint(
                fullUrl = fullUrl,
                baseUrl = baseUrl,
                path = fullPath,
                httpMethod = "GET",
                sourceClass = sourceClass,
                sourceType = "uri_builder",
                queryParams = queryParams.distinct(),
                queryParamExamples = queryParamExamples.mapValues { it.value.distinct() }
                    .filterValues { it.isNotEmpty() }
            )
        )
    }

    /**
     * Resolve all concrete return strings for a virtual/abstract method.
     * Scans all classes in classIndex that override the given method and
     * returns a const-string.
     * Returns list of (implementingClass, returnValue) pairs.
     */
    private fun resolveMethodReturnStrings(methodRef: MethodReference): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val targetName = methodRef.name
        val targetProto = "${methodRef.returnType}(${methodRef.parameterTypes.joinToString("")})"

        for ((smaliName, classDef) in classIndex) {
            // Check if this class extends/implements the declaring class
            if (classDef.superclass != methodRef.definingClass &&
                !classDef.interfaces.contains(methodRef.definingClass)
            ) continue

            // Find the overriding method
            for (method in classDef.methods) {
                if (method.name != targetName) continue
                val proto = "${method.returnType}(${method.parameterTypes.joinToString("")})"
                if (proto != targetProto) continue

                val impl = method.implementation ?: continue
                // Look for a simple "const-string → return-object" pattern
                val returnString = extractSimpleReturnString(impl.instructions.toList())
                if (returnString != null) {
                    results.add(smaliName to returnString)
                    DexDebugLog.log("[UrlExtractor] Resolved $targetName in $smaliName → \"$returnString\"")
                }
                break
            }
        }
        return results
    }

    /**
     * Check if a method body is a simple "const-string vN, str; return-object vN" pattern.
     * Returns the string if so, null otherwise.
     */
    private fun extractSimpleReturnString(instructions: List<Instruction>): String? {
        val constStrings = mutableMapOf<Int, String>()
        for (instr in instructions) {
            val op = instr.opcode
            if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) {
                if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                    val strRef = instr.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
                    if (strRef != null) {
                        constStrings[instr.registerA] = strRef.string
                    }
                }
            }
            if (op == Opcode.RETURN_OBJECT && instr is OneRegisterInstruction) {
                return constStrings[instr.registerA]
            }
        }
        return null
    }

    /**
     * Extract Map key names from a class's methods that call Map.put(key, value)
     * where the key is a const-string. This catches the Google Places SDK pattern
     * where subclasses return query params via m8149e(map, "paramName", value).
     */
    /**
     * Extract Map.put(key, value) entries from a class's methods.
     * Returns both the keys list (for param names) and a key→values map (for examples).
     */
    private fun extractMapPutEntries(smaliClassName: String): Pair<List<String>, Map<String, List<String>>> {
        val classDef = classIndex[smaliClassName] ?: return Pair(emptyList(), emptyMap())
        val keys = mutableListOf<String>()
        val examples = mutableMapOf<String, MutableList<String>>()

        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            val constStrings = mutableMapOf<Int, String>()
            val constInts = mutableMapOf<Int, Int>()

            for (instr in instructions) {
                val op = instr.opcode
                if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) {
                    if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                        val strRef = instr.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference
                        if (strRef != null) {
                            constStrings[instr.registerA] = strRef.string
                        }
                    }
                    continue
                }
                // Track integer constants for numeric example values
                if (op == Opcode.CONST || op == Opcode.CONST_4 || op == Opcode.CONST_16 ||
                    op == Opcode.CONST_HIGH16
                ) {
                    if (instr is OneRegisterInstruction && instr is com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction) {
                        constInts[instr.registerA] = instr.wideLiteral.toInt()
                    }
                    continue
                }

                // Match: invoke-*.* Map.put(Object, Object) or helper(Map, String, Object)
                if (instr !is ReferenceInstruction) continue
                val ref = instr.reference as? MethodReference ?: continue

                val mapClasses = setOf(
                    "Ljava/util/Map;", "Ljava/util/HashMap;",
                    "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;"
                )
                val isMapPut = ref.definingClass in mapClasses && ref.name == "put"

                // Also match static helper methods like m8149e(Map, String, Object)
                val isStaticHelper = ref.parameterTypes.size == 3 &&
                    ref.parameterTypes[0].toString() in mapClasses &&
                    ref.parameterTypes[1].toString() == "Ljava/lang/String;"

                if (!isMapPut && !isStaticHelper) continue

                // Find the key and value argument registers
                val keyReg = when {
                    isMapPut && instr is Instruction35c -> instr.registerD
                    isMapPut && instr is Instruction3rc -> instr.startRegister + 1
                    isStaticHelper && instr is Instruction35c -> instr.registerD
                    isStaticHelper && instr is Instruction3rc -> instr.startRegister + 1
                    else -> continue
                }
                val valReg = when {
                    isMapPut && instr is Instruction35c -> instr.registerE
                    isMapPut && instr is Instruction3rc -> instr.startRegister + 2
                    isStaticHelper && instr is Instruction35c -> instr.registerE
                    isStaticHelper && instr is Instruction3rc -> instr.startRegister + 2
                    else -> null
                }

                val keyStr = constStrings[keyReg]
                if (keyStr != null && !keyStr.contains(" ") && keyStr.length < 50) {
                    keys.add(keyStr)
                    // Extract value — could be string or int
                    val valStr = valReg?.let { constStrings[it] }
                    val valInt = valReg?.let { constInts[it] }
                    val exampleValue = valStr ?: valInt?.toString()
                    if (exampleValue != null && exampleValue.length < 200) {
                        examples.getOrPut(keyStr) { mutableListOf() }.add(exampleValue)
                    }
                }
            }
        }
        return Pair(keys, examples)
    }

    /**
     * Strategy 4: Detect String.format() calls where the format template is a URL
     * and the format string is directly available in registers (const-string in same method).
     */
    private fun processStringFormatUrls(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>,
        className: String
    ) {
        for ((i, instr) in instructions.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            if (ref.definingClass != "Ljava/lang/String;" || ref.name != "format") continue

            val formatStringReg: Int? = when (ref.parameterTypes.size) {
                2 -> getStaticFirstArgReg(instr)
                3 -> when (instr) {
                    is Instruction35c -> if (instr.registerCount >= 2) instr.registerD else null
                    is Instruction3rc -> if (instr.registerCount >= 2) instr.startRegister + 1 else null
                    else -> null
                }
                else -> null
            }
            if (formatStringReg == null) continue

            val formatStr = stringRegs[i]?.get(formatStringReg) ?: continue
            if (!formatStr.contains('%')) continue
            parseUrlFormatTemplate(formatStr, className, "String.format")
        }
    }

    /**
     * Strategy 4b: Scan class fields for URL format templates.
     * Handles @JvmField val / static final fields where the format string is
     * initialized in <init>/<clinit> but read via iget/sget at String.format() call sites,
     * which computeStringRegisters cannot resolve.
     *
     * Scans:
     * 1. Static field initial values (compile-time constants via field.initialValue)
     * 2. Constructor (<init> / <clinit>) bodies for const-string → iput-object/sput-object
     *    patterns that store URL format strings into fields
     */
    private fun processFieldUrlTemplates(classDef: DexBackedClassDef, className: String) {
        val fieldStrings = mutableMapOf<String, String>() // fieldName → string value

        // Source 1: Static field initial values (available for static final String fields)
        for (field in classDef.staticFields) {
            val value = field.initialValue
            if (value is StringEncodedValue) {
                fieldStrings[field.name] = value.value
            }
        }

        // Source 2: Scan <init> and <clinit> for const-string → iput-object/sput-object
        for (method in classDef.methods) {
            val methodName = method.name
            if (methodName != "<init>" && methodName != "<clinit>") continue

            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()

            // Track const-string results per register
            val constStrings = mutableMapOf<Int, String>()

            for (instr in instructions) {
                val op = instr.opcode

                // const-string vX, "..."
                if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) {
                    if (instr is OneRegisterInstruction && instr is ReferenceInstruction) {
                        val strRef = instr.reference as? StringReference
                        if (strRef != null) {
                            constStrings[instr.registerA] = strRef.string
                        }
                    }
                    continue
                }

                // iput-object vValue, vObj, Field  or  sput-object vValue, Field
                if (op == Opcode.IPUT_OBJECT || op == Opcode.SPUT_OBJECT) {
                    if (instr is ReferenceInstruction) {
                        val fieldRef = instr.reference as? FieldReference ?: continue
                        // Only capture fields on this class
                        if (fieldRef.definingClass != className) continue
                        if (fieldRef.type != "Ljava/lang/String;") continue

                        val valueReg = when {
                            op == Opcode.SPUT_OBJECT && instr is OneRegisterInstruction ->
                                instr.registerA
                            op == Opcode.IPUT_OBJECT && instr is TwoRegisterInstruction ->
                                instr.registerA
                            else -> continue
                        }
                        val str = constStrings[valueReg]
                        if (str != null) {
                            fieldStrings[fieldRef.name] = str
                        }
                    }
                }

                // Any other instruction that writes to a register invalidates our tracking
                if (op.setsRegister() && instr is OneRegisterInstruction) {
                    constStrings.remove(instr.registerA)
                }
            }
        }

        // Now check all collected field strings for URL format templates
        for ((fieldName, formatStr) in fieldStrings) {
            if (!formatStr.startsWith("http://") && !formatStr.startsWith("https://")) continue
            // Must contain format specifiers to be a template (otherwise it's just a literal URL)
            if (!formatStr.contains('%')) continue

            parseUrlFormatTemplate(formatStr, className, fieldName)
        }
    }

    /**
     * Parse a URL format template string and emit an ApiEndpoint.
     * Shared by both processStringFormatUrls (register-tracked) and
     * processFieldUrlTemplates (field-scanned).
     */
    private fun parseUrlFormatTemplate(formatStr: String, className: String, source: String) {
        // Strip format specifiers to get a clean URL template for validation
        val cleanUrl = formatStr.replace(Regex("%\\d*\\$?[sdflLx]"), "PLACEHOLDER")

        val uri = try { java.net.URI(cleanUrl) } catch (_: Exception) { return }
        if (uri.host.isNullOrEmpty()) return

        val baseUrl = "${uri.scheme}://${uri.host}" +
            (if (uri.port > 0) ":${uri.port}" else "")
        val path = uri.rawPath ?: ""

        // Extract query param names from the format template
        val queryParams = mutableListOf<String>()
        val queryString = formatStr.substringAfter('?', "")
        if (queryString.isNotEmpty()) {
            for (param in queryString.split('&')) {
                val name = param.substringBefore('=').trim()
                if (name.isNotEmpty() && !name.startsWith("%")) {
                    queryParams.add(name)
                }
            }
        }

        val fullUrl = "$baseUrl$path"
        DexDebugLog.log("[UrlExtractor] Format template URL in $className ($source): $fullUrl params=$queryParams")

        addEndpoint(
            ApiEndpoint(
                fullUrl = fullUrl,
                baseUrl = baseUrl,
                path = path,
                httpMethod = "GET",
                sourceClass = className,
                sourceType = "string_format",
                queryParams = queryParams
            )
        )
    }

    /**
     * Add literal URL endpoints from StringConstantCollector (Strategy 5).
     * Called after all passes complete with URLs not already covered.
     */
    fun addLiteralUrls(urlsWithSource: List<Pair<String, String>>) {
        for ((url, sourceClass) in urlsWithSource) {
            val key = "$url|$sourceClass"
            if (key in endpoints) continue // already found by richer strategy

            val parsed = parseUrl(url)
            addEndpoint(
                ApiEndpoint(
                    fullUrl = url,
                    baseUrl = parsed.first,
                    path = parsed.second,
                    httpMethod = null,
                    sourceClass = sourceClass,
                    sourceType = "literal"
                )
            )
        }
    }

    fun getResults(): List<ApiEndpoint> = endpoints.values.toList()

    // --- Helpers ---

    private fun addEndpoint(endpoint: ApiEndpoint) {
        val key = "${endpoint.httpMethod ?: "?"}|${endpoint.fullUrl}|${endpoint.sourceClass}"
        val existing = endpoints[key]
        if (existing != null) {
            val existingPriority = sourceTypePriority[existing.sourceType] ?: 0
            val newPriority = sourceTypePriority[endpoint.sourceType] ?: 0
            if (newPriority <= existingPriority) {
                // Merge examples from lower priority into existing
                val mergedQueryEx = mergeExampleMaps(existing.queryParamExamples, endpoint.queryParamExamples)
                val mergedHeaderEx = mergeExampleMaps(existing.headerParamExamples, endpoint.headerParamExamples)
                if (mergedQueryEx != existing.queryParamExamples || mergedHeaderEx != existing.headerParamExamples) {
                    endpoints[key] = existing.copy(
                        queryParamExamples = mergedQueryEx,
                        headerParamExamples = mergedHeaderEx
                    )
                }
                return
            }
            // Merge examples from existing into new (higher priority)
            endpoints[key] = endpoint.copy(
                queryParamExamples = mergeExampleMaps(endpoint.queryParamExamples, existing.queryParamExamples),
                headerParamExamples = mergeExampleMaps(endpoint.headerParamExamples, existing.headerParamExamples)
            )
            return
        }
        endpoints[key] = endpoint
    }

    private fun mergeExampleMaps(
        a: Map<String, List<String>>,
        b: Map<String, List<String>>
    ): Map<String, List<String>> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        val result = a.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((k, v) in b) {
            val list = result.getOrPut(k) { mutableListOf() } as MutableList
            for (item in v) {
                if (item !in list) list.add(item)
            }
        }
        return result
    }

    private fun findRetrofitBaseUrl(interfaceClass: String): String? {
        // Check interface → baseUrl mapping from create() call analysis
        retrofitInterfaceBaseUrls[interfaceClass]?.let { return it }

        // Check if the class itself has a base URL (unlikely for interfaces but possible)
        retrofitBaseUrls[interfaceClass]?.let { return it }

        // If there's exactly one global base URL, use it
        if (retrofitBaseUrls.size == 1) return retrofitBaseUrls.values.first()

        return null
    }

    private fun combineBaseAndPath(baseUrl: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trimEnd('/')
        val p = path.trimStart('/')
        return if (p.isEmpty()) base else "$base/$p"
    }

    private fun isValidUrl(s: String): Boolean {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false
        if (s.length < 10) return false
        return try {
            val uri = java.net.URI(s)
            uri.host != null && uri.host.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /** Returns (baseUrl, path) — baseUrl is scheme://host, path is the rest. */
    private fun parseUrl(url: String): Pair<String, String> {
        return try {
            val uri = java.net.URI(url)
            val base = "${uri.scheme}://${uri.host}" +
                (if (uri.port > 0) ":${uri.port}" else "")
            val path = uri.rawPath ?: ""
            base to path
        } catch (_: Exception) {
            url to ""
        }
    }

    /** Get the first argument register for an instance method call (skipping `this`). */
    private fun getFirstArgReg(instr: Instruction): Int? {
        return when (instr) {
            is Instruction35c -> if (instr.registerCount >= 2) instr.registerD else null
            is Instruction3rc -> if (instr.registerCount >= 2) instr.startRegister + 1 else null
            else -> null
        }
    }

    /** Get the first argument register for a static method call. */
    private fun getStaticFirstArgReg(instr: Instruction): Int? {
        return when (instr) {
            is Instruction35c -> if (instr.registerCount >= 1) instr.registerC else null
            is Instruction3rc -> if (instr.registerCount >= 1) instr.startRegister else null
            else -> null
        }
    }
}
