package com.droidprobe.testapp.network

import okhttp3.Request

/**
 * Tests inline const-string sensitive key → addHeader flow.
 * Unlike ApiKeyClient which reads from FakeSecrets via sget-object,
 * this class defines the key as an inline const-string.
 * SensitiveStringFlowAnalyzer should detect the const-string → addHeader flow
 * and associate the key with the URL used in the same method.
 */
class InlineKeyClient {

    fun chargeCard(): Request {
        val key = "sk_test_InlineTestKeyForDroidProbe000"
        return Request.Builder()
            .url("https://payments.example.com/v1/charges")
            .addHeader("X-Stripe-Key", key)
            .build()
    }
}
