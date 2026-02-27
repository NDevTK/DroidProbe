package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.SensitiveString

private data class SecretPattern(val category: String, val regex: Regex)

private val SECRET_PATTERNS = listOf(
    SecretPattern("AWS Key", Regex("""AKIA[0-9A-Z]{16}""")),
    SecretPattern("Google API Key", Regex("""AIza[0-9A-Za-z\-_]{35}""")),
    SecretPattern("Firebase URL", Regex("""https?://[a-z0-9._-]+\.firebase(io|app)\.com""")),
    SecretPattern("GCP Service Account", Regex("""[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+\.iam\.gserviceaccount\.com""")),
    SecretPattern("Private Key", Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----""")),
    SecretPattern("Bearer Token", Regex("""(?i)bearer\s+[a-zA-Z0-9._\-]{20,}""")),
    SecretPattern("Slack Webhook", Regex("""https://hooks\.slack\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[a-zA-Z0-9]+""")),
    SecretPattern("GitHub Token", Regex("""gh[pousr]_[A-Za-z0-9_]{36,}""")),
)

class StringConstantCollector {

    private val allStrings = mutableSetOf<String>()

    fun process(classDef: DexBackedClassDef) {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (instruction in impl.instructions) {
                if (instruction is ReferenceInstruction) {
                    val ref = instruction.reference
                    if (ref is StringReference) {
                        allStrings.add(ref.string)
                    }
                }
            }
        }
    }

    fun getContentUriStrings(): List<String> =
        allStrings.filter { it.startsWith("content://") }.sorted()

    fun getDeepLinkUriStrings(): List<String> =
        allStrings.filter { s ->
            val schemeEnd = s.indexOf("://")
            schemeEnd > 0 && !s.startsWith("content://") && !s.startsWith("http://") &&
                    !s.startsWith("https://") && !s.startsWith("file://") &&
                    s.length > schemeEnd + 3 && s[schemeEnd + 3].isLetterOrDigit() &&
                    isValidUri(s)
        }.sorted()

    fun getColumnNameCandidates(): List<String> =
        allStrings.filter { isLikelyColumnName(it) }.sorted()

    fun getIntentActionCandidates(): List<String> =
        allStrings.filter { isLikelyIntentAction(it) }.sorted()

    fun getIntentExtraKeyCandidates(): List<String> =
        allStrings.filter { isLikelyExtraKey(it) }.sorted()

    fun getAllUrlStrings(): List<String> =
        allStrings.filter { s ->
            (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) &&
                    isValidUri(s)
        }.sorted()

    fun getSensitiveStrings(): List<SensitiveString> {
        val results = mutableListOf<SensitiveString>()
        val seen = mutableSetOf<String>()
        for (s in allStrings) {
            if (s.length < 10) continue
            for (pattern in SECRET_PATTERNS) {
                if (pattern.regex.containsMatchIn(s) && seen.add(s)) {
                    results.add(SensitiveString(value = s, category = pattern.category))
                    break
                }
            }
        }
        return results.sortedBy { it.category }
    }

    fun getAllStrings(): Set<String> = allStrings

    private fun isValidUri(s: String): Boolean {
        return try {
            val uri = java.net.URI(s)
            uri.scheme != null && uri.host != null && uri.host.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun isLikelyColumnName(s: String): Boolean {
        if (s.length < 2 || s.length > 100) return false
        if (s.contains(' ') || s.contains('/') || s.contains(':')) return false
        // Common column name patterns: _id, _count, snake_case identifiers
        return s == "_id" || s == "_count" ||
                (s.matches(Regex("^[a-z][a-z0-9_]*$")) && s.contains('_'))
    }

    private fun isLikelyIntentAction(s: String): Boolean {
        return s.contains(".action.") ||
                s.contains(".intent.action.") ||
                s.startsWith("android.intent.action.")
    }

    private fun isLikelyExtraKey(s: String): Boolean {
        return s.contains(".extra.") ||
                s.contains(".EXTRA_") ||
                s.contains("_EXTRA") ||
                s.startsWith("android.intent.extra.")
    }
}
