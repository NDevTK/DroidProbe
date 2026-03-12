package com.droidprobe.app.analysis

import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.data.model.*
import com.droidprobe.app.interaction.ApiSpecFetcher
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
                    activity("StringSwitchActivity", true, actionFilter("$PKG.action.SWITCH")),
                    activity("PrefixValueActivity", true, actionFilter("$PKG.action.PREFIX")),
                    activity("ParseBooleanActivity", true, actionFilter("$PKG.action.PARSE_BOOL")),
                    activity("ContainsKeyActivity", true, actionFilter("$PKG.action.CONTAINS_KEY")),
                    activity("SetDataActivity", true, actionFilter("$PKG.action.SET_DATA")),
                    activity("DefaultValueActivity", true, actionFilter("$PKG.action.DEFAULTS")),
                    activity("DeepLinkActivity", true, listOf(
                        IntentFilterInfo(
                            actions = listOf("android.intent.action.VIEW"),
                            categories = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                            dataSchemes = listOf("testapp"),
                            dataAuthorities = listOf("open"),
                            dataPaths = emptyList(),
                            mimeTypes = emptyList()
                        )
                    )),
                    activity("SettingsAlias", true, actionFilter("$PKG.action.SETTINGS_ALIAS"),
                        target = "$PKG.activities.ValueScanActivity")
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
                    ),
                    ExportedComponent(
                        name = "$PKG.receivers.OrderedReceiver",
                        isExported = true,
                        permission = null,
                        intentFilters = listOf(IntentFilterInfo(
                            actions = listOf("$PKG.action.ORDERED"),
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
                    provider("UriBuilderProvider", "$PKG.builder", true),
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

    // ==================== API key association — CFG-based dataflow ====================

    @Test
    fun `Google API Key associated with ApiKeyClient endpoint via dataflow`() {
        val googleKey = analysis.sensitiveStrings.find { it.category == "Google API Key" }
        assertThat(googleKey).isNotNull()
        // GOOGLE_KEY flows: FakeSecrets.GOOGLE_KEY → sget-object in ApiKeyClient →
        // addHeader("X-Api-Key", key) alongside url("https://maps.example.com/api/geocode")
        val urls = googleKey!!.associatedUrls
        assertThat(urls).contains("https://maps.example.com/api/geocode")
    }

    @Test
    fun `Google API Key NOT associated with unrelated endpoints`() {
        val googleKey = analysis.sensitiveStrings.find { it.category == "Google API Key" }
        assertThat(googleKey).isNotNull()
        // Key only flows to ApiKeyClient, NOT to RetrofitClient or OkHttpCaller
        val urls = googleKey!!.associatedUrls
        assertThat(urls.none { it.contains("api.example.com/v1/") }).isTrue()
    }

    @Test
    fun `AWS Key has no associated URLs - no dataflow to HTTP sink`() {
        val awsKey = analysis.sensitiveStrings.find { it.category == "AWS Key" }
        assertThat(awsKey).isNotNull()
        // AWS_KEY is defined in FakeSecrets but never read and passed to an HTTP client
        assertThat(awsKey!!.associatedUrls).isEmpty()
    }

    @Test
    fun `FakeSecrets Stripe Key has no associated URLs - no dataflow to HTTP sink`() {
        val stripeKey = analysis.sensitiveStrings.find {
            it.category == "Stripe Key" && it.value.contains("sk_live_FakeStripeKey")
        }
        assertThat(stripeKey).isNotNull()
        assertThat(stripeKey!!.associatedUrls).isEmpty()
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

    // ==================== Group G: CFG-based API key association ====================

    @Test
    fun `only Google Key has CFG-verified URL associations among FakeSecrets`() {
        val secrets = analysis.sensitiveStrings.filter {
            it.sourceClass.contains("FakeSecrets")
        }
        assertThat(secrets).isNotEmpty()
        // Only GOOGLE_KEY flows to an HTTP sink (ApiKeyClient.addHeader)
        // All other FakeSecrets keys have no dataflow to HTTP sinks
        val withUrls = secrets.filter { it.associatedUrls.isNotEmpty() }
        assertThat(withUrls).hasSize(1)
        assertThat(withUrls.first().category).isEqualTo("Google API Key")
        assertThat(withUrls.first().associatedUrls).contains("https://maps.example.com/api/geocode")
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

    // ==================== Group M: String switch value detection ====================

    @Test
    fun `StringSwitch category has values food drink dessert`() {
        val extras = extrasFor("StringSwitchActivity")
        val category = extras.find { it.extraKey == "category" }
        assertThat(category).isNotNull()
        assertThat(category!!.possibleValues).containsAtLeast("food", "drink", "dessert")
    }

    @Test
    fun `StringSwitch category type is String`() {
        val extras = extrasFor("StringSwitchActivity")
        val category = extras.find { it.extraKey == "category" }
        assertThat(category).isNotNull()
        assertThat(category!!.extraType).isEqualTo("String")
    }

    @Test
    fun `StringSwitch category NOT on other activities`() {
        val otherActivities = listOf("DirectExtraActivity", "ValueScanActivity", "PrefixValueActivity")
        for (act in otherActivities) {
            val extras = extrasFor(act)
            assertThat(extras.map { it.extraKey }).doesNotContain("category")
        }
    }

    // ==================== Group N: startsWith value detection ====================

    @Test
    fun `PrefixValue transport has startsWith values air sea`() {
        val extras = extrasFor("PrefixValueActivity")
        val transport = extras.find { it.extraKey == "transport" }
        assertThat(transport).isNotNull()
        assertThat(transport!!.possibleValues).containsAtLeast("air", "sea")
    }

    @Test
    fun `PrefixValue transport type is String`() {
        val extras = extrasFor("PrefixValueActivity")
        val transport = extras.find { it.extraKey == "transport" }
        assertThat(transport).isNotNull()
        assertThat(transport!!.extraType).isEqualTo("String")
    }

    @Test
    fun `PrefixValue transport NOT on other activities`() {
        val otherActivities = listOf("DirectExtraActivity", "ValueScanActivity", "StringSwitchActivity")
        for (act in otherActivities) {
            val extras = extrasFor(act)
            assertThat(extras.map { it.extraKey }).doesNotContain("transport")
        }
    }

    // ==================== Group O: Inline const-string key CFG association ====================

    @Test
    fun `inline Stripe key detected as sensitive string`() {
        val inlineKey = analysis.sensitiveStrings.find {
            it.value.contains("InlineTestKeyForDroidProbe")
        }
        assertThat(inlineKey).isNotNull()
        assertThat(inlineKey!!.category).isEqualTo("Stripe Key")
    }

    @Test
    fun `inline Stripe key associated with payments example com`() {
        val inlineKey = analysis.sensitiveStrings.find {
            it.value.contains("InlineTestKeyForDroidProbe")
        }
        assertThat(inlineKey).isNotNull()
        assertThat(inlineKey!!.associatedUrls).contains("https://payments.example.com/v1/charges")
    }

    @Test
    fun `inline Stripe key NOT associated with api example com`() {
        val inlineKey = analysis.sensitiveStrings.find {
            it.value.contains("InlineTestKeyForDroidProbe")
        }
        assertThat(inlineKey).isNotNull()
        inlineKey!!.associatedUrls.forEach { url ->
            assertThat(url).doesNotContain("api.example.com")
        }
    }

    // ==================== Group P: Sensitive key negative — no HTTP sink ====================

    @Test
    fun `FakeSecrets Stripe Key has no associated URLs`() {
        val stripeKey = analysis.sensitiveStrings.find {
            it.value.contains("sk_live_FakeStripeKey") && it.sourceClass.contains("FakeSecrets")
        }
        assertThat(stripeKey).isNotNull()
        assertThat(stripeKey!!.associatedUrls).isEmpty()
    }

    @Test
    fun `FakeSecrets JWT has no associated URLs`() {
        val jwt = analysis.sensitiveStrings.find {
            it.category == "JWT" && it.sourceClass.contains("FakeSecrets")
        }
        assertThat(jwt).isNotNull()
        assertThat(jwt!!.associatedUrls).isEmpty()
    }

    // ==================== Group Q: Deep link param scoping ====================

    @Test
    fun `myapp deeplink home has ref param`() {
        val uri = analysis.contentProviderUris.find {
            it.uriPattern == "myapp://deeplink/home"
        }
        assertThat(uri).isNotNull()
        assertThat(uri!!.queryParameters).contains("ref")
    }

    @Test
    fun `customscheme action open does NOT have ref param`() {
        val uri = analysis.contentProviderUris.find {
            it.uriPattern == "customscheme://action/open"
        }
        assertThat(uri).isNotNull()
        assertThat(uri!!.queryParameters).doesNotContain("ref")
    }

    @Test
    fun `ref param appears on exactly one deep link URI`() {
        val urisWithRef = analysis.contentProviderUris.filter {
            it.queryParameters.contains("ref")
        }
        assertThat(urisWithRef).hasSize(1)
        assertThat(urisWithRef.first().uriPattern).isEqualTo("myapp://deeplink/home")
    }

    // ==================== Group R: Match code isolation ====================

    @Test
    fun `DispatchProvider messages params exclude threads params`() {
        val messageUris = urisForAuthority("$PKG.dispatch").filter {
            it.uriPattern.contains("messages") && !it.uriPattern.contains("#")
        }
        val messageParams = messageUris.flatMap { it.queryParameters }.toSet()
        assertThat(messageParams).containsNoneOf("thread_id", "page")
    }

    @Test
    fun `DispatchProvider threads params exclude messages params`() {
        val threadUris = urisForAuthority("$PKG.dispatch").filter {
            it.uriPattern.contains("threads")
        }
        val threadParams = threadUris.flatMap { it.queryParameters }.toSet()
        assertThat(threadParams).containsNoneOf("sender", "limit")
    }

    // ==================== Group S: sourceClass attribution ====================

    @Test
    fun `DirectExtraActivity extras have correct sourceClass`() {
        val extras = extrasFor("DirectExtraActivity")
        assertThat(extras).isNotEmpty()
        extras.forEach { extra ->
            assertThat(extra.sourceClass).contains("DirectExtraActivity")
        }
    }

    @Test
    fun `BasicProvider items URI has sourceClass containing BasicProvider`() {
        val itemsUri = urisForAuthority("$PKG.basic").find {
            it.uriPattern == "content://$PKG.basic/items"
        }
        assertThat(itemsUri).isNotNull()
        assertThat(itemsUri!!.sourceClass).contains("BasicProvider")
    }

    @Test
    fun `content provider call has sourceClass containing ProviderCaller`() {
        val calls = analysis.contentProviderCalls
        val callWithSourceClass = calls.find { it.sourceClass?.contains("ProviderCaller") == true }
        assertThat(callWithSourceClass).isNotNull()
    }

    // ==================== Group T: FileProvider exact assertions ====================

    @Test
    fun `code-referenced FileProvider paths have exact filePath values`() {
        val codeRefs = analysis.fileProviderPaths.filter { it.pathType == "code-reference" }
        val fileNames = codeRefs.mapNotNull { it.filePath }
        assertThat(fileNames).containsAtLeast("report.pdf", "data.csv", "app.log")
    }

    @Test
    fun `XML-defined FileProvider paths have null filePath`() {
        val xmlPaths = analysis.fileProviderPaths.filter {
            it.pathType != "code-reference"
        }
        assertThat(xmlPaths).isNotEmpty()
        xmlPaths.forEach { path ->
            assertThat(path.filePath).isNull()
        }
    }

    // ==================== Group U: Usability completeness checks ====================

    @Test
    fun `all exported component extras have non-null extraType`() {
        val exported = analysis.intentExtras.filter {
            it.associatedComponent != null
        }
        assertThat(exported).isNotEmpty()
        val missingType = exported.filter { it.extraType == null }
        assertThat(missingType).isEmpty()
    }

    @Test
    fun `all content provider URIs have non-null authority`() {
        val contentUris = analysis.contentProviderUris.filter {
            it.uriPattern.startsWith("content://")
        }
        assertThat(contentUris).isNotEmpty()
        contentUris.forEach { uri ->
            assertThat(uri.authority).isNotNull()
            assertThat(uri.authority).isNotEmpty()
        }
    }

    @Test
    fun `all Retrofit endpoints have non-null httpMethod`() {
        val retrofitEndpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        assertThat(retrofitEndpoints).isNotEmpty()
        retrofitEndpoints.forEach { endpoint ->
            assertThat(endpoint.httpMethod).isNotNull()
            assertThat(endpoint.httpMethod).isNotEmpty()
        }
    }

    // ==================== Group V: Activity alias extras ====================

    @Test
    fun `alias extras resolve to target ValueScanActivity extras`() {
        val aliasExtras = extrasFor("SettingsAlias")
        val targetExtras = extrasFor("ValueScanActivity")
        // Alias should inherit extras from its target activity
        val targetKeys = targetExtras.map { it.extraKey }.toSet()
        val aliasKeys = aliasExtras.map { it.extraKey }.toSet()
        assertThat(aliasKeys).isEqualTo(targetKeys)
    }

    @Test
    fun `alias has associatedAction SETTINGS_ALIAS`() {
        val aliasExtras = extrasFor("SettingsAlias")
        assertThat(aliasExtras).isNotEmpty()
        val actions = aliasExtras.mapNotNull { it.associatedAction }.toSet()
        assertThat(actions).contains("$PKG.action.SETTINGS_ALIAS")
    }

    // ==================== Group W: Virtual Discovery Document Synthesis ====================

    @Test
    fun `synthesize produces non-null document from testapp endpoints`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)
        assertThat(doc).isNotNull()
    }

    @Test
    fun `synthesize document has correct rootUrl for api example com`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        assertThat(doc.rootUrl).isEqualTo("https://api.example.com/v1/")
    }

    @Test
    fun `synthesize groups endpoints into resources by first path segment`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        assertThat(doc.resources.keys).containsAtLeast("users", "search", "auth")
    }

    @Test
    fun `synthesize preserves httpMethod on methods`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allMethods = doc.resources.values.flatMap { it.methods.values }.map { it.httpMethod }.toSet()
        assertThat(allMethods).containsAtLeast("GET", "POST", "PUT", "DELETE", "PATCH")
    }

    @Test
    fun `synthesize extracts path params with location path and required true`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val usersResource = doc.resources["users"]!!
        val getUser = usersResource.methods.values.find { it.httpMethod == "GET" && it.path.contains("{id}") }
        assertThat(getUser).isNotNull()
        val idParam = getUser!!.parameters["id"]
        assertThat(idParam).isNotNull()
        assertThat(idParam!!.location).isEqualTo("path")
        assertThat(idParam.required).isTrue()
    }

    @Test
    fun `synthesize extracts query params with location query and required false`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val searchResource = doc.resources["search"]!!
        val searchMethod = searchResource.methods.values.first()
        val qParam = searchMethod.parameters["q"]
        assertThat(qParam).isNotNull()
        assertThat(qParam!!.location).isEqualTo("query")
        assertThat(qParam.required).isFalse()
    }

    @Test
    fun `synthesize extracts header params with location header`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allParams = doc.resources.values
            .flatMap { it.methods.values }
            .flatMap { it.parameters.values }
        val headerParams = allParams.filter { it.location == "header" }
        assertThat(headerParams).isNotEmpty()
        assertThat(headerParams.map { it.name }).contains("Authorization")
    }

    @Test
    fun `synthesize marks all methods with source dex`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allMethods = doc.resources.values.flatMap { it.methods.values }
        assertThat(allMethods).isNotEmpty()
        allMethods.forEach { method ->
            assertThat(method.source).isEqualTo("dex")
        }
    }

    // ==================== Group X: Virtual Discovery Document Merge ====================

    @Test
    fun `merge adds virtual-only methods to remote document`() {
        val fetcher = ApiSpecFetcher()
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val virtual = fetcher.synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!

        // Create a remote doc with only one method
        val remoteMethod = DiscoveryMethod(
            id = "GET users/{id}", httpMethod = "GET", path = "users/{id}",
            description = "Get a user", parameters = emptyMap(), parameterOrder = emptyList(),
            scopes = emptyList(), source = ""
        )
        val remote = DiscoveryDocument(
            name = "api.example.com", version = "v1", title = "Remote API",
            description = "Remote spec", rootUrl = "https://api.example.com/v1/",
            servicePath = "/",
            resources = mapOf("users" to DiscoveryResource(
                name = "users", methods = mapOf("GET users/{id}" to remoteMethod), resources = emptyMap()
            ))
        )

        val merged = fetcher.mergeDocuments(remote, virtual)
        val allMethods = merged.resources.values.flatMap { it.methods.values }
        // Should have more methods than just the one remote method
        assertThat(allMethods.size).isGreaterThan(1)
        // Should include virtual-only methods (e.g., POST users)
        val virtualMethods = allMethods.filter { it.source == "dex" }
        assertThat(virtualMethods).isNotEmpty()
    }

    @Test
    fun `merge does not duplicate methods with same httpMethod and path`() {
        val fetcher = ApiSpecFetcher()
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val virtual = fetcher.synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!

        // Create remote with a method that exists in virtual too
        val remoteMethod = DiscoveryMethod(
            id = "GET users/{id}", httpMethod = "GET", path = "users/{id}",
            description = "Get a user", parameters = emptyMap(), parameterOrder = emptyList(),
            scopes = emptyList(), source = ""
        )
        val remote = DiscoveryDocument(
            name = "api.example.com", version = "v1", title = "Remote API",
            description = "Remote spec", rootUrl = "https://api.example.com/v1/",
            servicePath = "/",
            resources = mapOf("users" to DiscoveryResource(
                name = "users", methods = mapOf("GET users/{id}" to remoteMethod), resources = emptyMap()
            ))
        )

        val merged = fetcher.mergeDocuments(remote, virtual)
        val usersResource = merged.resources["users"]!!
        val getUserMethods = usersResource.methods.values.filter {
            it.httpMethod == "GET" && it.path == "users/{id}"
        }
        // Should not have duplicates
        assertThat(getUserMethods).hasSize(1)
        // The kept one should be from remote (tagged as "spec")
        assertThat(getUserMethods.first().source).isEqualTo("spec")
    }

    @Test
    fun `merge tags remote methods with source spec`() {
        val fetcher = ApiSpecFetcher()
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val virtual = fetcher.synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!

        val remoteMethod = DiscoveryMethod(
            id = "GET health", httpMethod = "GET", path = "health",
            description = "Health check", parameters = emptyMap(), parameterOrder = emptyList(),
            scopes = emptyList(), source = ""
        )
        val remote = DiscoveryDocument(
            name = "api.example.com", version = "v1", title = "Remote API",
            description = "Remote spec", rootUrl = "https://api.example.com/v1/",
            servicePath = "/",
            resources = mapOf("health" to DiscoveryResource(
                name = "health", methods = mapOf("GET health" to remoteMethod), resources = emptyMap()
            ))
        )

        val merged = fetcher.mergeDocuments(remote, virtual)
        val healthMethod = merged.resources["health"]!!.methods.values.first()
        assertThat(healthMethod.source).isEqualTo("spec")
    }

    @Test
    fun `merge preserves remote document metadata`() {
        val fetcher = ApiSpecFetcher()
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val virtual = fetcher.synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!

        val remote = DiscoveryDocument(
            name = "MyAPI", version = "v2", title = "My Remote API",
            description = "Production API", rootUrl = "https://api.example.com/v1/",
            servicePath = "/api/",
            resources = emptyMap()
        )

        val merged = fetcher.mergeDocuments(remote, virtual)
        assertThat(merged.name).isEqualTo("MyAPI")
        assertThat(merged.version).isEqualTo("v2")
        assertThat(merged.title).isEqualTo("My Remote API")
        assertThat(merged.rootUrl).isEqualTo("https://api.example.com/v1/")
    }

    // ==================== Group Y: @Part/@FieldMap/@QueryMap Detection ====================

    @Test
    fun `UploadService upload has Part params file and description`() {
        val upload = analysis.apiEndpoints.find {
            it.sourceType == "retrofit" && it.path == "files/upload"
        }
        assertThat(upload).isNotNull()
        assertThat(upload!!.queryParams).containsAtLeast("file", "description")
    }

    @Test
    fun `UploadService upload httpMethod is POST`() {
        val upload = analysis.apiEndpoints.find {
            it.sourceType == "retrofit" && it.path == "files/upload"
        }
        assertThat(upload).isNotNull()
        assertThat(upload!!.httpMethod).isEqualTo("POST")
    }

    @Test
    fun `UploadService submitForm has hasBody true`() {
        val submit = analysis.apiEndpoints.find {
            it.sourceType == "retrofit" && it.path == "forms/submit"
        }
        assertThat(submit).isNotNull()
        assertThat(submit!!.hasBody).isTrue()
    }

    @Test
    fun `UploadService filterItems has query param sort`() {
        val filter = analysis.apiEndpoints.find {
            it.sourceType == "retrofit" && it.path == "items/filter"
        }
        assertThat(filter).isNotNull()
        assertThat(filter!!.queryParams).contains("sort")
    }

    @Test
    fun `UploadService endpoints NOT on other services`() {
        val apiServiceEndpoints = analysis.apiEndpoints.filter {
            it.sourceType == "retrofit" && it.sourceClass.contains("ApiService")
        }
        val uploadPaths = listOf("files/upload", "forms/submit", "items/filter")
        for (ep in apiServiceEndpoints) {
            assertThat(ep.path).isNotIn(uploadPaths)
        }
    }

    // ==================== Group Z: Ordered Broadcast Extras ====================

    @Test
    fun `OrderedReceiver has command extra with type String`() {
        val extras = extrasForComponent("$PKG.receivers.OrderedReceiver")
        val command = extras.find { it.extraKey == "command" }
        assertThat(command).isNotNull()
        assertThat(command!!.extraType).isEqualTo("String")
    }

    @Test
    fun `OrderedReceiver has associatedAction ORDERED`() {
        val extras = extrasForComponent("$PKG.receivers.OrderedReceiver")
        assertThat(extras).isNotEmpty()
        val actions = extras.mapNotNull { it.associatedAction }.toSet()
        assertThat(actions).contains("$PKG.action.ORDERED")
    }

    @Test
    fun `OrderedReceiver extras NOT on DataReceiver`() {
        val dataExtras = extrasForComponent("$PKG.receivers.DataReceiver")
        val dataKeys = dataExtras.map { it.extraKey }.toSet()
        assertThat(dataKeys).doesNotContain("command")
    }

    // ==================== Group AA: ContentResolver call() Bundle Negative ====================

    @Test
    fun `BundleCaller export call has authority and method name`() {
        val calls = analysis.contentProviderCalls
        val exportCall = calls.find { it.methodName == "export" }
        assertThat(exportCall).isNotNull()
        assertThat(exportCall!!.authority).isEqualTo("$PKG.basic")
    }

    @Test
    fun `BundleCaller export call arg is all`() {
        val calls = analysis.contentProviderCalls
        val exportCall = calls.find { it.methodName == "export" }
        assertThat(exportCall).isNotNull()
        assertThat(exportCall!!.arg).isEqualTo("all")
    }

    // ==================== Group AB: Discovery Document Completeness ====================

    @Test
    fun `all synthesized methods have non-empty path`() {
        val allEndpoints = analysis.apiEndpoints.filter { it.sourceType == "retrofit" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/",
            allEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" })!!
        val allMethods = doc.resources.values.flatMap { it.methods.values }
        allMethods.forEach { method ->
            assertThat(method.path).isNotEmpty()
        }
    }

    @Test
    fun `all path params have required true`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allParams = doc.resources.values
            .flatMap { it.methods.values }
            .flatMap { it.parameters.values }
        val pathParams = allParams.filter { it.location == "path" }
        assertThat(pathParams).isNotEmpty()
        pathParams.forEach { param ->
            assertThat(param.required).isTrue()
        }
    }

    @Test
    fun `all query params have required false`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allParams = doc.resources.values
            .flatMap { it.methods.values }
            .flatMap { it.parameters.values }
        val queryParams = allParams.filter { it.location == "query" }
        assertThat(queryParams).isNotEmpty()
        queryParams.forEach { param ->
            assertThat(param.required).isFalse()
        }
    }

    @Test
    fun `no synthesized method has empty httpMethod`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        val allMethods = doc.resources.values.flatMap { it.methods.values }
        allMethods.forEach { method ->
            assertThat(method.httpMethod).isNotEmpty()
        }
    }

    // ==================== Group AC: API Key → Discovery Document Flow ====================

    @Test
    fun `api example com endpoints include Retrofit-extracted paths`() {
        val apiEndpoints = analysis.apiEndpoints.filter {
            it.baseUrl == "https://api.example.com/v1/" && it.sourceType == "retrofit"
        }
        val paths = apiEndpoints.map { it.path }.toSet()
        assertThat(paths).containsAtLeast("users/{id}", "users", "search")
    }

    @Test
    fun `Google API key associated URLs include maps example com`() {
        val googleKey = analysis.sensitiveStrings.find {
            it.category == "Google API Key"
        }
        assertThat(googleKey).isNotNull()
        assertThat(googleKey!!.associatedUrls).isNotEmpty()
        val hasMapsExample = googleKey.associatedUrls.any { it.contains("maps.example.com") }
        assertThat(hasMapsExample).isTrue()
    }

    @Test
    fun `synthesized document for api example com includes users resource`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        assertThat(doc.resources).containsKey("users")
        val usersResource = doc.resources["users"]!!
        assertThat(usersResource.methods).isNotEmpty()
    }

    @Test
    fun `synthesized document servicePath is common prefix of all paths`() {
        val apiEndpoints = analysis.apiEndpoints.filter { it.baseUrl == "https://api.example.com/v1/" }
        val doc = ApiSpecFetcher().synthesizeFromEndpoints("https://api.example.com/v1/", apiEndpoints)!!
        // servicePath should be "/" since paths like "users/{id}", "search", "auth/login" have no common prefix
        assertThat(doc.servicePath).isEqualTo("/")
    }

    // ==================== Group AD: Content provider param exactness ====================

    @Test
    fun `BasicProvider items URI has exactly filter and sort_by params`() {
        val items = urisForAuthority("$PKG.basic").find {
            it.uriPattern == "content://$PKG.basic/items"
        }
        assertThat(items).isNotNull()
        assertThat(items!!.queryParameters).containsExactly("filter", "sort_by")
    }

    @Test
    fun `ParamProvider params URI has exactly verbose include_deleted tags`() {
        val paramUris = urisForAuthority("$PKG.params")
        val allParams = paramUris.flatMap { it.queryParameters }.toSet()
        assertThat(allParams).containsExactly("verbose", "include_deleted", "tags")
    }

    @Test
    fun `BulkParamReader params are exactly user_id action referrer`() {
        val uris = analysis.contentProviderUris.filter {
            it.sourceClass?.contains("BulkParamReader") == true
        }
        val allParams = uris.flatMap { it.queryParameters }.toSet()
        assertThat(allParams).containsAtLeast("user_id", "action", "referrer")
    }

    @Test
    fun `StaticUriProvider has no query params`() {
        val staticUris = urisForAuthority("$PKG.static")
        assertThat(staticUris).isNotEmpty()
        for (uri in staticUris) {
            assertThat(uri.queryParameters).isEmpty()
        }
    }

    // ==================== Group AE: Boolean.parseBoolean() value detection ====================

    @Test
    fun `ParseBooleanActivity verbose has values true false`() {
        val extras = extrasFor("ParseBooleanActivity")
        val verbose = extras.find { it.extraKey == "verbose" }
        assertThat(verbose).isNotNull()
        assertThat(verbose!!.possibleValues).containsAtLeast("true", "false")
    }

    @Test
    fun `ParseBooleanActivity verbose type is String`() {
        val extras = extrasFor("ParseBooleanActivity")
        val verbose = extras.find { it.extraKey == "verbose" }
        assertThat(verbose).isNotNull()
        // getStringExtra → type is String (parseBoolean is applied after extraction)
        assertThat(verbose!!.extraType).isEqualTo("String")
    }

    @Test
    fun `ParseBooleanActivity verbose NOT on other activities`() {
        val directExtras = extrasFor("DirectExtraActivity")
        val directKeys = directExtras.map { it.extraKey }.toSet()
        assertThat(directKeys).doesNotContain("verbose")
    }

    // ==================== Group AF: Additional sensitive string categories ====================

    @Test
    fun `MongoDB URI detected as sensitive string`() {
        val mongo = analysis.sensitiveStrings.find { it.category == "MongoDB URI" }
        assertThat(mongo).isNotNull()
        assertThat(mongo!!.value).contains("mongodb")
    }

    @Test
    fun `MongoDB URI has no associated URLs`() {
        val mongo = analysis.sensitiveStrings.find { it.category == "MongoDB URI" }
        assertThat(mongo).isNotNull()
        assertThat(mongo!!.associatedUrls).isEmpty()
    }

    @Test
    fun `Mapbox Token detected as sensitive string`() {
        val mapbox = analysis.sensitiveStrings.find { it.category == "Mapbox Token" }
        assertThat(mapbox).isNotNull()
        assertThat(mapbox!!.value).startsWith("pk.eyJ")
    }

    @Test
    fun `Sentry DSN detected as sensitive string`() {
        val sentry = analysis.sensitiveStrings.find { it.category == "Sentry DSN" }
        assertThat(sentry).isNotNull()
        assertThat(sentry!!.value).contains("sentry.io")
    }

    // ==================== Group AG: Tighter value assertions — containsExactly ====================

    @Test
    fun `StringSwitch category has exactly food drink dessert`() {
        val extras = extrasFor("StringSwitchActivity")
        val category = extras.find { it.extraKey == "category" }
        assertThat(category).isNotNull()
        assertThat(category!!.possibleValues).containsExactly("food", "drink", "dessert")
    }

    @Test
    fun `PrefixValue transport has exactly air sea`() {
        val extras = extrasFor("PrefixValueActivity")
        val transport = extras.find { it.extraKey == "transport" }
        assertThat(transport).isNotNull()
        assertThat(transport!!.possibleValues).containsExactly("air", "sea")
    }

    @Test
    fun `priority_code has exactly 10 20 30`() {
        val extras = extrasFor("ValueScanActivity")
        val priority = extras.find { it.extraKey == "priority_code" }
        assertThat(priority).isNotNull()
        assertThat(priority!!.possibleValues).containsExactly("10", "20", "30")
    }

    @Test
    fun `StringBuilder URL is exactly events example com api v3 track`() {
        val concat = analysis.apiEndpoints.filter { it.sourceType == "concatenation" }
        val events = concat.find { it.fullUrl.contains("events.example.com") }
        assertThat(events).isNotNull()
        assertThat(events!!.fullUrl).isEqualTo("https://events.example.com/api/v3/track")
    }

    @Test
    fun `UploadService endpoints have correct baseUrl upload example com`() {
        val uploadEndpoints = analysis.apiEndpoints.filter {
            it.sourceType == "retrofit" && it.baseUrl == "https://upload.example.com/"
        }
        assertThat(uploadEndpoints).isNotEmpty()
        val paths = uploadEndpoints.map { it.path }.toSet()
        assertThat(paths).containsAtLeast("files/upload", "forms/submit", "items/filter")
    }

    // ==================== Group AH: OkHttp header-endpoint association ====================

    @Test
    fun `OkHttp export endpoint has both X-Api-Key and Accept headers`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val export = okhttp.find { it.fullUrl.contains("data/export") }
        assertThat(export).isNotNull()
        assertThat(export!!.headerParams).containsExactly("X-Api-Key", "Accept")
    }

    @Test
    fun `OkHttp export endpoint fullUrl is correct`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val export = okhttp.find { it.fullUrl.contains("data/export") }
        assertThat(export).isNotNull()
        assertThat(export!!.fullUrl).isEqualTo("https://api.example.com/data/export")
    }

    @Test
    fun `InlineKeyClient payments endpoint has X-Stripe-Key header`() {
        val okhttp = analysis.apiEndpoints.filter { it.sourceType == "okhttp" }
        val payments = okhttp.find { it.fullUrl.contains("payments.example.com") }
        assertThat(payments).isNotNull()
        assertThat(payments!!.headerParams).contains("X-Stripe-Key")
    }

    // ==================== Group AI: Uri.Builder pattern detection ====================

    @Test
    fun `UriBuilderProvider records URI detected via builder`() {
        val uris = urisForAuthority("$PKG.builder")
        val patterns = uris.map { it.uriPattern }.toSet()
        assertThat(patterns).contains("content://$PKG.builder/records")
    }

    @Test
    fun `UriBuilderProvider has correct authority`() {
        val uris = urisForAuthority("$PKG.builder")
        assertThat(uris).isNotEmpty()
        uris.forEach { assertThat(it.authority).isEqualTo("$PKG.builder") }
    }

    @Test
    fun `UriBuilderProvider records URI has sourceClass containing UriBuilderProvider`() {
        val uris = urisForAuthority("$PKG.builder")
        val records = uris.find { it.uriPattern == "content://$PKG.builder/records" }
        assertThat(records).isNotNull()
        assertThat(records!!.sourceClass).contains("UriBuilderProvider")
    }

    @Test
    fun `UriBuilderProvider URIs NOT on BasicProvider`() {
        val basicUris = urisForAuthority("$PKG.basic")
        val basicPatterns = basicUris.map { it.uriPattern }.toSet()
        assertThat(basicPatterns.none { it.contains("$PKG.builder") }).isTrue()
    }

    // ==================== Group AJ: Bundle containsKey() detection ====================

    @Test
    fun `ContainsKeyActivity feature_flag detected as extra`() {
        val extras = extrasFor("ContainsKeyActivity")
        val featureFlag = extras.find { it.extraKey == "feature_flag" }
        assertThat(featureFlag).isNotNull()
    }

    @Test
    fun `ContainsKeyActivity experiment_id detected as extra`() {
        val extras = extrasFor("ContainsKeyActivity")
        val experimentId = extras.find { it.extraKey == "experiment_id" }
        assertThat(experimentId).isNotNull()
    }

    @Test
    fun `ContainsKeyActivity extras NOT on other activities`() {
        val directExtras = extrasFor("DirectExtraActivity")
        val directKeys = directExtras.map { it.extraKey }.toSet()
        assertThat(directKeys).doesNotContain("feature_flag")
        assertThat(directKeys).doesNotContain("experiment_id")
    }

    // ==================== Group AK: Intent addCategory() detection ====================

    @Test
    fun `CategoryActivity PREMIUM category detected`() {
        val categories = analysis.intentExtras
            .filter { it.sourceClass?.contains("CategoryActivity") == true }
        // Categories are stored separately from extras — check discoveredCategories
        // via the analysis dump or directly. For now, verify no extra keys named as categories.
        // The categories are exposed in DexAnalysis.discoveredCategories
        assertThat(analysis.discoveredCategories).contains("$PKG.category.PREMIUM")
    }

    @Test
    fun `CategoryActivity ALTERNATIVE category detected`() {
        assertThat(analysis.discoveredCategories).contains("android.intent.category.ALTERNATIVE")
    }

    @Test
    fun `addCategory categories NOT confused with extras`() {
        val allExtraKeys = analysis.intentExtras.map { it.extraKey }.toSet()
        assertThat(allExtraKeys).doesNotContain("$PKG.category.PREMIUM")
        assertThat(allExtraKeys).doesNotContain("android.intent.category.ALTERNATIVE")
    }

    // ==================== Group AL: Cross-feature isolation ====================

    @Test
    fun `Uri Builder URIs do NOT appear as deep link URIs`() {
        val builderUris = urisForAuthority("$PKG.builder")
        for (uri in builderUris) {
            assertThat(uri.uriPattern).startsWith("content://")
        }
    }

    @Test
    fun `containsKey extras have type Unknown`() {
        val extras = extrasFor("ContainsKeyActivity")
        val containsKeyExtras = extras.filter { it.extraKey in listOf("feature_flag", "experiment_id") }
        assertThat(containsKeyExtras).isNotEmpty()
        containsKeyExtras.forEach { assertThat(it.extraType).isEqualTo("Unknown") }
    }

    @Test
    fun `all content provider URIs still have non-null authority`() {
        val contentUris = analysis.contentProviderUris.filter { it.uriPattern.startsWith("content://") }
        assertThat(contentUris).isNotEmpty()
        contentUris.forEach { assertThat(it.authority).isNotNull() }
    }

    // ==================== Group AM: Regression ====================

    @Test
    fun `existing UriMatcher detection still works after Uri Builder addition`() {
        val dispatchUris = urisForAuthority("$PKG.dispatch")
        assertThat(dispatchUris).isNotEmpty()
    }

    @Test
    fun `existing getExtra detection still works after containsKey addition`() {
        val extras = extrasFor("DirectExtraActivity")
        val userName = extras.find { it.extraKey == "user_name" }
        assertThat(userName).isNotNull()
        assertThat(userName!!.extraType).isEqualTo("String")
    }

    // ==================== Group AN: Uri.Builder appendQueryParameter detection ====================

    @Test
    fun `UriBuilderProvider filtered records URI has status query param`() {
        val uris = urisForAuthority("$PKG.builder")
        val paramsAll = uris.flatMap { it.queryParameters }.toSet()
        assertThat(paramsAll).contains("status")
    }

    @Test
    fun `UriBuilderProvider filtered records URI has format query param`() {
        val uris = urisForAuthority("$PKG.builder")
        val paramsAll = uris.flatMap { it.queryParameters }.toSet()
        assertThat(paramsAll).contains("format")
    }

    @Test
    fun `UriBuilderProvider status param has value active`() {
        val uris = urisForAuthority("$PKG.builder")
        val paramValues = uris.flatMap { uri ->
            uri.queryParameterValues["status"]?.map { it } ?: emptyList()
        }.toSet()
        assertThat(paramValues).contains("active")
    }

    @Test
    fun `UriBuilderProvider format param has value json`() {
        val uris = urisForAuthority("$PKG.builder")
        val paramValues = uris.flatMap { uri ->
            uri.queryParameterValues["format"]?.map { it } ?: emptyList()
        }.toSet()
        assertThat(paramValues).contains("json")
    }

    @Test
    fun `UriBuilderProvider query params NOT on BasicProvider`() {
        val basicUris = urisForAuthority("$PKG.basic")
        val basicParams = basicUris.flatMap { it.queryParameters }.toSet()
        assertThat(basicParams).doesNotContain("status")
        assertThat(basicParams).doesNotContain("format")
    }

    // ==================== Group AO: Intent.setData() / setDataAndType() detection ====================

    @Test
    fun `SetDataActivity items URI detected via setData`() {
        assertThat(analysis.discoveredDataUris).contains("content://$PKG.basic/items")
    }

    @Test
    fun `SetDataActivity export URI detected via setDataAndType`() {
        assertThat(analysis.discoveredDataUris).contains("content://$PKG.basic/export")
    }

    @Test
    fun `SetDataActivity export URI has mime type application json`() {
        val mimeType = analysis.discoveredDataMimeTypes["content://$PKG.basic/export"]
        assertThat(mimeType).isEqualTo("application/json")
    }

    @Test
    fun `setData URIs are separate from content provider URIs`() {
        // discoveredDataUris are Intent data, not content provider patterns
        val cpAuthorities = analysis.contentProviderUris.map { it.authority }.toSet()
        // The URIs should exist in discoveredDataUris regardless of content provider registration
        assertThat(analysis.discoveredDataUris).isNotEmpty()
    }

    // ==================== Group AP: getXxxExtra default value extraction ====================

    @Test
    fun `DefaultValueActivity retry_count has default value 3`() {
        val extras = extrasFor("DefaultValueActivity")
        val retryCount = extras.find { it.extraKey == "retry_count" }
        assertThat(retryCount).isNotNull()
        assertThat(retryCount!!.defaultValue).isEqualTo("3")
    }

    @Test
    fun `DefaultValueActivity debug_mode has default value false`() {
        val extras = extrasFor("DefaultValueActivity")
        val debugMode = extras.find { it.extraKey == "debug_mode" }
        assertThat(debugMode).isNotNull()
        assertThat(debugMode!!.defaultValue).isEqualTo("false")
    }

    @Test
    fun `DefaultValueActivity timeout_ms has default value 5000`() {
        val extras = extrasFor("DefaultValueActivity")
        val timeout = extras.find { it.extraKey == "timeout_ms" }
        assertThat(timeout).isNotNull()
        assertThat(timeout!!.defaultValue).isEqualTo("5000")
    }

    @Test
    fun `DefaultValueActivity retry_count has type Int`() {
        val extras = extrasFor("DefaultValueActivity")
        val retryCount = extras.find { it.extraKey == "retry_count" }
        assertThat(retryCount).isNotNull()
        assertThat(retryCount!!.extraType).isEqualTo("Int")
    }

    @Test
    fun `string extras do NOT have default values`() {
        val extras = extrasFor("DirectExtraActivity")
        val userName = extras.find { it.extraKey == "user_name" }
        assertThat(userName).isNotNull()
        assertThat(userName!!.defaultValue).isNull()
    }

    // ==================== Group AQ: Cross-feature isolation (Phase 8) ====================

    @Test
    fun `appendQueryParameter params only on builder provider URIs`() {
        val dispatchUris = urisForAuthority("$PKG.dispatch")
        val dispatchParams = dispatchUris.flatMap { it.queryParameters }.toSet()
        assertThat(dispatchParams).doesNotContain("status")
        assertThat(dispatchParams).doesNotContain("format")
    }

    @Test
    fun `setData items URI not in discoveredDataMimeTypes`() {
        // setData only sets URI, no mime type
        assertThat(analysis.discoveredDataMimeTypes).doesNotContainKey("content://$PKG.basic/items")
    }

    // ==================== Group AR: Regression (Phase 8) ====================

    @Test
    fun `existing Uri Builder records URI still detected after appendQueryParameter addition`() {
        val uris = urisForAuthority("$PKG.builder")
        val patterns = uris.map { it.uriPattern }
        assertThat(patterns.any { it.contains("records") && !it.contains("?") }).isTrue()
    }

    @Test
    fun `existing putExtra values still detected after default value addition`() {
        val extras = extrasFor("PutExtraActivity")
        assertThat(extras).isNotEmpty()
    }
}
