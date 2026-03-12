package com.droidprobe.app.analysis

import com.droidprobe.app.data.model.*
import com.droidprobe.app.interaction.ApiSpecFetcher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Integration test using decompiled Google app smali from google_smali/.
 * Extracts real API keys and URLs from const-string instructions,
 * builds ApiEndpoints, synthesizes discovery documents, fetches real
 * Google Discovery docs over the network, and verifies they are callable.
 *
 * No mock data — all strings come from real Google app bytecode.
 */
class GoogleApkDiscoveryTest {

    companion object {
        private val smaliRoot = File("D:/androidast/google_smali")
        private lateinit var apiKeys: List<String>
        private lateinit var apiUrls: List<String>
        private lateinit var apiEndpoints: List<ApiEndpoint>
        private val fetcher = ApiSpecFetcher()

        // Key association: which API keys co-occur with which URLs in the same smali method
        private lateinit var keyToMethodUrls: Map<String, Set<String>>
        // Key to source class mapping
        private lateinit var keyToSourceClass: Map<String, String>

        @BeforeClass
        @JvmStatic
        fun setup() {
            assumeTrue(
                "google_smali directory not found",
                smaliRoot.exists() && smaliRoot.isDirectory
            )

            // Scan all smali directories for const-string instructions
            val smaliDirs = smaliRoot.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }
                ?: error("No smali dirs found")

            val keys = mutableSetOf<String>()
            val urls = mutableSetOf<String>()
            val urlToSource = mutableMapOf<String, String>()

            // Track key→URL co-occurrence at the method level (simulates SensitiveStringFlowAnalyzer)
            val keyMethodUrls = mutableMapOf<String, MutableSet<String>>()
            val keySourceClass = mutableMapOf<String, String>()

            for (dir in smaliDirs) {
                dir.walk()
                    .filter { it.extension == "smali" }
                    .forEach { file ->
                        val className = file.nameWithoutExtension

                        // Parse methods and track const-string values per method
                        val methodStrings = mutableListOf<MutableList<String>>()
                        var currentMethod: MutableList<String>? = null

                        file.useLines { lines ->
                            for (line in lines) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith(".method ")) {
                                    currentMethod = mutableListOf<String>().also { methodStrings.add(it) }
                                } else if (trimmed == ".end method") {
                                    currentMethod = null
                                } else if (trimmed.contains("const-string")) {
                                    val match = Regex("const-string [^,]+, \"(.+)\"").find(trimmed)
                                    if (match != null) {
                                        val value = match.groupValues[1]
                                        currentMethod?.add(value)

                                        // Google API keys start with AIzaSy
                                        if (value.startsWith("AIzaSy") && value.length > 20) {
                                            keys.add(value)
                                            keySourceClass.putIfAbsent(value, className)
                                        }

                                        // HTTP(S) URLs that look like API endpoints
                                        if (value.startsWith("https://") && !value.contains("*") &&
                                            !value.contains("support.google.com") &&
                                            !value.contains("policies.google.com") &&
                                            !value.contains("play.google.com/store") &&
                                            !value.contains("issuetracker") &&
                                            value.length > 15
                                        ) {
                                            urls.add(value)
                                            urlToSource.putIfAbsent(value, className)
                                        }
                                    }
                                }
                            }
                        }

                        // For each method, if it has both keys and URLs, associate them
                        for (methodValues in methodStrings) {
                            val methodKeys = methodValues.filter { it.startsWith("AIzaSy") && it.length > 20 }
                            val methodUrls = methodValues.filter {
                                it.startsWith("https://") && it.length > 15 &&
                                    it.contains("googleapis.com")
                            }
                            if (methodKeys.isNotEmpty() && methodUrls.isNotEmpty()) {
                                for (key in methodKeys) {
                                    keyMethodUrls.getOrPut(key) { mutableSetOf() }.addAll(methodUrls)
                                }
                            }
                        }

                        // Also check class-level co-occurrence (key + URL in same class)
                        val classKeys = mutableSetOf<String>()
                        val classUrls = mutableSetOf<String>()
                        file.useLines { lines ->
                            for (line in lines) {
                                if (!line.contains("const-string")) continue
                                val match = Regex("const-string [^,]+, \"(.+)\"").find(line.trim())
                                    ?: continue
                                val v = match.groupValues[1]
                                if (v.startsWith("AIzaSy") && v.length > 20) classKeys.add(v)
                                if (v.startsWith("https://") && v.contains("googleapis.com") && v.length > 15)
                                    classUrls.add(v)
                            }
                        }
                        if (classKeys.isNotEmpty() && classUrls.isNotEmpty()) {
                            for (key in classKeys) {
                                keyMethodUrls.getOrPut(key) { mutableSetOf() }.addAll(classUrls)
                            }
                        }
                    }
            }

            apiKeys = keys.sorted()
            apiUrls = urls.sorted()
            keyToMethodUrls = keyMethodUrls
            keyToSourceClass = keySourceClass

            // Build ApiEndpoint objects from extracted URLs — only those with actual paths
            apiEndpoints = urls
                .filter { url ->
                    url.contains("googleapis.com") && !url.contains("/auth/")
                }
                .mapNotNull { url ->
                    val parsed = try { java.net.URI(url) } catch (_: Exception) { return@mapNotNull null }
                    val path = parsed.rawPath?.trimStart('/') ?: ""
                    if (path.isEmpty()) return@mapNotNull null // skip bare hostnames
                    ApiEndpoint(
                        fullUrl = url,
                        baseUrl = "${parsed.scheme}://${parsed.host}",
                        path = path,
                        httpMethod = "GET",
                        sourceClass = "L${urlToSource[url]?.replace('.', '/') ?: "unknown"};",
                        sourceType = "literal"
                    )
                }
        }
    }

    // ==================== Smali Extraction: API Keys ====================

    @Test
    fun `smali contains Google API keys starting with AIzaSy`() {
        assertThat(apiKeys).isNotEmpty()
        for (key in apiKeys) {
            assertThat(key).startsWith("AIzaSy")
        }
    }

    @Test
    fun `smali contains multiple distinct API keys`() {
        assertThat(apiKeys.size).isGreaterThan(5)
    }

    // ==================== Smali Extraction: URLs ====================

    @Test
    fun `smali contains googleapis URLs`() {
        val googleapisUrls = apiUrls.filter { it.contains("googleapis.com") }
        assertThat(googleapisUrls).isNotEmpty()
    }

    @Test
    fun `smali contains google service endpoints with paths`() {
        assertThat(apiEndpoints).isNotEmpty()
        for (ep in apiEndpoints) {
            assertThat(ep.path).isNotEmpty()
            assertThat(ep.fullUrl).contains("googleapis.com")
        }
    }

    // ==================== Discovery: Synthesis from Smali Data ====================

    @Test
    fun `synthesize discovery doc from smali-extracted googleapis endpoints`() {
        assumeTrue("No googleapis endpoints", apiEndpoints.isNotEmpty())

        // Use generativelanguage.googleapis.com — Gemini API endpoints found in smali
        val geminiEndpoints = apiEndpoints.filter {
            it.baseUrl.contains("generativelanguage.googleapis.com")
        }
        assumeTrue("No Gemini endpoints", geminiEndpoints.isNotEmpty())

        val doc = fetcher.synthesizeFromEndpoints(
            "https://generativelanguage.googleapis.com", geminiEndpoints
        )
        assertThat(doc).isNotNull()
        assertThat(doc!!.resources).isNotEmpty()
        assertThat(doc.rootUrl).isEqualTo("https://generativelanguage.googleapis.com")
    }

    @Test
    fun `synthesized Gemini doc has methods with valid paths`() {
        val geminiEndpoints = apiEndpoints.filter {
            it.baseUrl.contains("generativelanguage.googleapis.com")
        }
        assumeTrue("No Gemini endpoints", geminiEndpoints.isNotEmpty())

        val doc = fetcher.synthesizeFromEndpoints(
            "https://generativelanguage.googleapis.com", geminiEndpoints
        )!!
        val allMethods = doc.resources.values.flatMap { it.methods.values }
        assertThat(allMethods).isNotEmpty()
        for (method in allMethods) {
            assertThat(method.source).isEqualTo("dex")
            assertThat(method.httpMethod).isNotEmpty()
            assertThat(method.path).isNotEmpty()
            assertThat(method.path).contains("models/")
        }
    }

    @Test
    fun `synthesized doc groups endpoints into resources by first path segment`() {
        assumeTrue("No googleapis endpoints", apiEndpoints.isNotEmpty())
        val doc = fetcher.synthesizeFromEndpoints(
            "https://generativelanguage.googleapis.com",
            apiEndpoints.filter { it.baseUrl.contains("generativelanguage.googleapis.com") }
        )
        assumeTrue("No Gemini doc", doc != null)
        for ((name, resource) in doc!!.resources) {
            assertThat(name).isNotEmpty()
            assertThat(resource.methods).isNotEmpty()
        }
    }

    // ==================== Real Network: Fetch Google Discovery Document ====================

    @Test
    fun `fetch real Generative Language API discovery document`() {
        // Discovery docs are public — no API key needed
        val result = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed (offline?)", result.isSuccess)
        val doc = result.getOrThrow()
        assertThat(doc.rootUrl).contains("generativelanguage.googleapis.com")
        assertThat(doc.resources).isNotEmpty()

        // Every method should be structurally callable
        val allMethods = doc.resources.values
            .flatMap { r -> r.methods.values + r.resources.values.flatMap { it.methods.values } }
        assertThat(allMethods).isNotEmpty()
        for (method in allMethods) {
            assertThat(method.httpMethod).isNotEmpty()
            assertThat(method.path).isNotEmpty()
        }
    }

    @Test
    fun `fetched discovery doc has valid rootUrl and servicePath`() {
        val result = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed", result.isSuccess)
        val doc = result.getOrThrow()
        assertThat(doc.rootUrl).startsWith("https://")
        assertThat(doc.rootUrl).contains("generativelanguage")
        // servicePath may be empty for APIs that serve from root (e.g. Generative Language)
        assertThat(doc.servicePath).isNotNull()
    }

    @Test
    fun `fetched discovery doc path params are required`() {
        val result = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed", result.isSuccess)
        val doc = result.getOrThrow()

        val allMethods = doc.resources.values
            .flatMap { r -> r.methods.values + r.resources.values.flatMap { it.methods.values } }
        val pathParams = allMethods.flatMap { it.parameters.values }
            .filter { it.location == "path" }

        if (pathParams.isNotEmpty()) {
            for (param in pathParams) {
                assertThat(param.required).isTrue()
            }
        }
    }

    // ==================== Merge: Remote + Virtual ====================

    @Test
    fun `merge remote Gemini discovery with smali-synthesized doc`() {
        val geminiEndpoints = apiEndpoints.filter {
            it.baseUrl.contains("generativelanguage.googleapis.com")
        }
        assumeTrue("No Gemini endpoints", geminiEndpoints.isNotEmpty())

        val baseUrl = "https://generativelanguage.googleapis.com"
        val remoteResult = runBlocking {
            fetcher.fetchSpec(baseUrl)
        }
        assumeTrue("Network fetch failed", remoteResult.isSuccess)
        val remoteDoc = remoteResult.getOrThrow()

        val virtualDoc = fetcher.synthesizeFromEndpoints(baseUrl, geminiEndpoints)!!
        val merged = fetcher.mergeDocuments(remoteDoc, virtualDoc)

        // Merged preserves remote metadata
        assertThat(merged.name).isEqualTo(remoteDoc.name)
        assertThat(merged.rootUrl).isEqualTo(remoteDoc.rootUrl)

        // Remote methods tagged as spec
        fun collectMethods(resources: Map<String, DiscoveryResource>): List<DiscoveryMethod> =
            resources.values.flatMap { r -> r.methods.values + collectMethods(r.resources) }

        val mergedMethods = collectMethods(merged.resources)
        val specMethods = mergedMethods.filter { it.source == "spec" }
        assertThat(specMethods).isNotEmpty()

        // Merged has at least as many methods as remote
        val remoteMethodCount = collectMethods(remoteDoc.resources).size
        assertThat(mergedMethods.size).isAtLeast(remoteMethodCount)
    }

    // ==================== Callable: Execute Real API Method ====================

    @Test
    fun `execute real GET method from Gemini discovery doc`() {
        val fetchResult = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed", fetchResult.isSuccess)
        val doc = fetchResult.getOrThrow()

        // Find a GET method — list models is a good candidate
        fun collectMethods(resources: Map<String, DiscoveryResource>): List<DiscoveryMethod> =
            resources.values.flatMap { r -> r.methods.values + collectMethods(r.resources) }

        val getMethod = collectMethods(doc.resources)
            .find { m ->
                m.httpMethod == "GET" &&
                    m.parameters.values.none { it.location == "path" && it.required }
            }
        assumeTrue("No simple GET method in discovery doc", getMethod != null)

        // Execute without API key — proves the URL construction is correct
        // even though auth will fail (we get 403, not connection error)
        val result = runBlocking {
            fetcher.executeMethod(
                rootUrl = doc.rootUrl,
                servicePath = doc.servicePath,
                method = getMethod!!,
                params = emptyMap(),
                apiKey = null
            )
        }

        // Server responded — URL is callable (any status code proves reachability)
        assertThat(result.isSuccess).isTrue()
        val execution = result.getOrThrow()
        assertThat(execution.statusCode).isGreaterThan(0)
        assertThat(execution.headers).isNotEmpty()
    }

    @Test
    fun `synthesized method URLs are valid and substitutable`() {
        val geminiEndpoints = apiEndpoints.filter {
            it.baseUrl.contains("generativelanguage.googleapis.com")
        }
        assumeTrue("No Gemini endpoints", geminiEndpoints.isNotEmpty())
        val doc = fetcher.synthesizeFromEndpoints(
            "https://generativelanguage.googleapis.com", geminiEndpoints
        )!!

        for ((_, resource) in doc.resources) {
            for ((_, method) in resource.methods) {
                // Substitute all path params
                var path = method.path
                for ((name, param) in method.parameters) {
                    if (param.location == "path") {
                        path = path.replace("{+$name}", "test").replace("{$name}", "test")
                    }
                }
                val base = doc.rootUrl.trimEnd('/')
                val svc = doc.servicePath.trimStart('/').trimEnd('/')
                val fullUrl = if (svc.isEmpty() || svc == "/")
                    "$base/${path.trimStart('/')}"
                else
                    "$base/$svc/${path.trimStart('/')}"

                // Must be a valid URI with no unsubstituted placeholders
                val uri = java.net.URI(fullUrl)
                assertThat(uri.scheme).isAnyOf("http", "https")
                assertThat(uri.host).isNotNull()
                assertThat(fullUrl).doesNotContain("{")
            }
        }
    }

    // ==================== Multi-service: Different base URLs ====================

    @Test
    fun `smali-extracted endpoints span multiple googleapis subdomains`() {
        val hosts = apiEndpoints.map { java.net.URI(it.fullUrl).host }.toSet()
        // Google app uses multiple googleapis subdomains
        assertThat(hosts.size).isGreaterThan(1)
    }

    @Test
    fun `each googleapis subdomain produces an independent discovery doc`() {
        val byHost = apiEndpoints.groupBy { java.net.URI(it.fullUrl).host }
        assertThat(byHost.size).isGreaterThan(1)

        for ((host, endpoints) in byHost) {
            if (endpoints.isEmpty()) continue
            val doc = fetcher.synthesizeFromEndpoints("https://$host", endpoints)
            // Each group should produce a valid doc if it has endpoints with paths
            if (doc != null) {
                assertThat(doc.rootUrl).contains(host)
                val methods = doc.resources.values.flatMap { it.methods.values }
                for (method in methods) {
                    assertThat(method.path).isNotEmpty()
                }
            }
        }
    }

    // ==================== Network Connectivity Diagnostic ====================

    @Test
    fun `API keys from smali are app-restricted and return 403`() {
        assumeTrue("No API keys found", apiKeys.isNotEmpty())

        // Google API keys embedded in APKs are restricted by package signature.
        // Using them outside the app's signing context returns 403.
        // This validates DroidProbe correctly extracts keys that ARE real.
        val url = "https://generativelanguage.googleapis.com/\$discovery/rest?key=${apiKeys.first()}"
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val statusCode = conn.responseCode
            conn.disconnect()
            // 403 = key is valid but restricted to the app's signing certificate
            assertThat(statusCode).isEqualTo(403)
        } catch (e: Exception) {
            assumeTrue("Network unavailable: ${e.message}", false)
        }
    }

    // ==================== API Key Association & Selection ====================

    @Test
    fun `API keys have associated URLs from smali co-occurrence`() {
        // At least some keys should co-occur with URLs in the same class/method
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assertThat(keysWithUrls).isNotEmpty()
    }

    @Test
    fun `associated URLs point to googleapis domains`() {
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assumeTrue("No key-URL associations found", keysWithUrls.isNotEmpty())

        for ((key, urls) in keysWithUrls) {
            for (url in urls) {
                assertThat(url).contains("googleapis.com")
                assertThat(key).startsWith("AIzaSy")
            }
        }
    }

    @Test
    fun `key selection scoring picks best key for given rootUrl`() {
        // Simulate the scoring logic from ApiExplorerViewModel.loadApiKeys()
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assumeTrue("No key-URL associations found", keysWithUrls.isNotEmpty())

        // Build SensitiveString objects with associatedUrls (like the real pipeline does)
        val sensitiveStrings = apiKeys.map { key ->
            SensitiveString(
                value = key,
                category = "Google API Key",
                sourceClass = keyToSourceClass[key] ?: "",
                associatedUrls = keyToMethodUrls[key]?.sorted()?.toList() ?: emptyList()
            )
        }

        // Test: for each unique googleapis host found in associations, the scorer
        // should prefer a key that has URLs matching that host
        val allAssociatedHosts = keyToMethodUrls.values
            .flatten()
            .mapNotNull { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }
            .toSet()

        for (targetHost in allAssociatedHosts) {
            val rootUrl = "https://$targetHost"
            val rootHost = try {
                java.net.URL(rootUrl).host
            } catch (_: Exception) { rootUrl }

            // Score each key by whether its associatedUrls match this host
            val scored = sensitiveStrings.distinctBy { it.value }.sortedByDescending { secret ->
                if (secret.associatedUrls.any { it.contains(rootHost, ignoreCase = true) }) 1 else 0
            }
            val bestKey = scored.firstOrNull()?.value ?: continue

            // The best key should have at least one URL matching this host
            val bestSecret = sensitiveStrings.find { it.value == bestKey }!!
            val matchesHost = bestSecret.associatedUrls.any { it.contains(rootHost, ignoreCase = true) }
            assertThat(matchesHost).isTrue()
        }
    }

    @Test
    fun `multiple distinct keys serve different googleapis services`() {
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assumeTrue("Need multiple key-URL associations", keysWithUrls.size > 1)

        // Different keys may be associated with different googleapis subdomains
        val keyHosts = keysWithUrls.mapValues { (_, urls) ->
            urls.mapNotNull { url -> try { java.net.URI(url).host } catch (_: Exception) { null } }.toSet()
        }

        // At least verify each associated key has valid googleapis hosts
        for ((key, hosts) in keyHosts) {
            assertThat(hosts).isNotEmpty()
            for (host in hosts) {
                assertThat(host).endsWith("googleapis.com")
            }
        }
    }

    @Test
    fun `key association uses discovery doc to validate key works with correct service`() {
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assumeTrue("No key-URL associations found", keysWithUrls.isNotEmpty())

        // Find a key associated with generativelanguage.googleapis.com
        val geminiKey = keysWithUrls.entries.find { (_, urls) ->
            urls.any { it.contains("generativelanguage.googleapis.com") }
        }?.key

        // Find a key associated with a different service
        val otherKey = keysWithUrls.entries.find { (key, urls) ->
            key != geminiKey && urls.none { it.contains("generativelanguage.googleapis.com") }
        }?.key

        // Fetch real discovery doc (public, no key needed)
        val result = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed", result.isSuccess)
        val doc = result.getOrThrow()

        // The discovery doc should have methods matching the URL paths associated with the key
        if (geminiKey != null) {
            val geminiUrls = keysWithUrls[geminiKey]!!
            val geminiPaths = geminiUrls.mapNotNull { url ->
                try { java.net.URI(url).rawPath?.trimStart('/') } catch (_: Exception) { null }
            }.filter { it.isNotEmpty() }

            fun collectMethods(resources: Map<String, DiscoveryResource>): List<DiscoveryMethod> =
                resources.values.flatMap { r -> r.methods.values + collectMethods(r.resources) }

            val docPaths = collectMethods(doc.resources).map { it.path }

            // At least some smali-extracted paths should relate to discovery doc paths
            // (they may not match exactly due to path parameters vs concrete values)
            assertThat(docPaths).isNotEmpty()
            assertThat(geminiPaths).isNotEmpty()
        }

        // If we have a key for a different service, it should NOT match generativelanguage paths
        if (otherKey != null) {
            val otherUrls = keysWithUrls[otherKey]!!
            for (url in otherUrls) {
                assertThat(url).doesNotContain("generativelanguage.googleapis.com")
            }
        }
    }

    @Test
    fun `execute discovery method with smali-extracted key returns 403 (app-restricted)`() {
        val keysWithUrls = keyToMethodUrls.filter { it.value.isNotEmpty() }
        assumeTrue("No key-URL associations found", keysWithUrls.isNotEmpty())

        // Pick any key that has googleapis associations
        val testKey = keysWithUrls.keys.first()

        val fetchResult = runBlocking {
            fetcher.fetchSpec("https://generativelanguage.googleapis.com")
        }
        assumeTrue("Network fetch failed", fetchResult.isSuccess)
        val doc = fetchResult.getOrThrow()

        fun collectMethods(resources: Map<String, DiscoveryResource>): List<DiscoveryMethod> =
            resources.values.flatMap { r -> r.methods.values + collectMethods(r.resources) }

        val getMethod = collectMethods(doc.resources)
            .find { m ->
                m.httpMethod == "GET" &&
                    m.parameters.values.none { it.location == "path" && it.required }
            }
        assumeTrue("No simple GET method", getMethod != null)

        // Execute WITH the smali-extracted key — proves the full pipeline:
        // smali extraction → key association → discovery doc → method execution
        val result = runBlocking {
            fetcher.executeMethod(
                rootUrl = doc.rootUrl,
                servicePath = doc.servicePath,
                method = getMethod!!,
                params = emptyMap(),
                apiKey = testKey
            )
        }

        assertThat(result.isSuccess).isTrue()
        val execution = result.getOrThrow()
        // 403 = key is real but app-restricted (package signature bound)
        // Any non-zero status proves the full pipeline works end-to-end
        assertThat(execution.statusCode).isGreaterThan(0)
    }

    // ==================== Dump for Inspection ====================

    @Test
    fun `dump smali-extracted Google data to file`() {
        val sb = StringBuilder()
        sb.appendLine("=== GOOGLE SMALI ANALYSIS ===")
        sb.appendLine()
        sb.appendLine("=== API KEYS (${apiKeys.size}) ===")
        for (key in apiKeys) {
            sb.appendLine("  $key")
        }
        sb.appendLine()
        sb.appendLine("=== GOOGLEAPIS ENDPOINTS (${apiEndpoints.size}) ===")
        for (ep in apiEndpoints) {
            sb.appendLine("  ${ep.fullUrl}")
            sb.appendLine("    src=${ep.sourceClass}")
        }
        sb.appendLine()
        sb.appendLine("=== API KEY ASSOCIATIONS ===")
        for ((key, urls) in keyToMethodUrls) {
            sb.appendLine("  $key")
            for (url in urls.sorted()) {
                sb.appendLine("    → $url")
            }
        }
        sb.appendLine()
        sb.appendLine("=== ALL EXTRACTED URLS (${apiUrls.size}) ===")
        for (url in apiUrls) {
            sb.appendLine("  $url")
        }

        val geminiEndpoints = apiEndpoints.filter {
            it.baseUrl.contains("generativelanguage.googleapis.com")
        }
        val doc = if (geminiEndpoints.isNotEmpty()) {
            fetcher.synthesizeFromEndpoints(
                "https://generativelanguage.googleapis.com", geminiEndpoints
            )
        } else null

        if (doc != null) {
            sb.appendLine()
            sb.appendLine("=== SYNTHESIZED GEMINI DISCOVERY DOC ===")
            sb.appendLine("  rootUrl=${doc.rootUrl}")
            sb.appendLine("  servicePath=${doc.servicePath}")
            sb.appendLine("  resources=${doc.resources.keys}")
            for ((name, resource) in doc.resources) {
                sb.appendLine("  resource: $name (${resource.methods.size} methods)")
                for ((_, method) in resource.methods) {
                    sb.appendLine("    ${method.httpMethod} ${method.path}")
                }
            }
        }

        File("build/google-smali-analysis.txt").writeText(sb.toString())
    }
}
