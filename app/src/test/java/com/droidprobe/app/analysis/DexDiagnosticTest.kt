package com.droidprobe.app.analysis

import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.data.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class DexDiagnosticTest {

    @Test
    fun `dump full analysis results`() {
        val apkStream = javaClass.getResourceAsStream("/testapp.apk")
            ?: error("testapp.apk not found")
        val tempFile = File.createTempFile("testapp", ".apk")
        tempFile.deleteOnExit()
        apkStream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }

        val pkg = "com.droidprobe.testapp"
        fun activity(name: String, exported: Boolean, filters: List<IntentFilterInfo> = emptyList()) =
            ExportedComponent("$pkg.activities.$name", exported, null, filters)
        fun actionFilter(action: String) = listOf(IntentFilterInfo(listOf(action), listOf("android.intent.category.DEFAULT"), emptyList(), emptyList(), emptyList(), emptyList()))
        fun provider(name: String, authority: String, exported: Boolean) =
            ProviderComponent("$pkg.providers.$name", authority, exported, null, null, null, false, emptyList())

        val manifest = ManifestAnalysis(
            packageName = pkg,
            activities = listOf(
                activity("DirectExtraActivity", true, actionFilter("$pkg.action.DIRECT")),
                activity("BaseExtraActivity", false),
                activity("InheritedChildActivity", true, actionFilter("$pkg.action.INHERITED")),
                activity("ValueScanActivity", true, actionFilter("$pkg.action.VALUES")),
                activity("BundleExtraActivity", true, actionFilter("$pkg.action.BUNDLE")),
                activity("PutExtraActivity", true, actionFilter("$pkg.action.PUT")),
                activity("InterProcActivity", true, actionFilter("$pkg.action.INTERPROC")),
                activity("DeepLinkActivity", true, listOf(IntentFilterInfo(
                    listOf("android.intent.action.VIEW"),
                    listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                    listOf("testapp"), listOf("open"), emptyList(), emptyList()
                )))
            ),
            services = emptyList(), receivers = emptyList(),
            providers = listOf(
                provider("BasicProvider", "$pkg.basic", true),
                provider("DispatchProvider", "$pkg.dispatch", true),
                provider("ParamProvider", "$pkg.params", true),
                provider("StaticUriProvider", "$pkg.static", true),
                ProviderComponent("androidx.core.content.FileProvider", "$pkg.fileprovider", false, null, null, null, true, emptyList())
            ),
            customPermissions = emptyList()
        )

        val analysis = runBlocking { DexAnalyzer().analyze(tempFile.absolutePath, manifest) }

        val sb = StringBuilder()
        sb.appendLine("=== API ENDPOINTS (${analysis.apiEndpoints.size}) ===")
        for (ep in analysis.apiEndpoints) {
            sb.appendLine("  [${ep.sourceType}] ${ep.httpMethod ?: "?"} ${ep.fullUrl}")
            sb.appendLine("    base=${ep.baseUrl} path=${ep.path}")
            sb.appendLine("    query=${ep.queryParams} path=${ep.pathParams} header=${ep.headerParams} body=${ep.hasBody}")
        }

        sb.appendLine("\n=== CONTENT PROVIDER URIS (${analysis.contentProviderUris.size}) ===")
        for (uri in analysis.contentProviderUris) {
            sb.appendLine("  authority=${uri.authority} pattern=${uri.uriPattern} matchCode=${uri.matchCode}")
            sb.appendLine("    params=${uri.queryParameters} values=${uri.queryParameterValues}")
            sb.appendLine("    source=${uri.sourceClass}")
        }

        sb.appendLine("\n=== SENSITIVE STRINGS (${analysis.sensitiveStrings.size}) ===")
        for (s in analysis.sensitiveStrings) {
            sb.appendLine("  [${s.category}] ${s.value.take(40)}... src=${s.sourceClass}")
        }

        sb.appendLine("\n=== RAW CONTENT URIS (${analysis.rawContentUriStrings.size}) ===")
        for (u in analysis.rawContentUriStrings) sb.appendLine("  $u")

        sb.appendLine("\n=== DEEP LINK URIS (${analysis.deepLinkUriStrings.size}) ===")
        for (u in analysis.deepLinkUriStrings) sb.appendLine("  $u")

        sb.appendLine("\n=== ALL URL STRINGS (${analysis.allUrlStrings.size}) ===")
        for (u in analysis.allUrlStrings) sb.appendLine("  $u")

        sb.appendLine("\n=== FILE PROVIDER PATHS (${analysis.fileProviderPaths.size}) ===")
        for (f in analysis.fileProviderPaths) {
            sb.appendLine("  authority=${f.authority} type=${f.pathType} path=${f.path} name=${f.name} file=${f.filePath}")
        }

        sb.appendLine("\n=== CONTENT PROVIDER CALLS (${analysis.contentProviderCalls.size}) ===")
        for (c in analysis.contentProviderCalls) {
            sb.appendLine("  authority=${c.authority} method=${c.methodName} src=${c.sourceClass}")
        }

        sb.appendLine("\n=== INTENT EXTRAS (${analysis.intentExtras.size}) ===")
        for (e in analysis.intentExtras) {
            sb.appendLine("  key=${e.extraKey} type=${e.extraType} values=${e.possibleValues}")
            sb.appendLine("    component=${e.associatedComponent} src=${e.sourceClass}")
        }

        File("build/analysis-dump.txt").writeText(sb.toString())
        assert(true) // Always pass, just dump data
    }

}
