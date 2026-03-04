package com.droidprobe.app.analysis.dex

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.droidprobe.app.data.model.SensitiveString

private data class SecretPattern(val category: String, val regex: Regex)

private val SECRET_PATTERNS = listOf(
    // Cloud provider keys
    SecretPattern("AWS Key", Regex("""AKIA[0-9A-Z]{16}""")),
    SecretPattern("Google API Key", Regex("""AIza[0-9A-Za-z\-_]{35}""")),
    SecretPattern("Firebase URL", Regex("""https?://[a-z0-9._-]+\.firebase(io|app)\.com""")),
    SecretPattern("GCP Service Account", Regex("""[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+\.iam\.gserviceaccount\.com""")),

    // Payment keys
    SecretPattern("Stripe Key", Regex("""(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{20,}""")),
    SecretPattern("Square Key", Regex("""sq0[ac][ts]p-[A-Za-z0-9\-_]{20,}""")),

    // Auth / tokens
    SecretPattern("Private Key", Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----""")),
    SecretPattern("Bearer Token", Regex("""(?i)bearer\s+[a-zA-Z0-9._\-]{20,}""")),
    SecretPattern("JWT", Regex("""eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+""")),

    // Chat / messaging
    SecretPattern("Slack Webhook", Regex("""https://hooks\.slack\.com/services/T[A-Z0-9]+/B[A-Z0-9]+/[a-zA-Z0-9]+""")),
    SecretPattern("Slack Token", Regex("""xox[bpars]-[A-Za-z0-9\-]{10,}""")),
    SecretPattern("Telegram Bot Token", Regex("""[0-9]{8,10}:[A-Za-z0-9_-]{35}""")),
    SecretPattern("Discord Token", Regex("""[MN][A-Za-z0-9]{23,}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,}""")),

    // VCS / CI
    SecretPattern("GitHub Token", Regex("""gh[pousr]_[A-Za-z0-9_]{36,}""")),
    SecretPattern("GitLab Token", Regex("""glpat-[A-Za-z0-9\-_]{20,}""")),

    // SaaS / APIs
    SecretPattern("Twilio Key", Regex("""SK[0-9a-fA-F]{32}""")),
    SecretPattern("SendGrid Key", Regex("""SG\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}""")),
    SecretPattern("Mapbox Token", Regex("""[sp]k\.eyJ[A-Za-z0-9_-]{50,}""")),
    SecretPattern("Algolia Key", Regex("""(?i)algolia[^"]{0,20}['"][0-9a-f]{32}['"]""")),
    SecretPattern("Sentry DSN", Regex("""https://[a-f0-9]{32}@[a-z0-9.]+\.sentry\.io/[0-9]+""")),

    // Database connection strings (should never be in a mobile app)
    SecretPattern("MongoDB URI", Regex("""mongodb(?:\+srv)?://[^\s"']+@[^\s"']+""")),
    SecretPattern("Database URI", Regex("""(?:postgres|mysql|mariadb)://[^\s"']+:[^\s"']+@[^\s"']+""")),
)

class StringConstantCollector {

    // Maps string → first class it appeared in
    private val allStrings = mutableMapOf<String, String>()

    fun process(classDef: DexBackedClassDef) {
        val className = classDef.type
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (instruction in impl.instructions) {
                if (instruction is ReferenceInstruction) {
                    val ref = instruction.reference
                    if (ref is StringReference) {
                        allStrings.putIfAbsent(ref.string, className)
                    }
                }
            }
        }
    }

    fun getContentUriStrings(): List<String> =
        allStrings.keys.filter { it.startsWith("content://") && it.length > 10 }.sorted()

    fun getDeepLinkUriStrings(): List<String> =
        allStrings.keys.filter { s ->
            val schemeEnd = s.indexOf("://")
            schemeEnd > 0 && !s.startsWith("content://") && !s.startsWith("http://") &&
                    !s.startsWith("https://") && !s.startsWith("file://") &&
                    s.length > schemeEnd + 3 && s[schemeEnd + 3].isLetterOrDigit() &&
                    isValidUri(s)
        }.sorted()

    fun getColumnNameCandidates(): List<String> =
        allStrings.keys.filter { isLikelyColumnName(it) }.sorted()

    fun getIntentActionCandidates(): List<String> =
        allStrings.keys.filter { isLikelyIntentAction(it) }.sorted()

    fun getIntentExtraKeyCandidates(): List<String> =
        allStrings.keys.filter { isLikelyExtraKey(it) }.sorted()

    fun getAllUrlStrings(): List<String> =
        allStrings.keys.filter { s ->
            (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) &&
                    isValidUri(s)
        }.sorted()

    fun getSensitiveStrings(): List<SensitiveString> {
        // Build reverse index: class → URLs in that class
        val urlsByClass = mutableMapOf<String, MutableList<String>>()
        for ((s, cls) in allStrings) {
            if ((s.startsWith("http://") || s.startsWith("https://")) && s.length > 10) {
                urlsByClass.getOrPut(cls) { mutableListOf() }.add(s)
            }
        }

        val results = mutableListOf<SensitiveString>()
        val seen = mutableSetOf<String>()
        for ((s, sourceClass) in allStrings) {
            if (s.length < 10) continue
            for (pattern in SECRET_PATTERNS) {
                if (pattern.regex.containsMatchIn(s) && seen.add(s)) {
                    val urls = urlsByClass[sourceClass]?.filter { it != s } ?: emptyList()
                    results.add(SensitiveString(
                        value = s,
                        category = pattern.category,
                        sourceClass = sourceClass,
                        associatedUrls = urls
                    ))
                    break
                }
            }
        }
        return results.sortedBy { it.category }
    }

    fun getAllStrings(): Set<String> = allStrings.keys

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
