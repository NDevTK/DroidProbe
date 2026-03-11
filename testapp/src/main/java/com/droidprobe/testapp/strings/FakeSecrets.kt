package com.droidprobe.testapp.strings

/**
 * Fake sensitive string constants for testing StringConstantCollector detection.
 * These are NOT real credentials — they are deliberately fake test patterns.
 * Using @JvmField val (not const val) to ensure strings appear as field initializers in DEX.
 */
object FakeSecrets {
    // AWS key pattern (starts with AKIA, 20 chars)
    @JvmField val AWS_KEY = "AKIAIOSFODNN7EXAMPLE"

    // Google API key pattern (starts with AIzaSy)
    @JvmField val GOOGLE_KEY = "AIzaSyA-fake-key-for-testing-only-xxxxx"

    // Stripe secret key pattern (starts with sk_live_)
    @JvmField val STRIPE_KEY = "sk_live_FakeStripeKeyForTestingXXXXX"

    // JWT pattern (3 base64 segments separated by dots)
    @JvmField val JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

    // Firebase URL pattern
    @JvmField val FIREBASE_URL = "https://my-test-project.firebaseio.com"

    // GitHub token pattern (starts with ghp_)
    @JvmField val GITHUB_TOKEN = "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

    // MongoDB connection string pattern
    @JvmField val MONGO_URI = "mongodb+srv://user:pass@cluster.mongodb.net/db"

    // Force class initialization so strings appear in DEX
    fun init() {
        AWS_KEY.length
    }
}
