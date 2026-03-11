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
                services = listOf(
                    ExportedComponent(
                        name = "$PKG.services.SyncService",
                        isExported = true,
                        permission = null,
                        intentFilters = listOf(IntentFilterInfo(
                            actions = listOf("$PKG.action.SYNC"),
                            categories = emptyList(),
                            dataSchemes = emptyList(),
                            dataAuthorities = emptyList(),
                            dataPaths = emptyList(),
                            mimeTypes = emptyList()
                        ))
                    )
                ),
                receivers = listOf(
                    ExportedComponent(
                        name = "$PKG.receivers.DataReceiver",
                        isExported = true,
                        permission = null,
                        intentFilters = listOf(IntentFilterInfo(
                            actions = listOf("$PKG.action.DATA"),
                            categories = emptyList(),
                            dataSchemes = emptyList(),
                            dataAuthorities = emptyList(),
                            dataPaths = emptyList(),
                            mimeTypes = emptyList()
                        ))
                    )
                ),
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

        // Helper: get extras for a specific activity component
        private fun extrasFor(component: String): List<IntentInfo> =
            analysis.intentExtras.filter {
                it.associatedComponent == "$PKG.activities.$component"
            }

        // Helper: get extras by full component name (for receivers/services)
        private fun extrasForComponent(fullName: String): List<IntentInfo> =
            analysis.intentExtras.filter { it.associatedComponent == fullName }

        // Helper: get URI results for a specific authority
        private fun urisForAuthority(authority: String): List<ContentProviderInfo> =
            analysis.contentProviderUris.filter { it.authority == authority }

        // Helper: get all query params across all URIs for an authority
        private fun queryParamsForAuthority(authority: String): Set<String> =
            urisForAuthority(authority).flatMap { it.queryParameters }.toSet()

        // Helper: get endpoints by source type
        private fun endpointsByType(type: String): List<ApiEndpoint> =
            analysis.apiEndpoints.filter { it.sourceType == type }
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

    // ==================== Bug fix verifications ====================

    @Test
    fun `PutExtra type and values correctly resolved`() {
        val extras = extrasFor("PutExtraActivity")
        val actionType = extras.find { it.extraKey == "action_type" }
        assertThat(actionType).isNotNull()
        assertThat(actionType!!.extraType).isEqualTo("String")
        assertThat(actionType.possibleValues).contains("navigate")

        val count = extras.find { it.extraKey == "count" }
        assertThat(count).isNotNull()
        assertThat(count!!.extraType).isEqualTo("Int")
        assertThat(count.possibleValues).contains("42")

        val enabled = extras.find { it.extraKey == "enabled" }
        assertThat(enabled).isNotNull()
        assertThat(enabled!!.extraType).isEqualTo("Boolean")
        assertThat(enabled.possibleValues).contains("true")
    }

    @Test
    fun `ContentResolver call arg extracted`() {
        val clearCache = analysis.contentProviderCalls.find { it.methodName == "clear_cache" }
        assertThat(clearCache).isNotNull()
        assertThat(clearCache!!.arg).isEqualTo("all")

        val backup = analysis.contentProviderCalls.find { it.methodName == "backup" }
        assertThat(backup).isNotNull()
        assertThat(backup!!.arg).isNull()
    }

    @Test
    fun `deep link activity query params detected via orphan propagation`() {
        val deepLinkUris = analysis.contentProviderUris.filter {
            it.uriPattern.contains("testapp://open")
        }
        assertThat(deepLinkUris).isNotEmpty()
        val params = deepLinkUris.flatMap { it.queryParameters }.toSet()
        assertThat(params).containsAtLeast("screen", "id")
    }

    @Test
    fun `BasicProvider sort_by has all three values`() {
        val uris = urisForAuthority("$PKG.basic")
        val itemsUri = uris.find { it.uriPattern.contains("/items") && !it.uriPattern.contains("#") }
        assertThat(itemsUri).isNotNull()
        val sortByValues = itemsUri!!.queryParameterValues["sort_by"] ?: emptyList()
        assertThat(sortByValues).containsExactly("date", "id", "name")
    }

    @Test
    fun `BulkParamReader detects params via getQueryParameterNames and Map get`() {
        val bulkUris = analysis.contentProviderUris.filter {
            it.uriPattern.contains("myapp://profile")
        }
        assertThat(bulkUris).isNotEmpty()
        val params = bulkUris.flatMap { it.queryParameters }.toSet()
        assertThat(params).containsAtLeast("user_id", "action", "referrer")
    }

    // ==================== Strict value completeness ====================

    @Test
    fun `BasicProvider filter values exactly match`() {
        val uris = urisForAuthority("$PKG.basic")
        val itemsUri = uris.find { it.uriPattern.contains("/items") && !it.uriPattern.contains("#") }
        assertThat(itemsUri).isNotNull()
        val filterValues = itemsUri!!.queryParameterValues["filter"] ?: emptyList()
        assertThat(filterValues).containsExactly("active", "archived")
    }

    @Test
    fun `ValueScan mode values exactly match`() {
        val mode = extrasFor("ValueScanActivity").find { it.extraKey == "mode" }
        assertThat(mode).isNotNull()
        assertThat(mode!!.possibleValues).containsExactly("dark", "light", "system")
    }

    @Test
    fun `ValueScan level values exactly match`() {
        val level = extrasFor("ValueScanActivity").find { it.extraKey == "level" }
        assertThat(level).isNotNull()
        assertThat(level!!.possibleValues).containsExactly("1", "2", "3", "4")
    }

    @Test
    fun `InterProc nav_action values exactly match`() {
        val navAction = extrasFor("InterProcActivity").find { it.extraKey == "nav_action" }
        assertThat(navAction).isNotNull()
        assertThat(navAction!!.possibleValues).containsExactly("home", "profile", "settings")
    }

    @Test
    fun `Dispatch messages params exactly match`() {
        val messageUris = urisForAuthority("$PKG.dispatch")
            .filter { it.uriPattern.contains("messages") && !it.uriPattern.contains("#") }
        val params = messageUris.flatMap { it.queryParameters }.toSet()
        assertThat(params).containsExactly("limit", "sender")
    }

    @Test
    fun `Dispatch threads params exactly match`() {
        val threadUris = urisForAuthority("$PKG.dispatch")
            .filter { it.uriPattern.contains("threads") }
        val params = threadUris.flatMap { it.queryParameters }.toSet()
        assertThat(params).containsExactly("page", "thread_id")
    }

    // ==================== Retrofit @Headers — POSITIVE ====================

    @Test
    fun `Retrofit @Headers annotation detects static headers`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val profile = endpoints.find { it.path.contains("profile") }
        assertThat(profile).isNotNull()
        assertThat(profile!!.headerParams).containsAtLeast("Accept", "X-Version")
    }

    // ==================== Retrofit @Headers — NEGATIVE ====================

    @Test
    fun `@Headers NOT propagated to other Retrofit methods`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val search = endpoints.find { it.path == "search" }
        assertThat(search).isNotNull()
        assertThat(search!!.headerParams).containsNoneOf("Accept", "X-Version")
    }

    @Test
    fun `@Headers NOT on POST users endpoint`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val post = endpoints.find { it.httpMethod == "POST" && it.path == "users" }
        assertThat(post).isNotNull()
        assertThat(post!!.headerParams).isEmpty()
    }

    // ==================== OkHttp headers — POSITIVE ====================

    @Test
    fun `OkHttp addHeader detected on export endpoint`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val export = okhttp.find { it.fullUrl.contains("data/export") }
        assertThat(export).isNotNull()
        assertThat(export!!.headerParams).containsAtLeast("X-Api-Key", "Accept")
    }

    @Test
    fun `OkHttp ApiKeyClient endpoint detected with header`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val geocode = okhttp.find { it.fullUrl.contains("maps.example.com") }
        assertThat(geocode).isNotNull()
        assertThat(geocode!!.headerParams).contains("X-Api-Key")
    }

    // ==================== OkHttp headers — NEGATIVE ====================

    @Test
    fun `OkHttp headers NOT on image URL endpoint`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val image = okhttp.find { it.fullUrl.contains("cdn.example.com/assets") }
        assertThat(image).isNotNull()
        assertThat(image!!.headerParams).isEmpty()
    }

    // ==================== Multiple Retrofit base URLs — POSITIVE ====================

    @Test
    fun `CdnService endpoints use cdn base URL`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val cdnEndpoints = endpoints.filter { it.baseUrl.contains("cdn.example.com") }
        assertThat(cdnEndpoints).isNotEmpty()
        val paths = cdnEndpoints.map { it.path }
        assertThat(paths).containsAtLeast("images/{id}", "videos/{id}/stream")
    }

    @Test
    fun `CdnService getImage has width and height query params`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val getImage = endpoints.find { it.path == "images/{id}" }
        assertThat(getImage).isNotNull()
        assertThat(getImage!!.queryParams).containsAtLeast("width", "height")
    }

    @Test
    fun `CdnService streamVideo has Range header`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val stream = endpoints.find { it.path.contains("stream") }
        assertThat(stream).isNotNull()
        assertThat(stream!!.headerParams).contains("Range")
    }

    // ==================== Multiple Retrofit base URLs — NEGATIVE ====================

    @Test
    fun `ApiService endpoints do NOT use cdn base URL`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val apiEndpoints = endpoints.filter { it.path == "search" || it.path == "users" }
        for (ep in apiEndpoints) {
            assertThat(ep.baseUrl).doesNotContain("cdn.example.com")
        }
    }

    @Test
    fun `CdnService endpoints do NOT use api base URL`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val cdnEndpoints = endpoints.filter { it.path.contains("images") || it.path.contains("videos") }
        for (ep in cdnEndpoints) {
            assertThat(ep.baseUrl).doesNotContain("api.example.com")
        }
    }

    @Test
    fun `CdnService query params NOT on ApiService endpoints`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val search = endpoints.find { it.path == "search" }
        assertThat(search).isNotNull()
        assertThat(search!!.queryParams).containsNoneOf("width", "height")
    }

    // ==================== API key association — NEGATIVE ====================

    @Test
    fun `Google API Key NOT associated with endpoints in different classes`() {
        val googleKey = analysis.sensitiveStrings.find { it.category == "Google API Key" }
        assertThat(googleKey).isNotNull()
        // The key is defined in FakeSecrets, not in OkHttpCaller or RetrofitClient
        // It should NOT be associated with api.example.com endpoints
        val urls = googleKey!!.associatedUrls
        assertThat(urls.none { it.contains("api.example.com/v1/") }).isTrue()
    }

    @Test
    fun `AWS Key NOT associated with non-FakeSecrets endpoints`() {
        val awsKey = analysis.sensitiveStrings.find { it.category == "AWS Key" }
        assertThat(awsKey).isNotNull()
        // FakeSecrets also contains the Firebase URL literal, so that gets associated
        // But it should NOT be associated with api.example.com endpoints (different class)
        val urls = awsKey!!.associatedUrls
        assertThat(urls.none { it.contains("api.example.com") }).isTrue()
        assertThat(urls.none { it.contains("maps.example.com") }).isTrue()
    }

    // ==================== Deep link / BulkParam cross-extractor isolation ====================

    @Test
    fun `deep link params NOT in content provider params`() {
        val providerParams = analysis.contentProviderUris
            .filter { it.uriPattern.startsWith("content://") }
            .flatMap { it.queryParameters }.toSet()
        assertThat(providerParams).containsNoneOf("screen", "id")
    }

    @Test
    fun `BulkParamReader params NOT on DispatchProvider URIs`() {
        val dispatchParams = urisForAuthority("$PKG.dispatch")
            .flatMap { it.queryParameters }.toSet()
        assertThat(dispatchParams).containsNoneOf("user_id", "action", "referrer")
    }

    @Test
    fun `content provider params NOT appearing as deep link params`() {
        val deepLinkParams = analysis.contentProviderUris
            .filter { !it.uriPattern.startsWith("content://") }
            .flatMap { it.queryParameters }.toSet()
        assertThat(deepLinkParams).containsNoneOf("filter", "sort_by", "sender", "limit")
    }

    // ==================== PUT endpoint (Retrofit) ====================

    @Test
    fun `Retrofit PUT endpoint detected with path and body`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val put = endpoints.find { it.httpMethod == "PUT" }
        assertThat(put).isNotNull()
        assertThat(put!!.pathParams).contains("id")
        assertThat(put.hasBody).isTrue()
    }

    @Test
    fun `Retrofit PATCH endpoint detected`() {
        val endpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val patch = endpoints.find { it.httpMethod == "PATCH" }
        assertThat(patch).isNotNull()
        assertThat(patch!!.path).contains("settings")
    }

    // ==================== Group A: associatedAction ====================

    @Test
    fun `PutExtra extras have associatedAction from manifest`() {
        val extras = extrasFor("PutExtraActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.PUT") }
    }

    @Test
    fun `DirectExtra extras have associatedAction`() {
        val extras = extrasFor("DirectExtraActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.DIRECT") }
    }

    @Test
    fun `ValueScan extras have associatedAction`() {
        val extras = extrasFor("ValueScanActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.VALUES") }
    }

    @Test
    fun `InterProc extras have associatedAction`() {
        val extras = extrasFor("InterProcActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.INTERPROC") }
    }

    @Test
    fun `BundleExtra extras have associatedAction`() {
        val extras = extrasFor("BundleExtraActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.BUNDLE") }
    }

    // ==================== Group B: BroadcastReceiver / Service extras ====================

    @Test
    fun `DataReceiver extras detected with correct types`() {
        val extras = extrasForComponent("$PKG.receivers.DataReceiver")
        val keys = extras.map { it.extraKey }.toSet()
        assertThat(keys).containsAtLeast("source", "priority")
        assertThat(extras.find { it.extraKey == "source" }!!.extraType).isEqualTo("String")
        assertThat(extras.find { it.extraKey == "priority" }!!.extraType).isEqualTo("Int")
    }

    @Test
    fun `DataReceiver extras have correct associatedComponent`() {
        val extras = extrasForComponent("$PKG.receivers.DataReceiver")
        extras.forEach {
            assertThat(it.associatedComponent).isEqualTo("$PKG.receivers.DataReceiver")
        }
    }

    @Test
    fun `DataReceiver extras have associatedAction`() {
        val extras = extrasForComponent("$PKG.receivers.DataReceiver")
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.DATA") }
    }

    @Test
    fun `SyncService extras detected with correct types`() {
        val extras = extrasForComponent("$PKG.services.SyncService")
        val keys = extras.map { it.extraKey }.toSet()
        assertThat(keys).containsAtLeast("sync_type", "force")
        assertThat(extras.find { it.extraKey == "sync_type" }!!.extraType).isEqualTo("String")
        assertThat(extras.find { it.extraKey == "force" }!!.extraType).isEqualTo("Boolean")
    }

    @Test
    fun `SyncService extras have correct associatedComponent`() {
        val extras = extrasForComponent("$PKG.services.SyncService")
        extras.forEach {
            assertThat(it.associatedComponent).isEqualTo("$PKG.services.SyncService")
        }
    }

    @Test
    fun `SyncService extras have associatedAction`() {
        val extras = extrasForComponent("$PKG.services.SyncService")
        extras.forEach { assertThat(it.associatedAction).isEqualTo("$PKG.action.SYNC") }
    }

    @Test
    fun `DataReceiver extras NOT on SyncService`() {
        val syncExtras = extrasForComponent("$PKG.services.SyncService")
            .map { it.extraKey }.toSet()
        assertThat(syncExtras).containsNoneOf("source", "priority")
    }

    @Test
    fun `SyncService extras NOT on DataReceiver`() {
        val receiverExtras = extrasForComponent("$PKG.receivers.DataReceiver")
            .map { it.extraKey }.toSet()
        assertThat(receiverExtras).containsNoneOf("sync_type", "force")
    }

    // ==================== Group C: DeepLinkHandler ref param ====================

    @Test
    fun `DeepLinkHandler ref param linked to deeplink URI`() {
        val deepLinkUris = analysis.contentProviderUris.filter {
            it.uriPattern == "myapp://deeplink/home"
        }
        assertThat(deepLinkUris).isNotEmpty()
        assertThat(deepLinkUris.first().queryParameters).contains("ref")
    }

    @Test
    fun `ref param NOT on testapp open URI`() {
        val openUris = analysis.contentProviderUris.filter {
            it.uriPattern == "testapp://open"
        }
        assertThat(openUris).isNotEmpty()
        val openParams = openUris.flatMap { it.queryParameters }.toSet()
        assertThat(openParams).doesNotContain("ref")
    }

    @Test
    fun `customscheme action open in deepLinkUriStrings`() {
        assertThat(analysis.deepLinkUriStrings).contains("customscheme://action/open")
    }

    // ==================== Group D: Activity-alias ====================

    @Test
    fun `customscheme action open detected as content provider URI`() {
        val uris = analysis.contentProviderUris.filter {
            it.uriPattern == "customscheme://action/open"
        }
        assertThat(uris).isNotEmpty()
        assertThat(uris.first().sourceClass).contains("DeepLinkHandler")
    }

    @Test
    fun `myapp deeplink home detected as content provider URI`() {
        val uris = analysis.contentProviderUris.filter {
            it.uriPattern == "myapp://deeplink/home"
        }
        assertThat(uris).isNotEmpty()
        assertThat(uris.first().sourceClass).contains("DeepLinkHandler")
    }

    @Test
    fun `deep link URIs correctly paired by gated CFG`() {
        // myapp://deeplink/home should exist but myapp://deeplink/open should NOT
        val patterns = analysis.contentProviderUris.map { it.uriPattern }.toSet()
        assertThat(patterns).contains("myapp://deeplink/home")
        assertThat(patterns).contains("customscheme://action/open")
        assertThat(patterns).doesNotContain("myapp://deeplink/open")
        assertThat(patterns).doesNotContain("myapp://action/open")
        assertThat(patterns).doesNotContain("customscheme://deeplink/home")
    }

    // ==================== Group E: ContentUris.withAppendedId ====================

    @Test
    fun `withAppendedId URI detected for basic items`() {
        val basicUris = urisForAuthority("$PKG.basic")
        val patterns = basicUris.map { it.uriPattern }
        // AppendIdCaller creates content://...basic/items via ContentUris.withAppendedId
        // The items URI should exist (either from UriMatcher or from AppendIdCaller)
        assertThat(patterns.any { it.contains("items") }).isTrue()
    }

    @Test
    fun `withAppendedId base URI in raw content URIs`() {
        assertThat(analysis.rawContentUriStrings).contains("content://$PKG.basic/items")
    }

    // ==================== Group F: getBooleanQueryParameter defaults ====================

    @Test
    fun `ParamProvider verbose default is false`() {
        val paramUris = urisForAuthority("$PKG.params")
        val defaults = paramUris.flatMap { it.queryParameterDefaults.entries }
            .associate { it.key to it.value }
        assertThat(defaults["verbose"]).isEqualTo("false")
    }

    @Test
    fun `ParamProvider include_deleted default is true`() {
        val paramUris = urisForAuthority("$PKG.params")
        val defaults = paramUris.flatMap { it.queryParameterDefaults.entries }
            .associate { it.key to it.value }
        assertThat(defaults["include_deleted"]).isEqualTo("true")
    }

    @Test
    fun `BasicProvider has no query parameter defaults`() {
        val basicUris = urisForAuthority("$PKG.basic")
        val allDefaults = basicUris.flatMap { it.queryParameterDefaults.entries }
        assertThat(allDefaults).isEmpty()
    }

    // ==================== Group G: Positive API key association ====================

    @Test
    fun `FakeSecrets sensitive strings have Firebase URL in associatedUrls`() {
        val secrets = analysis.sensitiveStrings.filter {
            it.sourceClass.contains("FakeSecrets") && it.category != "Firebase URL"
        }
        assertThat(secrets).isNotEmpty()
        secrets.forEach { secret ->
            assertThat(secret.associatedUrls).contains("https://my-test-project.firebaseio.com")
        }
    }

    @Test
    fun `FakeSecrets sensitive strings NOT associated with api example com`() {
        val secrets = analysis.sensitiveStrings.filter {
            it.sourceClass.contains("FakeSecrets")
        }
        secrets.forEach { secret ->
            secret.associatedUrls.forEach { url ->
                assertThat(url).doesNotContain("api.example.com")
            }
        }
    }

    // ==================== Group H: Endpoint dedup ====================

    @Test
    fun `same URL from Retrofit and literal dedups to Retrofit`() {
        // "https://api.example.com/v1/users" exists as both Retrofit POST and literal
        val usersEndpoints = analysis.apiEndpoints.filter {
            it.fullUrl == "https://api.example.com/v1/users"
        }
        assertThat(usersEndpoints.any { it.sourceType == "retrofit" }).isTrue()
    }

    @Test
    fun `deduped Retrofit users endpoint retains httpMethod POST`() {
        val postUsers = analysis.apiEndpoints.find {
            it.fullUrl == "https://api.example.com/v1/users" && it.sourceType == "retrofit"
        }
        assertThat(postUsers).isNotNull()
        assertThat(postUsers!!.httpMethod).isEqualTo("POST")
    }

    @Test
    fun `literal-only URLs still appear`() {
        val hooks = analysis.apiEndpoints.find {
            it.fullUrl == "https://hooks.example.com/webhook"
        }
        assertThat(hooks).isNotNull()
        assertThat(hooks!!.sourceType).isEqualTo("literal")
    }

    // ==================== Group I: Retrofit @Field ====================

    @Test
    fun `Field params detected as query params on login endpoint`() {
        val login = analysis.apiEndpoints.find {
            it.path == "auth/login" && it.sourceType == "retrofit"
        }
        assertThat(login).isNotNull()
        assertThat(login!!.queryParams).containsAtLeast("username", "password")
    }

    @Test
    fun `login endpoint has POST method and correct base URL`() {
        val login = analysis.apiEndpoints.find {
            it.path == "auth/login" && it.sourceType == "retrofit"
        }
        assertThat(login).isNotNull()
        assertThat(login!!.httpMethod).isEqualTo("POST")
        assertThat(login.baseUrl).isEqualTo("https://api.example.com/v1/")
    }

    @Test
    fun `Field params NOT on GET search endpoint`() {
        val search = analysis.apiEndpoints.find {
            it.path == "search" && it.sourceType == "retrofit"
        }
        assertThat(search).isNotNull()
        assertThat(search!!.queryParams).containsNoneOf("username", "password")
    }

    // ==================== Group J: HttpUrl.parse / toHttpUrl ====================

    @Test
    fun `HttpUrl toHttpUrl URL detected as OkHttp endpoint`() {
        val configEndpoint = analysis.apiEndpoints.find {
            it.fullUrl == "https://static.example.com/v2/config.json"
        }
        assertThat(configEndpoint).isNotNull()
        assertThat(configEndpoint!!.sourceType).isEqualTo("okhttp")
    }

    @Test
    fun `HttpUrl endpoint not duplicated`() {
        val configEndpoints = analysis.apiEndpoints.filter {
            it.fullUrl == "https://static.example.com/v2/config.json"
        }
        assertThat(configEndpoints).hasSize(1)
    }

    // ==================== Group K: File constructor ====================

    @Test
    fun `File String String constructor resolves child path`() {
        val paths = analysis.fileProviderPaths.filter { it.pathType == "code-reference" }
        val fileNames = paths.map { it.filePath }
        assertThat(fileNames).contains("app.log")
    }

    @Test
    fun `File paths from different constructors all detected`() {
        val codeRefs = analysis.fileProviderPaths.filter { it.pathType == "code-reference" }
        val fileNames = codeRefs.map { it.filePath }
        assertThat(fileNames).containsAtLeast("report.pdf", "data.csv", "app.log")
    }

    // ==================== Group L: Cross-extractor isolation ====================

    @Test
    fun `receiver extras NOT on any activity component`() {
        val activityExtras = analysis.intentExtras.filter {
            it.associatedComponent?.contains(".activities.") == true
        }.map { it.extraKey }.toSet()
        assertThat(activityExtras).doesNotContain("source")
    }

    @Test
    fun `service extras NOT on any activity component`() {
        val activityExtras = analysis.intentExtras.filter {
            it.associatedComponent?.contains(".activities.") == true
        }.map { it.extraKey }.toSet()
        assertThat(activityExtras).doesNotContain("sync_type")
    }

    @Test
    fun `activity extras NOT on receiver or service components`() {
        val nonActivityExtras = analysis.intentExtras.filter {
            it.associatedComponent?.contains(".receivers.") == true ||
                it.associatedComponent?.contains(".services.") == true
        }.map { it.extraKey }.toSet()
        assertThat(nonActivityExtras).containsNoneOf("user_name", "mode", "theme", "nav_action")
    }

    @Test
    fun `deep link ref param NOT in API endpoint query params`() {
        val apiParams = analysis.apiEndpoints.flatMap { it.queryParams }.toSet()
        assertThat(apiParams).doesNotContain("ref")
    }
}
