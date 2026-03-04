package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.droidprobe.app.data.model.ApiEndpoint

/**
 * Extracts API endpoints from DEX bytecode using multiple strategies:
 * 1. Retrofit annotations (@GET, @POST, etc.) combined with pre-scanned baseUrl
 * 2. OkHttp Request.Builder.url() and HttpUrl.parse() calls
 * 3. StringBuilder concatenation that produces http/https URLs
 * 4. Literal URL strings (fallback, handled externally via StringConstantCollector)
 */
class UrlExtractor(
    private val classIndex: Map<String, DexBackedClassDef>
) {
    // Retrofit base URLs discovered in Pass 1.5 (class → baseUrl)
    private val retrofitBaseUrls = mutableMapOf<String, String>()

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
        "retrofit" to 4,
        "okhttp" to 3,
        "concatenation" to 2,
        "literal" to 1
    )

    /**
     * Pass 1.5: Pre-scan for Retrofit.Builder.baseUrl() calls to discover base URLs.
     */
    fun preScanRetrofitBuilders(classDef: DexBackedClassDef) {
        val className = classDef.type
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val stringRegs = cfg.computeStringRegisters()

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
                    DexDebugLog.log("[UrlExtractor] Retrofit baseUrl in $className: $url")
                }
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

        // Strategy 2 & 3: OkHttp + StringBuilder in method bodies
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            val instructions = impl.instructions.toList()
            if (instructions.isEmpty()) continue

            val cfg = MethodCFG(instructions, impl.tryBlocks)
            val stringRegs = cfg.computeStringRegisters()

            processOkHttpCalls(instructions, stringRegs, className)
            processStringBuilderConcat(instructions, stringRegs, className)
        }
    }

    /**
     * Strategy 1: Scan Retrofit interface methods for HTTP annotations.
     */
    private fun processRetrofitAnnotations(classDef: DexBackedClassDef, className: String) {
        for (method in classDef.methods) {
            for (annotation in method.annotations) {
                val annotationType = annotation.type
                val httpMethod = retrofitAnnotations[annotationType] ?: continue

                // Extract path from annotation "value" element
                var path: String? = null
                var resolvedMethod = httpMethod

                for (element in annotation.elements) {
                    when (element.name) {
                        "value" -> {
                            val value = element.value
                            if (value is StringEncodedValue) {
                                path = value.value
                            }
                        }
                        "method" -> {
                            // For @HTTP annotation, method is specified separately
                            val value = element.value
                            if (value is StringEncodedValue) {
                                resolvedMethod = value.value
                            }
                        }
                    }
                }

                if (path == null) continue

                // Find base URL: check this class, then try single global base URL
                val baseUrl = findRetrofitBaseUrl(className)
                val fullUrl = if (baseUrl != null) {
                    combineBaseAndPath(baseUrl, path)
                } else {
                    path
                }

                addEndpoint(
                    ApiEndpoint(
                        fullUrl = fullUrl,
                        baseUrl = baseUrl ?: "",
                        path = path,
                        httpMethod = resolvedMethod,
                        sourceClass = className,
                        sourceType = "retrofit"
                    )
                )
            }
        }
    }

    /**
     * Strategy 2: Detect OkHttp Request.Builder.url() and HttpUrl.parse() calls.
     */
    private fun processOkHttpCalls(
        instructions: List<Instruction>,
        stringRegs: Array<Map<Int, String>?>,
        className: String
    ) {
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
                val parsed = parseUrl(url)
                addEndpoint(
                    ApiEndpoint(
                        fullUrl = url,
                        baseUrl = parsed.first,
                        path = parsed.second,
                        httpMethod = null,
                        sourceClass = className,
                        sourceType = "okhttp"
                    )
                )
            }
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
     * Add literal URL endpoints from StringConstantCollector (Strategy 4).
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
        val key = "${endpoint.fullUrl}|${endpoint.sourceClass}"
        val existing = endpoints[key]
        if (existing != null) {
            val existingPriority = sourceTypePriority[existing.sourceType] ?: 0
            val newPriority = sourceTypePriority[endpoint.sourceType] ?: 0
            if (newPriority <= existingPriority) return
        }
        endpoints[key] = endpoint
    }

    private fun findRetrofitBaseUrl(interfaceClass: String): String? {
        // Check if the class itself has a base URL (unlikely for interfaces but possible)
        retrofitBaseUrls[interfaceClass]?.let { return it }

        // If there's exactly one global base URL, use it
        if (retrofitBaseUrls.size == 1) return retrofitBaseUrls.values.first()

        // Could try to trace which Retrofit instance uses this interface,
        // but that's complex. Return null to just use path.
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
