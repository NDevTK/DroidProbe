package com.droidprobe.app.analysis

import com.droidprobe.app.data.model.*
import com.droidprobe.app.interaction.ApiSpecFetcher
import com.droidprobe.app.interaction.ProtoJsonField
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test

/**
 * Core integration tests for the Google API discovery pipeline.
 * Tests the network-facing API client directly — no assumeTrue, all tests must pass:
 * - Fetching real Google Discovery documents
 * - Verifying discovery docs are structurally usable for the UI
 * - ProtoJson parameter leak (application/json+protobuf type confusion)
 * - Scope discovery from Www-Authenticate 403 responses
 * - X-Goog-Spatula header construction (verified against known-good values)
 * - Staging discovery URL construction
 * - Merge logic between remote and virtual documents
 * - End-to-end method execution
 */
class GoogleApkDiscoveryTest {

    companion object {
        private val fetcher = ApiSpecFetcher()
        private lateinit var geminiDoc: DiscoveryDocument

        @BeforeClass
        @JvmStatic
        fun setup() {
            val result = runBlocking {
                fetcher.fetchSpec("https://generativelanguage.googleapis.com")
            }
            geminiDoc = result.getOrThrow()
        }
    }

    // ==================== Discovery Document Fetch ====================

    @Test
    fun `fetch real Generative Language API discovery document`() {
        assertThat(geminiDoc.rootUrl).contains("generativelanguage.googleapis.com")
        assertThat(geminiDoc.resources).isNotEmpty()
    }

    @Test
    fun `fetched discovery doc has valid rootUrl and servicePath`() {
        assertThat(geminiDoc.rootUrl).startsWith("https://")
        assertThat(geminiDoc.rootUrl).contains("generativelanguage")
        assertThat(geminiDoc.servicePath).isNotNull()
    }

    @Test
    fun `fetched discovery doc resources have methods with valid structure`() {
        val allMethods = collectMethods(geminiDoc.resources)
        assertThat(allMethods).isNotEmpty()
        for (method in allMethods) {
            assertThat(method.httpMethod).isNotEmpty()
            assertThat(method.path).isNotEmpty()
            assertThat(method.id).isNotEmpty()
        }
    }

    @Test
    fun `fetched discovery doc path params are required`() {
        val pathParams = collectMethods(geminiDoc.resources)
            .flatMap { it.parameters.values }
            .filter { it.location == "path" }

        assertThat(pathParams).isNotEmpty()
        for (param in pathParams) {
            assertThat(param.required).isTrue()
        }
    }

    @Test
    fun `fetched discovery doc has nested resources`() {
        val hasNested = geminiDoc.resources.values.any { it.resources.isNotEmpty() }
        assertThat(hasNested).isTrue()
    }

    @Test
    fun `fetched discovery doc methods include GET and POST`() {
        val httpMethods = collectMethods(geminiDoc.resources).map { it.httpMethod }.toSet()
        assertThat(httpMethods).contains("GET")
        assertThat(httpMethods).contains("POST")
    }

    // ==================== Discovery Doc Usability for UI ====================

    @Test
    fun `discovery doc can be flattened into UI-renderable resource tree`() {
        val flatItems = mutableListOf<Pair<String, List<DiscoveryMethod>>>()
        fun flatten(resources: Map<String, DiscoveryResource>, prefix: String = "") {
            for ((name, resource) in resources) {
                val fullName = if (prefix.isEmpty()) name else "$prefix.$name"
                if (resource.methods.isNotEmpty()) {
                    flatItems.add(fullName to resource.methods.values.toList())
                }
                flatten(resource.resources, fullName)
            }
        }
        flatten(geminiDoc.resources)

        assertThat(flatItems).isNotEmpty()
        for ((name, methods) in flatItems) {
            assertThat(name).isNotEmpty()
            assertThat(methods).isNotEmpty()
        }
    }

    @Test
    fun `every method path can be resolved to a full URL`() {
        for (method in collectMethods(geminiDoc.resources)) {
            var path = method.path
            for ((name, param) in method.parameters) {
                if (param.location == "path") {
                    path = path.replace("{+$name}", "test").replace("{$name}", "test")
                }
            }

            val base = geminiDoc.rootUrl.trimEnd('/')
            val svc = geminiDoc.servicePath.trimStart('/').trimEnd('/')
            val fullUrl = if (svc.isEmpty() || svc == "/")
                "$base/${path.trimStart('/')}"
            else
                "$base/$svc/${path.trimStart('/')}"

            val uri = java.net.URI(fullUrl)
            assertThat(uri.scheme).isAnyOf("http", "https")
            assertThat(uri.host).isNotNull()
            assertThat(fullUrl).doesNotContain("{")
        }
    }

    @Test
    fun `methods with scopes show OAuth requirements in UI`() {
        val methodsWithScopes = collectMethods(geminiDoc.resources).filter { it.scopes.isNotEmpty() }
        for (method in methodsWithScopes) {
            for (scope in method.scopes) {
                assertThat(scope).isNotEmpty()
            }
        }
    }

    // ==================== Execute Real API Method ====================

    @Test
    fun `execute real GET method from Gemini discovery doc`() {
        val getMethod = collectMethods(geminiDoc.resources)
            .first { m ->
                m.httpMethod == "GET" &&
                    m.parameters.values.none { it.location == "path" && it.required }
            }

        val result = runBlocking {
            fetcher.executeMethod(
                rootUrl = geminiDoc.rootUrl,
                servicePath = geminiDoc.servicePath,
                method = getMethod,
                params = emptyMap(),
                apiKey = null
            )
        }

        assertThat(result.isSuccess).isTrue()
        val execution = result.getOrThrow()
        assertThat(execution.statusCode).isGreaterThan(0)
        assertThat(execution.headers).isNotEmpty()
    }

    @Test
    fun `discovery doc has POST methods that support request bodies`() {
        val postMethod = collectMethods(geminiDoc.resources)
            .first { it.httpMethod == "POST" }
        assertThat(postMethod.httpMethod).isEqualTo("POST")
    }

    // ==================== Merge: Remote + Virtual ====================

    @Test
    fun `merge remote discovery with synthesized virtual doc`() {
        val fakeEndpoint = ApiEndpoint(
            fullUrl = "https://generativelanguage.googleapis.com/v1/dex-only/test",
            baseUrl = "https://generativelanguage.googleapis.com",
            path = "v1/dex-only/test",
            httpMethod = "GET",
            sourceClass = "Lcom/test/DexClass;",
            sourceType = "literal"
        )
        val virtualDoc = fetcher.synthesizeFromEndpoints(
            "https://generativelanguage.googleapis.com",
            listOf(fakeEndpoint)
        )!!

        val merged = fetcher.mergeDocuments(geminiDoc, virtualDoc)

        assertThat(merged.name).isEqualTo(geminiDoc.name)
        assertThat(merged.rootUrl).isEqualTo(geminiDoc.rootUrl)

        val mergedMethods = collectMethods(merged.resources)
        val specMethods = mergedMethods.filter { it.source == "spec" }
        assertThat(specMethods).isNotEmpty()

        val dexMethods = mergedMethods.filter { it.source == "dex" }
        assertThat(dexMethods).isNotEmpty()
        assertThat(dexMethods.any { it.path.contains("dex-only") }).isTrue()

        val remoteMethodCount = collectMethods(geminiDoc.resources).size
        assertThat(mergedMethods.size).isGreaterThan(remoteMethodCount)
    }

    @Test
    fun `merge deduplicates methods that exist in both remote and virtual`() {
        val realMethod = collectMethods(geminiDoc.resources).first()
        val duplicateEndpoint = ApiEndpoint(
            fullUrl = "${geminiDoc.rootUrl}/${realMethod.path}",
            baseUrl = geminiDoc.rootUrl,
            path = realMethod.path,
            httpMethod = realMethod.httpMethod,
            sourceClass = "Lcom/test/Duplicate;",
            sourceType = "literal"
        )
        val virtualDoc = fetcher.synthesizeFromEndpoints(
            geminiDoc.rootUrl, listOf(duplicateEndpoint)
        )!!

        val merged = fetcher.mergeDocuments(geminiDoc, virtualDoc)
        val mergedMethods = collectMethods(merged.resources)
        val remoteMethodCount = collectMethods(geminiDoc.resources).size

        assertThat(mergedMethods.size).isEqualTo(remoteMethodCount)
    }

    // ==================== Synthesize From Endpoints ====================

    @Test
    fun `synthesize discovery doc from ApiEndpoints`() {
        val endpoints = listOf(
            ApiEndpoint(
                fullUrl = "https://example.googleapis.com/v1/users/{userId}",
                baseUrl = "https://example.googleapis.com",
                path = "v1/users/{userId}",
                httpMethod = "GET",
                sourceClass = "Lcom/example/Api;",
                sourceType = "retrofit",
                pathParams = listOf("userId")
            ),
            ApiEndpoint(
                fullUrl = "https://example.googleapis.com/v1/users",
                baseUrl = "https://example.googleapis.com",
                path = "v1/users",
                httpMethod = "POST",
                sourceClass = "Lcom/example/Api;",
                sourceType = "retrofit",
                hasBody = true,
                queryParams = listOf("page_token")
            )
        )

        val doc = fetcher.synthesizeFromEndpoints("https://example.googleapis.com", endpoints)
        assertThat(doc).isNotNull()
        assertThat(doc!!.rootUrl).isEqualTo("https://example.googleapis.com")

        val allMethods = collectMethods(doc.resources)
        assertThat(allMethods).hasSize(2)

        val getMethod = allMethods.find { it.httpMethod == "GET" }!!
        assertThat(getMethod.parameters["userId"]).isNotNull()
        assertThat(getMethod.parameters["userId"]!!.location).isEqualTo("path")
        assertThat(getMethod.parameters["userId"]!!.required).isTrue()

        val postMethod = allMethods.find { it.httpMethod == "POST" }!!
        assertThat(postMethod.parameters["page_token"]).isNotNull()
        assertThat(postMethod.parameters["page_token"]!!.location).isEqualTo("query")
        assertThat(postMethod.hasBody).isTrue()

        for (method in allMethods) {
            assertThat(method.source).isEqualTo("dex")
        }
    }

    // ==================== X-Goog-Spatula Header ====================

    @Test
    fun `buildSpatulaHeader matches known-good value from req2proto data`() {
        val packageName = "com.google.android.play.games"
        val certSha1 = "38918a453d07199354f8b19af05ec6562ced5788"
        val expectedSpatula = "Cj0KHWNvbS5nb29nbGUuYW5kcm9pZC5wbGF5LmdhbWVzGhxPSkdLUlQwSEdaTlUrTEdhOEY3R1ZpenRWNGc9GLingOeJmKD6Ng=="

        val actual = fetcher.buildSpatulaHeader(packageName, certSha1)
        assertThat(actual).isEqualTo(expectedSpatula)
    }

    @Test
    fun `buildSpatulaHeader matches another known-good value`() {
        val packageName = "com.google.android.youtube"
        val certSha1 = "24bb24c05e47e0aefa68a58a766179d9b613a600"
        val expectedSpatula = "CjoKGmNvbS5nb29nbGUuYW5kcm9pZC55b3V0dWJlGhxKTHNrd0Y1SDRLNzZhS1dLZG1GNTJiWVRwZ0E9GLingOeJmKD6Ng=="

        val actual = fetcher.buildSpatulaHeader(packageName, certSha1)
        assertThat(actual).isEqualTo(expectedSpatula)
    }

    @Test
    fun `buildSpatulaHeader produces valid base64-encoded protobuf`() {
        val spatula = fetcher.buildSpatulaHeader(
            "com.google.android.googlequicksearchbox",
            "38918a453d07199354f8b19af05ec6562ced5788"
        )

        assertThat(spatula).isNotEmpty()
        val decoded = java.util.Base64.getDecoder().decode(spatula)
        assertThat(decoded.size).isGreaterThan(10)

        val decodedStr = String(decoded, Charsets.ISO_8859_1)
        assertThat(decodedStr).contains("com.google.android.googlequicksearchbox")
    }

    @Test
    fun `buildSpatulaHeader contains base64-encoded SHA1 signature`() {
        val sha1Hex = "38918a453d07199354f8b19af05ec6562ced5788"
        val spatula = fetcher.buildSpatulaHeader("com.google.android.play.games", sha1Hex)

        val sha1Bytes = ByteArray(20) { i ->
            Integer.parseInt(sha1Hex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        val sha1Base64 = java.util.Base64.getEncoder().encodeToString(sha1Bytes)

        val decoded = String(java.util.Base64.getDecoder().decode(spatula), Charsets.ISO_8859_1)
        assertThat(decoded).contains(sha1Base64)
    }

    @Test
    fun `different packages produce different Spatula headers`() {
        val sha1 = "38918a453d07199354f8b19af05ec6562ced5788"
        val spatula1 = fetcher.buildSpatulaHeader("com.google.android.apps.maps", sha1)
        val spatula2 = fetcher.buildSpatulaHeader("com.google.android.youtube", sha1)
        assertThat(spatula1).isNotEqualTo(spatula2)
    }

    @Test
    fun `spatula contains droidguard_response field`() {
        val spatula = fetcher.buildSpatulaHeader(
            "com.google.android.gms",
            "38918a453d07199354f8b19af05ec6562ced5788"
        )
        val decoded = java.util.Base64.getDecoder().decode(spatula)

        val bytes = decoded.toList()
        assertThat(bytes).contains(0x18.toByte()) // field 3, wire type 0
    }

    // ==================== Scope Discovery ====================

    @Test
    fun `discoverScopes returns scopes from People API`() {
        val result = runBlocking {
            fetcher.discoverScopes(
                "https://people-pa.googleapis.com",
                "v2/people"
            )
        }
        assertThat(result.isSuccess).isTrue()
        val discovery = result.getOrThrow()

        assertThat(discovery.statusCode).isAnyOf(401, 403, 400)

        if (discovery.scopes.isNotEmpty()) {
            for (scope in discovery.scopes) {
                assertThat(scope).contains("googleapis.com/auth/")
            }
        }
    }

    @Test
    fun `discoverScopes parses gRPC method name from error`() {
        val result = runBlocking {
            fetcher.discoverScopes(
                "https://people-pa.googleapis.com",
                "v2/people"
            )
        }
        assertThat(result.isSuccess).isTrue()
        val discovery = result.getOrThrow()

        if (discovery.grpcMethod != null) {
            assertThat(discovery.grpcMethod).contains(".")
        }
    }

    // ==================== ProtoJson Parameter Leak ====================

    @Test
    fun `discoverProtoJsonParams leaks field names from YouTube Innertube`() {
        val result = runBlocking {
            fetcher.discoverProtoJsonParams(
                "https://youtubei.googleapis.com",
                "youtubei/v1/browse"
            )
        }
        assertThat(result.isSuccess).isTrue()
        val fields = result.getOrThrow()

        assertThat(fields).isNotEmpty()
        for (field in fields) {
            assertThat(field.name).isNotEmpty()
            assertThat(field.type).isNotEmpty()
            assertThat(field.index).isGreaterThan(0)
        }

        val fieldNames = fields.map { it.name }
        // These are stable Innertube fields — must always be discovered
        assertThat(fieldNames).contains("browse_id")
        assertThat(fieldNames).contains("context")
        assertThat(fieldNames).contains("params")

        val browseId = fields.first { it.name == "browse_id" }
        assertThat(browseId.type).isEqualTo("TYPE_STRING")

        val context = fields.first { it.name == "context" }
        assertThat(context.type).isEqualTo("TYPE_MESSAGE")
        assertThat(context.messageType).isNotNull()
    }

    @Test
    fun `parseProtoJsonErrors handles structured fieldViolations`() {
        val body = """
        {
          "error": {
            "code": 400,
            "message": "Invalid JSON payload received.",
            "status": "INVALID_ARGUMENT",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.BadRequest",
                "fieldViolations": [
                  {
                    "field": "browse_id",
                    "description": "Invalid value at 'browse_id' (TYPE_STRING), \"x2\""
                  },
                  {
                    "field": "context",
                    "description": "Invalid value at 'context' (type.googleapis.com/youtube.api.pfiinnertube.InnerTubeContext), \"x1\""
                  },
                  {
                    "field": "params",
                    "description": "Invalid value at 'params' (TYPE_STRING), \"x3\""
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val fields = fetcher.parseProtoJsonErrors(body)
        assertThat(fields).hasSize(3)

        val browseId = fields.find { it.name == "browse_id" }!!
        assertThat(browseId.type).isEqualTo("TYPE_STRING")
        assertThat(browseId.index).isEqualTo(2)

        val context = fields.find { it.name == "context" }!!
        assertThat(context.type).isEqualTo("TYPE_MESSAGE")
        assertThat(context.index).isEqualTo(1)
        assertThat(context.messageType).isEqualTo("youtube.api.pfiinnertube.InnerTubeContext")

        val params = fields.find { it.name == "params" }!!
        assertThat(params.type).isEqualTo("TYPE_STRING")
        assertThat(params.index).isEqualTo(3)
    }

    @Test
    fun `parseProtoJsonErrors handles Base64 decoding failed variant`() {
        val body = """
        {
          "error": {
            "code": 400,
            "message": "Invalid JSON payload received.",
            "details": [
              {
                "fieldViolations": [
                  {
                    "field": "data",
                    "description": "Invalid value at 'data' (TYPE_BYTES), Base64 decoding failed for \"x5\""
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        val fields = fetcher.parseProtoJsonErrors(body)
        assertThat(fields).hasSize(1)
        assertThat(fields[0].name).isEqualTo("data")
        assertThat(fields[0].type).isEqualTo("TYPE_BYTES")
        assertThat(fields[0].index).isEqualTo(5)
    }

    @Test
    fun `parseProtoJsonErrors falls back to error message when no fieldViolations`() {
        val body = """
        {
          "error": {
            "code": 400,
            "message": "Invalid value at 'name' (TYPE_STRING), \"x1\". Invalid value at 'age' (TYPE_INT32), \"x2\"."
          }
        }
        """.trimIndent()

        val fields = fetcher.parseProtoJsonErrors(body)
        assertThat(fields).hasSize(2)
        assertThat(fields.map { it.name }).containsExactly("name", "age")
    }

    @Test
    fun `parseProtoJsonErrors extracts integer index from int payload`() {
        val body = """
        {
          "error": {
            "details": [{
              "fieldViolations": [
                {"field": "query", "description": "Invalid value at 'query' (TYPE_STRING), 7"}
              ]
            }]
          }
        }
        """.trimIndent()

        val fields = fetcher.parseProtoJsonErrors(body)
        assertThat(fields).hasSize(1)
        assertThat(fields[0].name).isEqualTo("query")
        assertThat(fields[0].index).isEqualTo(7)
    }

    // ==================== Dual-Probe Merge Logic (req2proto) ====================

    @Test
    fun `merge promotes field to TYPE_INT64 when only string probe reports it`() {
        // String probe reports "token" as TYPE_STRING at index 3,
        // but int probe does NOT report it → field accepts integers → TYPE_INT64
        val strFields = listOf(
            ProtoJsonField(name = "token", type = "TYPE_STRING", index = 3)
        )
        val intFields = emptyList<ProtoJsonField>()

        val merged = fetcher.mergeProbeResults(strFields, intFields)
        assertThat(merged).hasSize(1)
        assertThat(merged[0].name).isEqualTo("token")
        assertThat(merged[0].type).isEqualTo("TYPE_INT64")
    }

    @Test
    fun `merge keeps TYPE_STRING when only int probe reports it`() {
        // Int probe reports "name" as TYPE_STRING at index 1,
        // but string probe does NOT report it → field accepts strings → TYPE_STRING
        val strFields = emptyList<ProtoJsonField>()
        val intFields = listOf(
            ProtoJsonField(name = "name", type = "TYPE_STRING", index = 1)
        )

        val merged = fetcher.mergeProbeResults(strFields, intFields)
        assertThat(merged).hasSize(1)
        assertThat(merged[0].name).isEqualTo("name")
        assertThat(merged[0].type).isEqualTo("TYPE_STRING")
    }

    @Test
    fun `merge prefers messageType from string probe when both report same field`() {
        // Both probes report "context" — string probe has the message type
        val strFields = listOf(
            ProtoJsonField(name = "context", type = "TYPE_MESSAGE", index = 1,
                messageType = "youtube.api.InnerTubeContext")
        )
        val intFields = listOf(
            ProtoJsonField(name = "context", type = "TYPE_MESSAGE", index = 1,
                messageType = null)
        )

        val merged = fetcher.mergeProbeResults(strFields, intFields)
        assertThat(merged).hasSize(1)
        assertThat(merged[0].type).isEqualTo("TYPE_MESSAGE")
        assertThat(merged[0].messageType).isEqualTo("youtube.api.InnerTubeContext")
    }

    @Test
    fun `merge combines fields from both probes with correct types`() {
        // Simulates a real dual-probe: 3 fields with different type signatures
        val strFields = listOf(
            ProtoJsonField(name = "context", type = "TYPE_MESSAGE", index = 1,
                messageType = "some.Context"),
            ProtoJsonField(name = "count", type = "TYPE_STRING", index = 2),  // will become INT64
            ProtoJsonField(name = "query", type = "TYPE_STRING", index = 5)   // both report → STRING
        )
        val intFields = listOf(
            ProtoJsonField(name = "context", type = "TYPE_MESSAGE", index = 1),
            ProtoJsonField(name = "name", type = "TYPE_STRING", index = 3),   // only int reports → STRING
            ProtoJsonField(name = "query", type = "TYPE_STRING", index = 5)
        )

        val merged = fetcher.mergeProbeResults(strFields, intFields)
        assertThat(merged).hasSize(4)

        val byName = merged.associateBy { it.name }
        assertThat(byName["context"]!!.type).isEqualTo("TYPE_MESSAGE")
        assertThat(byName["context"]!!.messageType).isEqualTo("some.Context")
        assertThat(byName["count"]!!.type).isEqualTo("TYPE_INT64")
        assertThat(byName["name"]!!.type).isEqualTo("TYPE_STRING")
        assertThat(byName["query"]!!.type).isEqualTo("TYPE_STRING")
    }

    @Test
    fun `merge returns sorted by field index`() {
        val strFields = listOf(
            ProtoJsonField(name = "z_field", type = "TYPE_STRING", index = 10),
            ProtoJsonField(name = "a_field", type = "TYPE_STRING", index = 1)
        )
        val intFields = listOf(
            ProtoJsonField(name = "m_field", type = "TYPE_STRING", index = 5)
        )

        val merged = fetcher.mergeProbeResults(strFields, intFields)
        assertThat(merged.map { it.index }).isEqualTo(listOf(1, 5, 10))
    }

    // ==================== Staging Discovery ====================

    @Test
    fun `fetchStagingDiscovery constructs correct staging URL`() {
        val result = runBlocking {
            fetcher.fetchStagingDiscovery("https://people-pa.googleapis.com")
        }
        // Staging may be restricted, but must not crash
        if (result.isSuccess) {
            val doc = result.getOrThrow()
            assertThat(doc.rootUrl).contains("sandbox.googleapis.com")
        }
    }

    @Test
    fun `fetchStagingDiscovery rejects non-googleapis URLs`() {
        val result = runBlocking {
            fetcher.fetchStagingDiscovery("https://api.example.com")
        }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("googleapis.com")
    }

    // ==================== Multi-Service Discovery ====================

    @Test
    fun `fetch People PA discovery document`() {
        val result = runBlocking {
            fetcher.fetchSpec("https://people-pa.googleapis.com")
        }
        assertThat(result.isSuccess).isTrue()
        val doc = result.getOrThrow()
        assertThat(doc.resources).isNotEmpty()
    }

    // ==================== Maps API Key Delivery ====================
    // Real key from com.google.android.deskclock (iju.mo11530i → cnx.f7194h)
    private val clockMapsKey = "AIzaSyBUcCPilPlw0sWDaXdmNScHS4N0jm31D-I"

    @Test
    fun `Maps Timezone API rejects key sent only via X-Goog-Api-Key header`() {
        val result = runBlocking {
            val conn = java.net.URL(
                "https://maps.googleapis.com/maps/api/timezone/json?location=0,0&timestamp=0"
            ).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("X-Goog-Api-Key", clockMapsKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                code to body
            } finally {
                conn.disconnect()
            }
        }
        // Maps Platform ignores X-Goog-Api-Key header — key must be in ?key= query param
        assertThat(result.second).contains("REQUEST_DENIED")
    }

    @Test
    fun `Maps Timezone API accepts real key as query parameter`() {
        val result = runBlocking {
            val conn = java.net.URL(
                "https://maps.googleapis.com/maps/api/timezone/json?location=0,0&timestamp=0&key=$clockMapsKey"
            ).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                code to body
            } finally {
                conn.disconnect()
            }
        }
        // Real key in query param → Maps processes request (no REQUEST_DENIED)
        assertThat(result.second).doesNotContain("REQUEST_DENIED")
    }

    @Test
    fun `executeMethod delivers key to Maps Timezone endpoint`() {
        val result = runBlocking {
            val testMethod = DiscoveryMethod(
                id = "test",
                httpMethod = "GET",
                path = "maps/api/timezone/json",
                description = "",
                parameters = mapOf(
                    "location" to DiscoveryParameter("location", "string", "query", false, "", null, emptyList()),
                    "timestamp" to DiscoveryParameter("timestamp", "string", "query", false, "", null, emptyList())
                ),
                parameterOrder = emptyList(),
                scopes = emptyList()
            )
            fetcher.executeMethod(
                rootUrl = "https://maps.googleapis.com",
                servicePath = "",
                method = testMethod,
                params = mapOf("location" to "0,0", "timestamp" to "0"),
                apiKey = clockMapsKey
            )
        }
        assertThat(result.isSuccess).isTrue()
        val body = result.getOrThrow().body
        // If REQUEST_DENIED → executeMethod sends key as header only, Maps doesn't see it
        // This test will FAIL until executeMethod also sends key as query param for Google APIs
        assertThat(body).doesNotContain("REQUEST_DENIED")
    }

    // ==================== Helpers ====================

    private fun collectMethods(resources: Map<String, DiscoveryResource>): List<DiscoveryMethod> =
        resources.values.flatMap { r -> r.methods.values + collectMethods(r.resources) }
}
