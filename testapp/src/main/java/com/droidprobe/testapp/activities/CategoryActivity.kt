package com.droidprobe.testapp.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Tests Intent.addCategory() detection.
 * IntentExtraExtractor should detect categories added via addCategory().
 */
class CategoryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchWithCategory()
    }

    fun launchWithCategory() {
        val intent = Intent()
        intent.addCategory("com.droidprobe.testapp.category.PREMIUM")
        intent.addCategory("android.intent.category.ALTERNATIVE")
        Log.d("Category", "Launching with categories")
        startActivity(intent)
    }
}
