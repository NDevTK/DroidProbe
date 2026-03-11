package com.droidprobe.app.analysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
                activity("StringSwitchActivity", true, actionFilter("$pkg.action.SWITCH")),
                activity("PrefixValueActivity", true, actionFilter("$pkg.action.PREFIX")),
                activity("DeepLinkActivity", true, listOf(IntentFilterInfo(
                    listOf("android.intent.action.VIEW"),
                    listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                    listOf("testapp"), listOf("open"), emptyList(), emptyList()
                )))
            ),
            services = listOf(
                ExportedComponent("$pkg.services.SyncService", true, null, listOf(IntentFilterInfo(listOf("$pkg.action.SYNC"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())))
            ),
            receivers = listOf(
                ExportedComponent("$pkg.receivers.DataReceiver", true, null, listOf(IntentFilterInfo(listOf("$pkg.action.DATA"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))),
                ExportedComponent("$pkg.receivers.OrderedReceiver", true, null, listOf(IntentFilterInfo(listOf("$pkg.action.ORDERED"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())))
            ),
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
            sb.appendLine("    params=${uri.queryParameters} values=${uri.queryParameterValues} defaults=${uri.queryParameterDefaults}")
            sb.appendLine("    source=${uri.sourceClass}")
        }

        sb.appendLine("\n=== SENSITIVE STRINGS (${analysis.sensitiveStrings.size}) ===")
        for (s in analysis.sensitiveStrings) {
            sb.appendLine("  [${s.category}] ${s.value.take(40)}... src=${s.sourceClass} urls=${s.associatedUrls}")
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
            sb.appendLine("  authority=${c.authority} method=${c.methodName} arg=${c.arg} src=${c.sourceClass}")
        }

        sb.appendLine("\n=== INTENT EXTRAS (${analysis.intentExtras.size}) ===")
        for (e in analysis.intentExtras) {
            sb.appendLine("  key=${e.extraKey} type=${e.extraType} values=${e.possibleValues}")
            sb.appendLine("    component=${e.associatedComponent} action=${e.associatedAction} src=${e.sourceClass}")
        }

        File("build/analysis-dump.txt").writeText(sb.toString())
        assert(true) // Always pass, just dump data
    }

    @Test
    fun `dump bytecode for investigation`() {
        val apkStream = javaClass.getResourceAsStream("/testapp.apk")
            ?: error("testapp.apk not found")
        val tempFile = File.createTempFile("testapp", ".apk")
        tempFile.deleteOnExit()
        apkStream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }

        val dexFile = DexFileFactory.loadDexContainer(tempFile, Opcodes.forApi(36))
        val sb = StringBuilder()

        val targets = mapOf(
            "DeepLinkHandler" to "handleDeepLink",
            "DeepLinkActivity" to "onCreate",
            "BasicProvider" to "query",
            "BulkParamReader" to "readParams",
            "ApiKeyClient" to "fetchWithApiKey",
            "FakeSecrets" to "<clinit>",
            "PrefixValueActivity" to "onCreate",
            "StringSwitchActivity" to "onCreate"
        )

        for (dexName in dexFile.dexEntryNames) {
            val dexEntry = dexFile.getEntry(dexName) ?: continue
            for (classDef in dexEntry.dexFile.classes) {
                val matchTarget = targets.entries.find { classDef.type.contains(it.key) } ?: continue
                sb.appendLine("=== ${classDef.type} ===")
                for (method in classDef.methods) {
                    val impl = method.implementation ?: continue
                    if (method.name != matchTarget.value) continue
                    sb.appendLine("  method: ${method.name} regs=${impl.registerCount}")
                    val instructions = impl.instructions.toList()
                    for ((i, instr) in instructions.withIndex()) {
                        val detail = buildString {
                            append("    [$i] ${instr.opcode}")
                            if (instr is OneRegisterInstruction) append(" v${instr.registerA}")
                            if (instr is TwoRegisterInstruction) append(", v${instr.registerB}")
                            if (instr is NarrowLiteralInstruction) append(" #${instr.narrowLiteral}")
                            if (instr is ReferenceInstruction) {
                                when (val ref = instr.reference) {
                                    is MethodReference -> append(" ${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString(",")})")
                                    is StringReference -> append(" \"${ref.string}\"")
                                    is FieldReference -> append(" ${ref.definingClass}->${ref.name}:${ref.type}")
                                }
                            }
                            if (instr is OffsetInstruction) append(" +${instr.codeOffset}")
                        }
                        sb.appendLine(detail)
                    }
                }
            }
        }

        File("build/bytecode-investigation.txt").writeText(sb.toString())
    }
}
