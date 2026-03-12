package com.droidprobe.testapp.network

import android.util.Log

/**
 * Tests Sentry DSN sensitive string detection.
 * StringConstantCollector should detect the DSN matching the "Sentry DSN" pattern.
 * The DSN is only logged, NOT passed to any HTTP sink.
 */
class SentryConfigClient {

    fun initSentry() {
        val dsn = "https://abc123def456abc123def456abc123de@o123456.ingest.sentry.io/1234567"
        Log.d("Sentry", "DSN length: ${dsn.length}")
    }
}
