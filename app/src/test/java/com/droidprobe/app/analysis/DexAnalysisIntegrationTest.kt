package com.droidprobe.app.analysis

import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.data.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Integration test that runs the full DexAnalyzer pipeline on the testapp APK
 * and verifies every extractor finds its expected patterns (positive tests)
 * and does NOT over-attribute patterns to wrong components (negative tests).
 */
class DexAnalysisIntegrationTest {

    companion object {
        private lateinit var analysis: DexAnalysis
        private const val PKG = "com.droidprobe.testapp"

        @BeforeClass
        @JvmStatic
        fun setup() {
            val apkStream = DexAnalysisIntegrationTest::class.java
                .getResourceAsStream("/testapp.apk")
                ?: error("testapp.apk not found in test resources. Run: ./gradlew :testapp:assembleDebug")

            val tempFile = File.createTempFile("testapp", ".apk")
            tempFile.deleteOnExit()
            apkStream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }

            val manifest = buildManifestAnalysis()

            analysis = runBlocking {
                DexAnalyzer().analyze(tempFile.absolutePath, manifest)
            }
        }

        private fun buildManifestAnalysis(): ManifestAnalysis {
            fun activity(name: String, exported: Boolean, filters: List<IntentFilterInfo> = emptyList(), target: String? = null) =
                ExportedComponent(
                    name = "$PKG.activities.$name",
                    isExported = exported,
                    permission = null,
                    intentFilters = filters,
                    targetActivity = target
                )

            fun actionFilter(action: String) = listOf(
                IntentFilterInfo(
                    actions = listOf(action),
                    categories = listOf("android.intent.category.DEFAULT"),
                    dataSchemes = emptyList(),
                    dataAuthorities = emptyList(),
                    dataPaths = emptyList(),
                    mimeTypes = emptyList()
                )
            )

            fun provider(name: String, authority: String, exported: Boolean) =
                ProviderComponent(
                    name = "$PKG.providers.$name",
                    authority = authority,
                    isExported = exported,
                    permission = null,
                    readPermission = null,
                    writePermission = null,
                    grantUriPermissions = false,
                    pathPermissions = emptyList()
                )

            return ManifestAnalysis(
                packageName = PKG,
                activities = listOf(
                    activity("DirectExtraActivity", true, actionFilter("$PKG.action.DIRECT")),
                    activity("BaseExtraActivity", false),
                    activity("InheritedChildActivity", true, actionFilter("$PKG.action.INHERITED")),
                    activity("ValueScanActivity", true, actionFilter("$PKG.action.VALUES")),
                    activity("BundleExtraActivity", true, actionFilter("$PKG.action.BUNDLE")),
                    activity("PutExtraActivity", true, actionFilter("$PKG.action.PUT")),
                    activity("InterProcActivity", true, actionFilter("$PKG.action.INTERPROC")),
                    activity("DeepLinkActivity", true, listOf(
                        IntentFilterInfo(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                            dataSchemes = listOf("testapp"),
                            dataAuthorities = listOf("open"),
                            dataPaths = emptyList(),
                            mimeTypes = emptyList()
                        )
                    ))
                ),
                services = emptyList(),
                receivers = emptyList(),
                providers = listOf(
                    provider("BasicProvider", "$PKG.basic", true),
                    provider("DispatchProvider", "$PKG.dispatch", true),
                    provider("ParamProvider", "$PKG.params", true),
                    provider("StaticUriProvider", "$PKG.static", true),
                    ProviderComponent(
                        name = "androidx.core.content.FileProvider",
                        authority = "$PKG.fileprovider",
                        isExported = false,
                        permission = null,
                        readPermission = null,
                        writePermission = null,
                        grantUriPermissions = true,
                        pathPermissions = emptyList()
                    )
                ),
                customPermissions = emptyList()
            )
        }

        // Helper: get extras for a specific component
        private fun extrasFor(component: String): List<IntentInfo> =
            analysis.intentExtras.filter {
                it.associatedComponent == "$PKG.activities.$component"
            }

        // Helper: get URI results for a specific authority
        private fun urisForAuthority(authority: String): List<ContentProviderInfo> =
            analysis.contentProviderUris.filter { it.authority == authority }

        // Helper: get all query params across all URIs for an authority
        private fun queryParamsForAuthority(authority: String): Set<String> =
            urisForAuthority(authority).flatMap { it.queryParameters }.toSet()
    }

    // ==================== UriPatternExtractor — POSITIVE ====================

    @Test
    fun `UriMatcher patterns detected for BasicProvider`() {
        val uris = urisForAuthority("$PKG.basic")
        val patterns = uris.map { it.uriPattern }
        assertThat(patterns).containsAtLeast(
            "content://$PKG.basic/items",
            "content://$PKG.basic/items/#"
        )
        // categories/* pattern
        assertThat(patterns.any { it.contains("categories") }).isTrue()
    }

    @Test
    fun `query parameter values detected for BasicProvider`() {
        val uris = urisForAuthority("$PKG.basic")
        val allParams = uris.flatMap { it.queryParameters }.toSet()
        assertThat(allParams).containsAtLeast("filter", "sort_by")

        // Check that values are detected
        val allValues = uris.flatMap { it.queryParameterValues.entries }
            .associate { it.key to it.value }
        val filterValues = allValues["filter"] ?: emptyList()
        assertThat(filterValues).containsAtLeast("active", "archived")
    }

    @Test
    fun `dispatch scoping separates params by match code`() {
        val uris = urisForAuthority("$PKG.dispatch")
        val messageUris = uris.filter { it.uriPattern.contains("messages") && !it.uriPattern.contains("#") }
        val threadUris = uris.filter { it.uriPattern.contains("threads") }

        // Messages should have sender, limit
        val messageParams = messageUris.flatMap { it.queryParameters }.toSet()
        assertThat(messageParams).containsAtLeast("sender", "limit")

        // Threads should have thread_id, page
        val threadParams = threadUris.flatMap { it.queryParameters }.toSet()
        assertThat(threadParams).containsAtLeast("thread_id", "page")
    }

    @Test
    fun `boolean query parameters detected`() {
        val params = queryParamsForAuthority("$PKG.params")
        assertThat(params).containsAtLeast("verbose", "include_deleted")
    }

    @Test
    fun `plural getQueryParameters detected`() {
        val params = queryParamsForAuthority("$PKG.params")
        assertThat(params).contains("tags")
    }

    @Test
    fun `static CONTENT_URI fields detected`() {
        assertThat(analysis.rawContentUriStrings).containsAtLeast(
            "content://$PKG.static/data",
            "content://$PKG.static/users"
        )
    }

    @Test
    fun `deep link URI strings collected`() {
        assertThat(analysis.deepLinkUriStrings).containsAtLeast(
            "myapp://deeplink/home",
            "customscheme://action/open"
        )
    }

    // ==================== UriPatternExtractor — NEGATIVE ====================

    @Test
    fun `dispatch messages params NOT on threads URI`() {
        val threadUris = urisForAuthority("$PKG.dispatch")
            .filter { it.uriPattern.contains("threads") }
        val threadParams = threadUris.flatMap { it.queryParameters }.toSet()
        assertThat(threadParams).containsNoneOf("sender", "limit")
    }

    @Test
    fun `dispatch threads params NOT on messages URI`() {
        val messageUris = urisForAuthority("$PKG.dispatch")
            .filter { it.uriPattern.contains("messages") && !it.uriPattern.contains("#") }
        val messageParams = messageUris.flatMap { it.queryParameters }.toSet()
        assertThat(messageParams).containsNoneOf("thread_id", "page")
    }

    @Test
    fun `BasicProvider params NOT on ParamProvider`() {
        val params = queryParamsForAuthority("$PKG.params")
        assertThat(params).containsNoneOf("filter", "sort_by")
    }

    @Test
    fun `ParamProvider params NOT on BasicProvider`() {
        val params = queryParamsForAuthority("$PKG.basic")
        assertThat(params).containsNoneOf("verbose", "include_deleted", "tags")
    }

    // ==================== IntentExtraExtractor — POSITIVE ====================

    @Test
    fun `all intent extra types detected from DirectExtraActivity`() {
        val extras = extrasFor("DirectExtraActivity")
        val keys = extras.map { it.extraKey }.toSet()
        assertThat(keys).containsAtLeast(
            "user_name", "age", "balance", "is_active", "rating",
            "precise_value", "flags", "priority", "initial",
            "names", "ids", "tag_list", "id_list",
            "data", "items", "parcel_list", "serial_data", "extra_bundle"
        )

        // Check types
        fun assertType(key: String, type: String) {
            val extra = extras.find { it.extraKey == key }
            assertThat(extra).isNotNull()
            assertThat(extra!!.extraType).isEqualTo(type)
        }
        assertType("user_name", "String")
        assertType("age", "Int")
        assertType("balance", "Long")
        assertType("is_active", "Boolean")
        assertType("rating", "Float")
        assertType("precise_value", "Double")
        assertType("names", "String[]")
        assertType("extra_bundle", "Bundle")
    }

    @Test
    fun `inheritance resolves base class extras to InheritedChildActivity`() {
        val extras = extrasFor("InheritedChildActivity")
        val keys = extras.map { it.extraKey }.toSet()
        // Own extras
        assertThat(keys).containsAtLeast("target_screen", "refresh_interval")
        // Inherited from BaseExtraActivity
        assertThat(keys).containsAtLeast("session_token", "debug_mode")
    }

    @Test
    fun `forward value scanning detects string equals for mode`() {
        val extras = extrasFor("ValueScanActivity")
        val mode = extras.find { it.extraKey == "mode" }
        assertThat(mode).isNotNull()
        assertThat(mode!!.possibleValues).containsAtLeast("light", "dark", "system")
    }

    @Test
    fun `forward value scanning detects int switch for level`() {
        val extras = extrasFor("ValueScanActivity")
        val level = extras.find { it.extraKey == "level" }
        assertThat(level).isNotNull()
        assertThat(level!!.possibleValues).containsAtLeast("1", "2", "3", "4")
    }

    @Test
    fun `parseInt chain resolves string to int values`() {
        val extras = extrasFor("ValueScanActivity")
        val priority = extras.find { it.extraKey == "priority_code" }
        assertThat(priority).isNotNull()
        assertThat(priority!!.possibleValues).containsAtLeast("10", "20", "30")
    }

    @Test
    fun `bundle extras detected`() {
        val extras = extrasFor("BundleExtraActivity")
        val keys = extras.map { it.extraKey }.toSet()
        assertThat(keys).containsAtLeast("theme", "item_count", "auto_refresh")
    }

    @Test
    fun `inter-procedural string value resolution`() {
        val extras = extrasFor("InterProcActivity")
        val navAction = extras.find { it.extraKey == "nav_action" }
        assertThat(navAction).isNotNull()
        assertThat(navAction!!.possibleValues).containsAtLeast("home", "settings", "profile")
    }

    // ==================== IntentExtraExtractor — NEGATIVE (per-activity isolation) ====================

    @Test
    fun `DirectExtra does NOT have inherited extras`() {
        val keys = extrasFor("DirectExtraActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("session_token", "debug_mode", "target_screen")
    }

    @Test
    fun `InheritedChild does NOT have DirectExtra extras`() {
        val keys = extrasFor("InheritedChildActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("user_name", "age", "rating", "is_active")
    }

    @Test
    fun `ValueScan does NOT have other activity extras`() {
        val keys = extrasFor("ValueScanActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("user_name", "theme", "nav_action")
    }

    @Test
    fun `BundleExtra does NOT have ValueScan extras`() {
        val keys = extrasFor("BundleExtraActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("mode", "level", "priority_code")
    }

    @Test
    fun `InterProc does NOT have BundleExtra extras`() {
        val keys = extrasFor("InterProcActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("theme", "item_count", "auto_refresh")
    }

    @Test
    fun `DeepLink does NOT have InterProc extras`() {
        val keys = extrasFor("DeepLinkActivity").map { it.extraKey }.toSet()
        assertThat(keys).containsNoneOf("nav_action", "mode")
    }

    // ==================== FileProviderExtractor ====================

    @Test
    fun `FileProvider XML path types parsed`() {
        val paths = analysis.fileProviderPaths.filter { it.authority == "$PKG.fileprovider" }
        val types = paths.map { it.pathType }.toSet()
        assertThat(types).containsAtLeast("files-path", "cache-path", "external-path", "root-path")
    }

    @Test
    fun `FileProvider getUriForFile code references detected`() {
        val codeRefs = analysis.fileProviderPaths.filter {
            it.authority == "$PKG.fileprovider" && it.filePath != null
        }
        val filePaths = codeRefs.mapNotNull { it.filePath }
        assertThat(filePaths.any { it.contains("report.pdf") }).isTrue()
    }

    // ==================== ContentProviderCallExtractor ====================

    @Test
    fun `ContentResolver call methods detected`() {
        val methods = analysis.contentProviderCalls.map { it.methodName }.toSet()
        assertThat(methods).containsAtLeast("backup", "clear_cache", "sync")
    }

    @Test
    fun `call authority resolved from URI`() {
        val backup = analysis.contentProviderCalls.find { it.methodName == "backup" }
        assertThat(backup).isNotNull()
        assertThat(backup!!.authority).contains("$PKG.basic")
    }

    @Test
    fun `call with string authority`() {
        val sync = analysis.contentProviderCalls.find { it.methodName == "sync" }
        assertThat(sync).isNotNull()
        assertThat(sync!!.authority).contains("$PKG.dispatch")
    }

    // ==================== StringConstantCollector ====================

    @Test
    fun `sensitive string categories detected`() {
        val categories = analysis.sensitiveStrings.map { it.category }.toSet()
        assertThat(categories).containsAtLeast("Google API Key", "GitHub Token")
    }

    @Test
    fun `sensitive string values found`() {
        val values = analysis.sensitiveStrings.map { it.value }.toSet()
        assertThat(values).contains("AKIAIOSFODNN7EXAMPLE")
        assertThat(values).contains("AIzaSyA-fake-key-for-testing-only-xxxxx")
    }

    @Test
    fun `raw content URI strings collected`() {
        assertThat(analysis.rawContentUriStrings).containsAtLeast(
            "content://$PKG.raw/documents",
            "content://$PKG.raw/contacts"
        )
    }

    @Test
    fun `URL strings collected`() {
        assertThat(analysis.allUrlStrings).containsAtLeast(
            "https://hooks.example.com/webhook",
            "https://docs.example.com/api"
        )
    }

    // ==================== UrlExtractor — POSITIVE ====================

    @Test
    fun `Retrofit GET endpoint with path and query and header params`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val getUser = endpoints.find { it.httpMethod == "GET" && it.path.contains("users/{id}") }
        assertThat(getUser).isNotNull()
        assertThat(getUser!!.queryParams).contains("fields")
        assertThat(getUser.pathParams).contains("id")
        assertThat(getUser.headerParams).contains("Authorization")
    }

    @Test
    fun `Retrofit POST with body flag`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val createUser = endpoints.find { it.httpMethod == "POST" && it.path == "users" }
        assertThat(createUser).isNotNull()
        assertThat(createUser!!.hasBody).isTrue()
    }

    @Test
    fun `Retrofit DELETE endpoint detected`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val deleteUser = endpoints.find { it.httpMethod == "DELETE" && it.path.contains("users") }
        assertThat(deleteUser).isNotNull()
    }

    @Test
    fun `Retrofit search with multiple query params`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val search = endpoints.find { it.path == "search" && it.httpMethod == "GET" }
        assertThat(search).isNotNull()
        assertThat(search!!.queryParams).containsAtLeast("q", "page", "limit")
    }

    @Test
    fun `Retrofit base URL resolved`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val withBaseUrl = endpoints.filter { it.baseUrl.isNotEmpty() }
        assertThat(withBaseUrl).isNotEmpty()
        assertThat(withBaseUrl.any { it.baseUrl.contains("api.example.com") || it.fullUrl.contains("api.example.com") }).isTrue()
    }

    @Test
    fun `OkHttp URLs detected`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val urls = okhttp.map { it.fullUrl }
        assertThat(urls).containsAtLeast(
            "https://api.example.com/data/export",
            "https://cdn.example.com/assets/image.png"
        )
    }

    @Test
    fun `StringBuilder URL concatenation detected`() {
        val concat = analysis.apiEndpoints.filter { it.sourceType == "concatenation" }
        val urls = concat.map { it.fullUrl }
        assertThat(urls.any { it.contains("events.example.com") && it.contains("track") }).isTrue()
    }

    // ==================== Cross-extractor isolation — NEGATIVE ====================

    @Test
    fun `Retrofit query params NOT in content provider query params`() {
        val allProviderParams = analysis.contentProviderUris
            .flatMap { it.queryParameters }.toSet()
        // These are Retrofit @Query params, not content provider params
        assertThat(allProviderParams).containsNoneOf("fields", "q")
    }

    @Test
    fun `content provider authorities NOT in API endpoint base URLs`() {
        val apiBaseUrls = analysis.apiEndpoints.map { it.baseUrl }.toSet()
        assertThat(apiBaseUrls).containsNoneOf(
            "$PKG.basic",
            "$PKG.dispatch",
            "$PKG.params"
        )
    }

    @Test
    fun `FileProvider authority NOT in content provider URI results`() {
        val uriAuthorities = analysis.contentProviderUris.mapNotNull { it.authority }.toSet()
        assertThat(uriAuthorities).doesNotContain("$PKG.fileprovider")
    }
}
