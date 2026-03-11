package com.droidprobe.testapp.network

import android.util.Log
import com.droidprobe.testapp.strings.FakeSecrets

/**
 * Negative test: reads FakeSecrets.STRIPE_KEY via sget-object but only
 * passes it to Log.d() — NOT an HTTP sink.
 * SensitiveStringFlowAnalyzer should NOT associate STRIPE_KEY with any URL
 * because the tainted value never reaches addHeader/header/addQueryParameter.
 */
class KeyLogger {

    fun logKey() {
        val key = FakeSecrets.STRIPE_KEY
        Log.d("Keys", "Current key: $key")
    }
}
