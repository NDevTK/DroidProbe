package com.droidprobe.app.interaction

import com.droidprobe.app.data.model.DiscoveryDocument
import com.droidprobe.app.data.model.DiscoveryMethod
import com.droidprobe.app.data.model.DiscoveryParameter
import com.droidprobe.app.data.model.DiscoveryResource
import com.droidprobe.app.data.model.ExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * All formats are normalized into the common DiscoveryDocument model.
 */
class ApiSpecFetcher {

    companion object {
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
    }

    /**
     * Probes a base URL for any known API spec format.
     * Returns the parsed DiscoveryDocument or an error.
     */
    suspend fun fetchSpec(rootUrl: String, apiKey: String? = null): Result<DiscoveryDocument> =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(rootUrl)

            var lastError: Exception? = null
            for (path in SPEC_PATHS) {
                var url = base + path
                if (!apiKey.isNullOrBlank()) {
                    url += (if ('?' in url) "&" else "?") + "key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
                }
                try {
                    val body = httpGet(url) ?: continue
                    val json = JSONObject(body)
                    val doc = detectAndParse(json, base)
                    if (doc != null) return@withContext Result.success(doc)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            Result.failure(lastError ?: Exception("No API specification found"))
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

    suspend fun executeMethod(
        rootUrl: String,
        servicePath: String,
        method: DiscoveryMethod,
        params: Map<String, String>,
        apiKey: String?,
        requestBody: String? = null
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

            if (!apiKey.isNullOrBlank()) {
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

                Result.success(ExecutionResult(statusCode, body, headers))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Format detection & parsing ---

    private fun detectAndParse(json: JSONObject, fallbackRootUrl: String): DiscoveryDocument? {
        return when {
            // OpenAPI 3.x
            json.has("openapi") -> parseOpenApi3(json, fallbackRootUrl)
            // Swagger 2.0
            json.has("swagger") -> parseSwagger2(json, fallbackRootUrl)
            // Google Discovery
            json.has("discoveryVersion") || json.has("rootUrl") -> parseGoogleDiscovery(json)
            // Unknown but has paths (might be OpenAPI without version field)
            json.has("paths") -> parseOpenApi3(json, fallbackRootUrl)
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

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStringArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
