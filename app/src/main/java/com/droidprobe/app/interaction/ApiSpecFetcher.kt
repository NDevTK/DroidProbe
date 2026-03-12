package com.droidprobe.app.interaction

import android.util.Log
import com.droidprobe.app.data.model.ApiEndpoint
import com.droidprobe.app.data.model.DiscoveryDocument
import com.droidprobe.app.data.model.DiscoveryMethod
import com.droidprobe.app.data.model.DiscoveryParameter
import com.droidprobe.app.data.model.DiscoveryResource
import com.droidprobe.app.data.model.ExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and parses API specification documents from multiple formats:
 * - Google Discovery REST ($discovery/rest)
 * - OpenAPI 3.x (openapi.json, openapi.yaml)
 * - Swagger 2.0 (swagger.json, swagger/v1/swagger.json)
 *
 * Implements Google-specific authentication techniques from brutecat research:
 * - X-Goog-Api-Key header (preferred over query param for Google APIs)
 * - X-Goog-Spatula header (keyless auth from package name + cert SHA1)
 * - Staging discovery (staging-*.sandbox.googleapis.com)
 * - Visibility labels (?labels=PANTHEON for hidden endpoints)
 * - Scope discovery from Www-Authenticate 403 responses
 * - ProtoJson parameter leak (application/json+protobuf type confusion)
 *
 * All formats are normalized into the common DiscoveryDocument model.
 */
class ApiSpecFetcher {

    companion object {
        private const val TAG = "ApiSpecFetcher"

        /** Well-known spec document paths to probe, in priority order. */
        private val SPEC_PATHS = listOf(
            // Google Discovery
            "/\$discovery/rest",
            // OpenAPI / Swagger common paths
            "/openapi.json",
            "/swagger.json",
            "/v3/api-docs",
            "/v2/api-docs",
            "/api-docs",
            "/swagger/v1/swagger.json",
            "/api/swagger.json",
            "/_swagger",
            "/docs/spec.json",
        )

        /** Known visibility labels that unlock hidden endpoints in Google discovery docs. */
        private val VISIBILITY_LABELS = listOf("PANTHEON")
    }

    /**
     * Probes a base URL for any known API spec format.
     * For googleapis.com hosts: sends API key via X-Goog-Api-Key header,
     * tries X-Goog-Spatula fallback, probes staging, and tries visibility labels.
     * Returns the parsed DiscoveryDocument or an error.
     */
    suspend fun fetchSpec(
        rootUrl: String,
        apiKey: String? = null,
        packageName: String? = null,
        certSha1: String? = null
    ): Result<DiscoveryDocument> =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(rootUrl)
            val isGoogleApi = base.contains("googleapis.com") || base.contains("clients6.google.com")
            Log.d(TAG, "fetchSpec: base=$base isGoogle=$isGoogleApi apiKey=${if (apiKey.isNullOrBlank()) "none" else "present"}")

            // Build auth headers for Google APIs
            val headers = mutableMapOf<String, String>()
            if (isGoogleApi && !apiKey.isNullOrBlank()) {
                headers["X-Goog-Api-Key"] = apiKey
            }
            if (isGoogleApi && !packageName.isNullOrBlank() && !certSha1.isNullOrBlank()) {
                headers["X-Goog-Spatula"] = buildSpatulaHeader(packageName, certSha1)
            }
            Log.d(TAG, "fetchSpec: headers=${headers.keys}")

            var lastError: Exception? = null

            // Strategy 1: Direct discovery with API key header
            for (path in SPEC_PATHS) {
                val url = if (!isGoogleApi && !apiKey.isNullOrBlank()) {
                    // Non-Google APIs: append key as query param (legacy behavior)
                    base + path + (if ('?' in path) "&" else "?") +
                        "key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
                } else {
                    base + path
                }
                try {
                    val body = httpGet(url, headers)
                    if (body == null) {
                        Log.d(TAG, "fetchSpec: $url → non-2xx (skipped)")
                        continue
                    }
                    Log.d(TAG, "fetchSpec: $url → ${body.length} bytes, starts=${body.take(100)}")
                    val json = JSONObject(body)
                    val doc = detectAndParse(json, base)
                    if (doc != null) {
                        Log.d(TAG, "fetchSpec: SUCCESS via $url → ${doc.name} (${doc.resources.size} resources)")
                        return@withContext Result.success(doc)
                    }
                    Log.d(TAG, "fetchSpec: $url → JSON parsed but detectAndParse returned null")
                } catch (e: Exception) {
                    Log.d(TAG, "fetchSpec: $url → exception: ${e.javaClass.simpleName}: ${e.message}")
                    lastError = e
                }
            }
            Log.d(TAG, "fetchSpec: Strategy 1 (direct) exhausted all paths")

            // Strategy 2 (Google only): Try with Spatula header if no API key
            if (isGoogleApi && apiKey.isNullOrBlank() && !packageName.isNullOrBlank() && !certSha1.isNullOrBlank()) {
                val spatulaUrl = "$base/\$discovery/rest"
                val spatulaHeaders = mapOf("X-Goog-Spatula" to buildSpatulaHeader(packageName, certSha1))
                Log.d(TAG, "fetchSpec: Strategy 2 (Spatula-only) → $spatulaUrl")
                try {
                    val body = httpGet(spatulaUrl, spatulaHeaders) ?: null
                    if (body != null) {
                        Log.d(TAG, "fetchSpec: Spatula → ${body.length} bytes")
                        val doc = detectAndParse(JSONObject(body), base)
                        if (doc != null) {
                            Log.d(TAG, "fetchSpec: SUCCESS via Spatula → ${doc.name}")
                            return@withContext Result.success(doc)
                        }
                    } else {
                        Log.d(TAG, "fetchSpec: Spatula → non-2xx")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "fetchSpec: Spatula → exception: ${e.message}")
                    lastError = e
                }
            }

            // Strategy 3 (Google only): Try visibility labels for richer docs
            if (isGoogleApi && headers.isNotEmpty()) {
                for (label in VISIBILITY_LABELS) {
                    val labelUrl = "$base/\$discovery/rest?labels=$label"
                    try {
                        val body = httpGet(labelUrl, headers)
                        if (body == null) {
                            Log.d(TAG, "fetchSpec: $labelUrl → non-2xx (skipped)")
                            continue
                        }
                        Log.d(TAG, "fetchSpec: $labelUrl → ${body.length} bytes")
                        val json = JSONObject(body)
                        val doc = detectAndParse(json, base)
                        if (doc != null) {
                            Log.d(TAG, "fetchSpec: SUCCESS via labels=$label → ${doc.name}")
                            return@withContext Result.success(doc)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "fetchSpec: $labelUrl → exception: ${e.message}")
                        lastError = e
                    }
                }
            }

            Log.w(TAG, "fetchSpec: ALL strategies failed for $base, lastError=${lastError?.message}")
            Result.failure(lastError ?: Exception("No API specification found at $base"))
        }

    /**
     * Fetch the staging version of a Google API discovery document.
     * Staging docs at staging-*.sandbox.googleapis.com often contain
     * richer documentation with internal comments.
     */
    suspend fun fetchStagingDiscovery(
        rootUrl: String,
        apiKey: String? = null,
        packageName: String? = null,
        certSha1: String? = null
    ): Result<DiscoveryDocument> = withContext(Dispatchers.IO) {
        val host = try { URL(rootUrl).host } catch (_: Exception) { return@withContext Result.failure(Exception("Invalid URL")) }
        if (!host.endsWith("googleapis.com")) {
            return@withContext Result.failure(Exception("Staging only available for googleapis.com"))
        }

        // Transform: people-pa.googleapis.com → staging-people-pa.sandbox.googleapis.com
        val prefix = host.removeSuffix(".googleapis.com")
        val stagingHost = "staging-$prefix.sandbox.googleapis.com"
        val stagingUrl = "https://$stagingHost"

        val headers = mutableMapOf<String, String>()
        if (!apiKey.isNullOrBlank()) headers["X-Goog-Api-Key"] = apiKey
        if (!packageName.isNullOrBlank() && !certSha1.isNullOrBlank()) {
            headers["X-Goog-Spatula"] = buildSpatulaHeader(packageName, certSha1)
        }

        try {
            val body = httpGet("$stagingUrl/\$discovery/rest", headers) ?: return@withContext Result.failure(
                Exception("Staging discovery returned no content")
            )
            val doc = detectAndParse(JSONObject(body), stagingUrl)
                ?: return@withContext Result.failure(Exception("Unrecognized spec format from staging"))
            Result.success(doc)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Strips default ports (:443 for https, :80 for http) and normalizes trailing slashes.
     */
    private fun normalizeBaseUrl(rootUrl: String): String {
        var base = rootUrl.trimEnd('/')
        // Remove default ports that can confuse URL parsing when combined with $discovery paths
        base = base.replace(Regex("^(https://[^/:]+):443(/|$)"), "$1$2")
        base = base.replace(Regex("^(http://[^/:]+):80(/|$)"), "$1$2")
        return base.trimEnd('/')
    }

    /**
     * Fetches a spec from an explicit URL (e.g. a swagger URL found in bytecode).
     */
    suspend fun fetchSpecFromUrl(specUrl: String, rootUrl: String): Result<DiscoveryDocument> =
        withContext(Dispatchers.IO) {
            try {
                val body = httpGet(specUrl) ?: return@withContext Result.failure(
                    Exception("Failed to fetch $specUrl")
                )
                val json = JSONObject(body)
                val doc = detectAndParse(json, rootUrl)
                    ?: return@withContext Result.failure(Exception("Unrecognized spec format"))
                Result.success(doc)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Discover required OAuth2 scopes for a Google API endpoint.
     * Sends a request with an intentionally insufficient bearer token,
     * then parses the Www-Authenticate header from the 403 response.
     * Returns the list of accepted scopes, or empty if not discoverable.
     */
    suspend fun discoverScopes(
        rootUrl: String,
        path: String,
        httpMethod: String = "GET"
    ): Result<ScopeDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            val url = normalizeBaseUrl(rootUrl) + "/" + path.trimStart('/')
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = httpMethod
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                // Send a dummy bearer token to trigger scope disclosure
                setRequestProperty("Authorization", "Bearer invalid_token_for_scope_discovery")
            }

            try {
                val statusCode = conn.responseCode
                val body = try {
                    (if (statusCode in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }

                // Parse Www-Authenticate header for scopes
                val wwwAuth = conn.getHeaderField("Www-Authenticate") ?: conn.getHeaderField("www-authenticate") ?: ""
                val scopes = parseScopesFromWwwAuthenticate(wwwAuth)

                // Parse gRPC method name from error response
                val grpcMethod = parseGrpcMethodFromError(body)

                // Parse service name from error
                val serviceName = parseServiceFromError(body)

                Result.success(ScopeDiscoveryResult(
                    scopes = scopes,
                    grpcMethod = grpcMethod,
                    serviceName = serviceName,
                    statusCode = statusCode
                ))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Discover hidden request parameters using ProtoJson type confusion.
     * Sends [1,2,3,...N] with Content-Type: application/json+protobuf
     * and parses the error response that leaks field names and types.
     * Works on Google APIs that use gRPC transcoding (most googleapis.com endpoints).
     *
     * Matches req2proto's dual-probe strategy:
     * - Send strings ["x1","x2",...] to discover non-string fields
     * - Send integers [1,2,...] to discover non-integer fields
     * Fields absent from one probe but present in the other reveal their type.
     * Forces alt=json so Google returns JSON errors (not protobuf).
     */
    suspend fun discoverProtoJsonParams(
        rootUrl: String,
        path: String,
        maxFields: Int = 300
    ): Result<List<ProtoJsonField>> = withContext(Dispatchers.IO) {
        try {
            // Force alt=json so Google returns JSON errors, not protobuf
            val baseUrl = normalizeBaseUrl(rootUrl) + "/" + path.trimStart('/')
            val url = forceAltJson(baseUrl)

            // Phase 1: Send strings to discover non-string fields (TYPE_MESSAGE, TYPE_BYTES, etc.)
            val strPayload = JSONArray((1..maxFields).map { "x$it" }).toString()
            val strFields = probeWithPayload(url, strPayload)

            // Phase 2: Send integers to discover non-integer fields (TYPE_STRING, TYPE_BOOL, etc.)
            val intPayload = JSONArray((1..maxFields).toList()).toString()
            val intFields = probeWithPayload(url, intPayload)

            Result.success(mergeProbeResults(strFields, intFields))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Merge string-probe and int-probe results using req2proto dual-probe logic:
     * - Field rejected by strings but NOT integers → accepts integers → TYPE_INT64
     * - Field rejected by integers but NOT strings → accepts strings → TYPE_STRING
     * - Field rejected by both → use error's reported type (prefer more specific)
     */
    internal fun mergeProbeResults(
        strFields: List<ProtoJsonField>,
        intFields: List<ProtoJsonField>
    ): List<ProtoJsonField> {
        val allFieldsByIndex = mutableMapOf<Int, ProtoJsonField>()

        for (field in intFields) {
            allFieldsByIndex[field.index] = field
        }
        for (field in strFields) {
            if (field.index !in allFieldsByIndex) {
                // Not reported when sending integers → it accepts integers
                allFieldsByIndex[field.index] = field.copy(type = "TYPE_INT64")
            } else {
                // Both probes reported it — keep the one with more specific type info
                val existing = allFieldsByIndex[field.index]!!
                if (existing.messageType == null && field.messageType != null) {
                    allFieldsByIndex[field.index] = field
                }
            }
        }

        return allFieldsByIndex.values.sortedBy { it.index }
    }

    /** Force alt=json query param so Google returns JSON error responses, not protobuf. */
    private fun forceAltJson(url: String): String {
        val parsed = java.net.URI(url)
        val query = parsed.rawQuery
        return if (query.isNullOrBlank()) {
            "$url?alt=json"
        } else if (!query.contains("alt=")) {
            "$url&alt=json"
        } else {
            url.replace(Regex("alt=[^&]*"), "alt=json")
        }
    }

    private fun probeWithPayload(url: String, payload: String): List<ProtoJsonField> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json+protobuf")
            setRequestProperty("Accept", "application/json")
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }

            return parseProtoJsonErrors(body)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun executeMethod(
        rootUrl: String,
        servicePath: String,
        method: DiscoveryMethod,
        params: Map<String, String>,
        apiKey: String?,
        requestBody: String? = null,
        packageName: String? = null,
        certSha1: String? = null
    ): Result<ExecutionResult> = withContext(Dispatchers.IO) {
        try {
            var path = method.path
            val queryParams = mutableMapOf<String, String>()

            for ((name, value) in params) {
                if (value.isBlank()) continue
                val param = method.parameters[name]
                if (param?.location == "path") {
                    path = path.replace("{+$name}", value).replace("{$name}", value)
                } else {
                    queryParams[name] = value
                }
            }

            val isGoogleApi = rootUrl.contains("googleapis.com") || rootUrl.contains("clients6.google.com")

            // Always send key as query param — Maps/Places APIs require it there,
            // and Cloud APIs accept it either way (X-Goog-Api-Key header is also sent below)
            if (!apiKey.isNullOrBlank() && "key" !in queryParams) {
                queryParams["key"] = apiKey
            }

            val base = normalizeBaseUrl(rootUrl) + "/" + servicePath.trimStart('/')
            val fullPath = base.trimEnd('/') + "/" + path.trimStart('/')
            val queryString = if (queryParams.isNotEmpty()) {
                "?" + queryParams.entries.joinToString("&") { (k, v) ->
                    "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
                }
            } else ""

            val url = fullPath + queryString
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.httpMethod
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")

                // Google API auth: use X-Goog-Api-Key header instead of query param
                if (isGoogleApi && !apiKey.isNullOrBlank()) {
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                }
                // Also send Spatula if available
                if (isGoogleApi && !packageName.isNullOrBlank() && !certSha1.isNullOrBlank()) {
                    setRequestProperty("X-Goog-Spatula", buildSpatulaHeader(packageName, certSha1))
                }

                if (requestBody != null && method.httpMethod in listOf("POST", "PUT", "PATCH")) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            try {
                if (requestBody != null && conn.doOutput) {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                        it.write(requestBody)
                    }
                }

                val statusCode = conn.responseCode
                val body = try {
                    (if (statusCode in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }

                val headers = mutableMapOf<String, String>()
                var i = 0
                while (true) {
                    val key = conn.getHeaderFieldKey(i) ?: if (i == 0) { i++; continue } else break
                    val value = conn.getHeaderField(i) ?: break
                    headers[key] = value
                    i++
                }

                // Enrich: extract scopes from 403 responses
                val scopes = if (statusCode == 403) {
                    val wwwAuth = headers["Www-Authenticate"] ?: headers["www-authenticate"] ?: ""
                    parseScopesFromWwwAuthenticate(wwwAuth)
                } else emptyList()

                Result.success(ExecutionResult(statusCode, body, headers, scopes))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Format detection & parsing ---

    private fun detectAndParse(json: JSONObject, fallbackRootUrl: String): DiscoveryDocument? {
        val format = when {
            json.has("openapi") -> "openapi3"
            json.has("swagger") -> "swagger2"
            json.has("discoveryVersion") || json.has("rootUrl") -> "google-discovery"
            json.has("paths") -> "openapi3-no-version"
            else -> null
        }
        Log.d(TAG, "detectAndParse: format=$format keys=${json.keys().asSequence().take(10).toList()}")
        return when (format) {
            "openapi3", "openapi3-no-version" -> parseOpenApi3(json, fallbackRootUrl)
            "swagger2" -> parseSwagger2(json, fallbackRootUrl)
            "google-discovery" -> parseGoogleDiscovery(json)
            else -> null
        }
    }

    // --- Google Discovery parser ---

    private fun parseGoogleDiscovery(json: JSONObject): DiscoveryDocument {
        return DiscoveryDocument(
            name = json.optString("name", ""),
            version = json.optString("version", ""),
            title = json.optString("title", ""),
            description = json.optString("description", ""),
            rootUrl = json.optString("rootUrl", json.optString("baseUrl", "")),
            servicePath = json.optString("servicePath", json.optString("basePath", "")),
            resources = parseGoogleResources(json.optJSONObject("resources"))
        )
    }

    private fun parseGoogleResources(json: JSONObject?): Map<String, DiscoveryResource> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, DiscoveryResource>()
        for (key in json.keys()) {
            val resJson = json.getJSONObject(key)
            result[key] = DiscoveryResource(
                name = key,
                methods = parseGoogleMethods(resJson.optJSONObject("methods")),
                resources = parseGoogleResources(resJson.optJSONObject("resources"))
            )
        }
        return result
    }

    private fun parseGoogleMethods(json: JSONObject?): Map<String, DiscoveryMethod> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, DiscoveryMethod>()
        for (key in json.keys()) {
            val mJson = json.getJSONObject(key)
            result[key] = DiscoveryMethod(
                id = mJson.optString("id", key),
                httpMethod = mJson.optString("httpMethod", "GET"),
                path = mJson.optString("path", mJson.optString("flatPath", "")),
                description = mJson.optString("description", ""),
                parameters = parseGoogleParameters(mJson.optJSONObject("parameters")),
                parameterOrder = parseStringArray(mJson.optJSONArray("parameterOrder")),
                scopes = parseStringArray(mJson.optJSONArray("scopes"))
            )
        }
        return result
    }

    private fun parseGoogleParameters(json: JSONObject?): Map<String, DiscoveryParameter> {
        if (json == null) return emptyMap()
        val result = mutableMapOf<String, DiscoveryParameter>()
        for (key in json.keys()) {
            val pJson = json.getJSONObject(key)
            result[key] = DiscoveryParameter(
                name = key,
                type = pJson.optString("type", "string"),
                location = pJson.optString("location", "query"),
                required = pJson.optBoolean("required", false),
                description = pJson.optString("description", ""),
                default = if (pJson.has("default")) pJson.getString("default") else null,
                enumValues = parseStringArray(pJson.optJSONArray("enum"))
            )
        }
        return result
    }

    // --- OpenAPI 3.x parser ---

    private fun parseOpenApi3(json: JSONObject, fallbackRootUrl: String): DiscoveryDocument {
        val info = json.optJSONObject("info")
        // Extract server URL
        val servers = json.optJSONArray("servers")
        val serverUrl = if (servers != null && servers.length() > 0) {
            servers.getJSONObject(0).optString("url", "")
        } else ""

        val rootUrl: String
        val servicePath: String
        if (serverUrl.startsWith("http")) {
            val parsed = URL(serverUrl)
            rootUrl = "${parsed.protocol}://${parsed.authority}"
            servicePath = parsed.path.ifEmpty { "/" }
        } else {
            rootUrl = fallbackRootUrl
            servicePath = serverUrl.ifEmpty { "/" }
        }

        return DiscoveryDocument(
            name = info?.optString("title", "")?.lowercase()?.replace(' ', '-') ?: "",
            version = info?.optString("version", "") ?: "",
            title = info?.optString("title", "") ?: "",
            description = info?.optString("description", "") ?: "",
            rootUrl = rootUrl,
            servicePath = servicePath,
            resources = parseOpenApiPaths(json.optJSONObject("paths"))
        )
    }

    private fun parseOpenApiPaths(paths: JSONObject?): Map<String, DiscoveryResource> {
        if (paths == null) return emptyMap()
        // Group paths by first segment: /pets/{id} → "pets"
        val grouped = mutableMapOf<String, MutableMap<String, DiscoveryMethod>>()

        for (pathStr in paths.keys()) {
            val pathObj = paths.getJSONObject(pathStr)
            val tag = pathStr.trimStart('/').substringBefore('/').ifEmpty { "default" }

            val methods = grouped.getOrPut(tag) { mutableMapOf() }
            for (httpMethod in listOf("get", "post", "put", "delete", "patch", "head", "options")) {
                val opObj = pathObj.optJSONObject(httpMethod) ?: continue
                val operationId = opObj.optString("operationId", "$httpMethod $pathStr")
                methods[operationId] = DiscoveryMethod(
                    id = operationId,
                    httpMethod = httpMethod.uppercase(),
                    path = pathStr,
                    description = opObj.optString("summary",
                        opObj.optString("description", "")),
                    parameters = parseOpenApiParameters(
                        opObj.optJSONArray("parameters"),
                        pathObj.optJSONArray("parameters")  // path-level params
                    ),
                    parameterOrder = emptyList(),
                    scopes = parseOpenApiScopes(opObj)
                )
            }
        }

        return grouped.map { (tag, methods) ->
            tag to DiscoveryResource(
                name = tag,
                methods = methods,
                resources = emptyMap()
            )
        }.toMap()
    }

    private fun parseOpenApiParameters(
        opParams: org.json.JSONArray?,
        pathParams: org.json.JSONArray?
    ): Map<String, DiscoveryParameter> {
        val result = mutableMapOf<String, DiscoveryParameter>()
        // Path-level params first (can be overridden by operation-level)
        for (arr in listOf(pathParams, opParams)) {
            if (arr == null) continue
            for (i in 0 until arr.length()) {
                val pJson = arr.getJSONObject(i)
                val name = pJson.optString("name", "param$i")
                val schema = pJson.optJSONObject("schema")
                result[name] = DiscoveryParameter(
                    name = name,
                    type = schema?.optString("type", "string")
                        ?: pJson.optString("type", "string"),
                    location = mapOpenApiLocation(pJson.optString("in", "query")),
                    required = pJson.optBoolean("required", false),
                    description = pJson.optString("description", ""),
                    default = schema?.let {
                        if (it.has("default")) it.get("default").toString() else null
                    } ?: if (pJson.has("default")) pJson.get("default").toString() else null,
                    enumValues = schema?.optJSONArray("enum")?.let { parseStringArray(it) }
                        ?: parseStringArray(pJson.optJSONArray("enum"))
                )
            }
        }
        return result
    }

    private fun mapOpenApiLocation(loc: String): String = when (loc) {
        "path" -> "path"
        "header" -> "header"
        "cookie" -> "cookie"
        else -> "query"
    }

    private fun parseOpenApiScopes(opObj: JSONObject): List<String> {
        val security = opObj.optJSONArray("security") ?: return emptyList()
        val scopes = mutableListOf<String>()
        for (i in 0 until security.length()) {
            val secObj = security.getJSONObject(i)
            for (key in secObj.keys()) {
                val arr = secObj.optJSONArray(key) ?: continue
                for (j in 0 until arr.length()) {
                    scopes.add(arr.getString(j))
                }
            }
        }
        return scopes
    }

    // --- Swagger 2.0 parser ---

    private fun parseSwagger2(json: JSONObject, fallbackRootUrl: String): DiscoveryDocument {
        val info = json.optJSONObject("info")
        val host = json.optString("host", "")
        val basePath = json.optString("basePath", "/")
        val schemes = json.optJSONArray("schemes")
        val scheme = if (schemes != null && schemes.length() > 0) schemes.getString(0) else "https"

        val rootUrl = if (host.isNotEmpty()) "$scheme://$host" else fallbackRootUrl

        return DiscoveryDocument(
            name = info?.optString("title", "")?.lowercase()?.replace(' ', '-') ?: "",
            version = info?.optString("version", "") ?: "",
            title = info?.optString("title", "") ?: "",
            description = info?.optString("description", "") ?: "",
            rootUrl = rootUrl,
            servicePath = basePath,
            resources = parseOpenApiPaths(json.optJSONObject("paths"))
            // Swagger 2.0 paths have the same structure for our purposes
        )
    }

    // --- Utilities ---

    private fun httpGet(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            for ((key, value) in extraHeaders) {
                setRequestProperty(key, value)
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText().take(200) }
                } catch (_: Exception) { null }
                Log.d(TAG, "httpGet: $url → HTTP $code${if (errorBody != null) " body=$errorBody" else ""}")
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStringArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // --- Virtual discovery document synthesis ---

    /**
     * Synthesize a DiscoveryDocument from DEX-extracted ApiEndpoints.
     * Groups endpoints by first path segment into resources, uses Retrofit-extracted
     * annotations to populate parameters.
     */
    fun synthesizeFromEndpoints(baseUrl: String, endpoints: List<ApiEndpoint>): DiscoveryDocument? {
        if (endpoints.isEmpty()) return null

        // Filter out useless literal endpoints with no real path or params
        val useful = endpoints.filter { ep ->
            val hasPath = ep.path.trimStart('/').isNotEmpty()
            val hasParams = ep.queryParams.isNotEmpty() || ep.pathParams.isNotEmpty()
            val hasMethod = ep.httpMethod != null
            hasPath || hasParams || hasMethod
        }
        if (useful.isEmpty()) return null

        val host = try {
            java.net.URI(baseUrl).host ?: baseUrl
        } catch (_: Exception) { baseUrl }

        // Compute servicePath as longest common path prefix by segments
        val allPaths = useful.map { it.path.trimStart('/') }
        val servicePath = computeCommonPrefix(allPaths)

        // Group by first segment after servicePath
        val grouped = mutableMapOf<String, MutableMap<String, DiscoveryMethod>>()
        for (ep in useful) {
            val relPath = ep.path.trimStart('/').removePrefix(servicePath.trimStart('/').trimEnd('/')).trimStart('/')
            val tag = relPath.substringBefore('/').ifEmpty { "root" }
            val methods = grouped.getOrPut(tag) { mutableMapOf() }

            val httpMethod = ep.httpMethod ?: "GET"
            val methodId = "$httpMethod ${ep.path}"
            if (methodId in methods) continue

            // Build parameters from Retrofit annotations + path placeholders
            val params = mutableMapOf<String, DiscoveryParameter>()
            val pathParamPattern = Regex("\\{\\+?([^}]+)\\}")
            for (match in pathParamPattern.findAll(ep.path)) {
                val name = match.groupValues[1]
                params[name] = DiscoveryParameter(
                    name = name, type = "string", location = "path",
                    required = true, description = "", default = null, enumValues = emptyList()
                )
            }
            for (name in ep.pathParams) {
                if (name !in params) {
                    params[name] = DiscoveryParameter(
                        name = name, type = "string", location = "path",
                        required = true, description = "", default = null, enumValues = emptyList()
                    )
                }
            }
            for (name in ep.queryParams) {
                val examples = ep.queryParamExamples[name] ?: emptyList()
                val defaultVal = examples.firstOrNull()
                params[name] = DiscoveryParameter(
                    name = name, type = "string", location = "query",
                    required = false, description = "", default = defaultVal,
                    enumValues = examples
                )
            }
            for (name in ep.headerParams) {
                val examples = ep.headerParamExamples[name] ?: emptyList()
                val defaultVal = examples.firstOrNull()
                params[name] = DiscoveryParameter(
                    name = name, type = "string", location = "header",
                    required = false, description = "", default = defaultVal,
                    enumValues = examples
                )
            }

            val sourceClass = ep.sourceClass
                .removePrefix("L").removeSuffix(";").replace('/', '.')
            methods[methodId] = DiscoveryMethod(
                id = methodId,
                httpMethod = httpMethod,
                path = ep.path,
                description = sourceClass,
                parameters = params,
                parameterOrder = params.filter { it.value.location == "path" }.keys.toList(),
                scopes = emptyList(),
                source = "dex",
                hasBody = ep.hasBody
            )
        }

        if (grouped.isEmpty()) return null

        return DiscoveryDocument(
            name = host,
            version = "dex",
            title = "Endpoints from bytecode analysis",
            description = "${useful.size} endpoints extracted from DEX bytecode",
            rootUrl = baseUrl,
            servicePath = servicePath,
            resources = grouped.map { (tag, methods) ->
                tag to DiscoveryResource(name = tag, methods = methods, resources = emptyMap())
            }.toMap()
        )
    }

    /**
     * Merge a virtual (DEX-synthesized) document into a remote spec document.
     * Virtual methods not already present in remote get added with source="dex".
     */
    fun mergeDocuments(remote: DiscoveryDocument, virtual: DiscoveryDocument): DiscoveryDocument {
        // Collect all remote method signatures for dedup
        val remoteSignatures = mutableSetOf<String>()
        fun collectSignatures(resources: Map<String, DiscoveryResource>) {
            for ((_, res) in resources) {
                for ((_, method) in res.methods) {
                    remoteSignatures.add("${method.httpMethod} ${method.path}")
                }
                collectSignatures(res.resources)
            }
        }
        collectSignatures(remote.resources)

        // Mark remote methods with source="spec"
        fun tagSpecSource(resources: Map<String, DiscoveryResource>): Map<String, DiscoveryResource> {
            return resources.mapValues { (_, res) ->
                res.copy(
                    methods = res.methods.mapValues { (_, m) ->
                        if (m.source.isEmpty()) m.copy(source = "spec") else m
                    },
                    resources = tagSpecSource(res.resources)
                )
            }
        }

        val taggedResources = tagSpecSource(remote.resources).toMutableMap()

        // Add virtual methods that don't exist in remote
        for ((resName, virtualRes) in virtual.resources) {
            val newMethods = virtualRes.methods.filter { (_, m) ->
                "${m.httpMethod} ${m.path}" !in remoteSignatures
            }
            if (newMethods.isEmpty()) continue

            val existing = taggedResources[resName]
            if (existing != null) {
                taggedResources[resName] = existing.copy(
                    methods = existing.methods + newMethods
                )
            } else {
                taggedResources[resName] = virtualRes
            }
        }

        return remote.copy(resources = taggedResources)
    }

    private fun computeCommonPrefix(paths: List<String>): String {
        if (paths.isEmpty()) return "/"
        val segmented = paths.map { it.split('/').filter { s -> s.isNotEmpty() } }
        val first = segmented.first()
        val common = mutableListOf<String>()
        for (i in first.indices) {
            val seg = first[i]
            if (segmented.all { it.size > i && it[i] == seg }) {
                common.add(seg)
            } else break
        }
        return if (common.isEmpty()) "/" else "/" + common.joinToString("/") + "/"
    }

    // --- Google-specific auth helpers ---

    /**
     * Build an X-Goog-Spatula header from package name and cert SHA1.
     * Matches spatula.proto from req2proto:
     *   message SpatulaInner { AppInfo app_info = 1; int64 droidguard_response = 3; }
     *   message AppInfo { string package_name = 1; string signature = 3; }
     *
     * The DroidGuard value (field 3 of outer) is NOT validated by Google's servers
     * but we include it for authenticity (same constant used by req2proto's aas-rs).
     */
    fun buildSpatulaHeader(packageName: String, certSha1Hex: String): String {
        // Convert hex SHA1 to bytes then base64
        val sha1Bytes = hexToBytes(certSha1Hex)
        val sha1Base64 = java.util.Base64.getEncoder().encodeToString(sha1Bytes)

        // AppInfo: field 1 (LEN) = package name, field 3 (LEN) = base64(sha1)
        val appInfo = buildProtobufLenField(1, packageName.toByteArray()) +
            buildProtobufLenField(3, sha1Base64.toByteArray())

        // SpatulaInner: field 1 (LEN) = AppInfo, field 3 (VARINT) = droidguard_response
        // DroidGuard constant from req2proto aas-rs: 3959931537119515576
        val outer = buildProtobufLenField(1, appInfo) +
            buildProtobufVarintField(3, 3959931537119515576L)

        return java.util.Base64.getEncoder().encodeToString(outer)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase().replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte()
        }
    }

    private fun buildProtobufLenField(fieldNumber: Int, data: ByteArray): ByteArray {
        // Wire type 2 (length-delimited): (fieldNumber << 3) | 2
        val tag = (fieldNumber shl 3) or 2
        val tagBytes = encodeVarint(tag)
        val lenBytes = encodeVarint(data.size)
        return tagBytes + lenBytes + data
    }

    private fun buildProtobufVarintField(fieldNumber: Int, value: Long): ByteArray {
        // Wire type 0 (varint): (fieldNumber << 3) | 0
        val tag = (fieldNumber shl 3) or 0
        val tagBytes = encodeVarint(tag)
        val valBytes = encodeVarintLong(value)
        return tagBytes + valBytes
    }

    private fun encodeVarint(value: Int): ByteArray {
        var v = value
        val bytes = mutableListOf<Byte>()
        while (v > 0x7f) {
            bytes.add(((v and 0x7f) or 0x80).toByte())
            v = v ushr 7
        }
        bytes.add((v and 0x7f).toByte())
        return bytes.toByteArray()
    }

    private fun encodeVarintLong(value: Long): ByteArray {
        var v = value
        val bytes = mutableListOf<Byte>()
        while (v > 0x7fL) {
            bytes.add(((v and 0x7fL) or 0x80L).toByte())
            v = v ushr 7
        }
        bytes.add((v and 0x7fL).toByte())
        return bytes.toByteArray()
    }

    /**
     * Parse scopes from a Www-Authenticate header.
     * Format: Bearer realm="...", error="insufficient_scope", scope="scope1 scope2 ..."
     */
    private fun parseScopesFromWwwAuthenticate(header: String): List<String> {
        if (header.isBlank()) return emptyList()
        val scopeMatch = Regex("""scope="([^"]+)"""").find(header) ?: return emptyList()
        return scopeMatch.groupValues[1].split(" ").filter { it.isNotBlank() }
    }

    /** Parse gRPC method name from Google API error response body. */
    private fun parseGrpcMethodFromError(body: String): String? {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error") ?: return null
            val details = error.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val detail = details.getJSONObject(i)
                val metadata = detail.optJSONObject("metadata") ?: continue
                val method = metadata.optString("method", "")
                if (method.isNotBlank()) return method
            }
            null
        } catch (_: Exception) { null }
    }

    /** Parse service name from Google API error response body. */
    private fun parseServiceFromError(body: String): String? {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error") ?: return null
            val details = error.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val detail = details.getJSONObject(i)
                val metadata = detail.optJSONObject("metadata") ?: continue
                val service = metadata.optString("service", "")
                if (service.isNotBlank()) return service
            }
            null
        } catch (_: Exception) { null }
    }

    /**
     * Parse ProtoJson error responses to extract field names, types, and indices.
     * Checks two sources (matching req2proto's approach):
     * 1. error.details[].fieldViolations[] — structured array of {field, description}
     * 2. error.message — fallback for APIs that put violations inline
     *
     * req2proto regex: Invalid value at '(.+)' \((.*)\), (?:Base64 decoding failed for )?"?x?([^"]*)"?
     * The third capture is the VALUE sent (e.g. "x5" or "5"), which equals the proto field index.
     */
    internal fun parseProtoJsonErrors(body: String): List<ProtoJsonField> {
        val fields = mutableListOf<ProtoJsonField>()
        val seen = mutableSetOf<Int>()

        // Matches req2proto's fieldDescRe
        val fieldDescPattern = Regex(
            """Invalid value at '([^']+)' \(([^)]+)\), (?:Base64 decoding failed for )?"?x?([^"]*)"?"""
        )

        fun addFromDescription(fieldPath: String, description: String) {
            val match = fieldDescPattern.find(description) ?: return
            val fieldName = match.groupValues[1].substringAfterLast('.')
            val fieldType = match.groupValues[2]
            val indexStr = match.groupValues[3]
            val fieldIndex = indexStr.toIntOrNull() ?: return
            if (fieldIndex in seen) return
            seen.add(fieldIndex)

            val isMessage = fieldType.startsWith("type.googleapis.com/")
            val messageType = if (isMessage) fieldType.removePrefix("type.googleapis.com/") else null

            fields.add(ProtoJsonField(
                name = fieldName,
                type = if (isMessage) "TYPE_MESSAGE" else fieldType,
                index = fieldIndex,
                messageType = messageType
            ))
        }

        try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error") ?: return fields

            // Source 1: Structured fieldViolations (preferred — what req2proto uses)
            val details = error.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val detail = details.optJSONObject(i) ?: continue
                    val violations = detail.optJSONArray("fieldViolations") ?: continue
                    for (j in 0 until violations.length()) {
                        val violation = violations.getJSONObject(j)
                        val field = violation.optString("field", "")
                        val desc = violation.optString("description", "")
                        if (desc.contains("Invalid value at")) {
                            addFromDescription(field, desc)
                        }
                    }
                }
            }

            // Source 2: Fallback to error.message (some APIs put violations inline)
            if (fields.isEmpty()) {
                val message = error.optString("message", "")
                for (match in fieldDescPattern.findAll(message)) {
                    addFromDescription("", match.value)
                }
            }
        } catch (_: Exception) { }
        return fields
    }
}

/** Result of scope discovery from a 403 response. */
data class ScopeDiscoveryResult(
    val scopes: List<String>,
    val grpcMethod: String?,
    val serviceName: String?,
    val statusCode: Int
)

/** A field discovered via ProtoJson type confusion. */
data class ProtoJsonField(
    val name: String,
    val type: String,
    val index: Int,
    val messageType: String? = null
)
