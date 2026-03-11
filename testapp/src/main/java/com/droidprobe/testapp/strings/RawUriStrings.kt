package com.droidprobe.testapp.strings

/**
 * Raw content URI and URL string constants for testing StringConstantCollector.
 * Using @JvmField val (not const val) to ensure strings appear as field initializers in DEX.
 */
object RawUriStrings {
    // Content URIs
    @JvmField val DOC_URI = "content://com.droidprobe.testapp.raw/documents"
    @JvmField val CONTACT_URI = "content://com.droidprobe.testapp.raw/contacts"

    // Deep link URIs (custom schemes)
    @JvmField val DEEP_LINK_1 = "myapp://deeplink/home"
    @JvmField val DEEP_LINK_2 = "customscheme://action/open"

    // HTTP/HTTPS URLs
    @JvmField val WEBHOOK_URL = "https://hooks.example.com/webhook"
    @JvmField val DOCS_URL = "https://docs.example.com/api"

    // Force class initialization so strings appear in DEX
    fun init() {
        DOC_URI.length
    }
}
