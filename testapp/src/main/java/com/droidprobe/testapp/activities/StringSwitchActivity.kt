package com.droidprobe.testapp.activities

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Tests string switch (Kotlin when) value detection.
 * Kotlin compiles when(string) to hashCode() → packed/sparse-switch → equals() branches.
 * ForwardValueScanner.scanStringSwitchValues() should detect "food", "drink", "dessert".
 * Exported with action: com.droidprobe.testapp.action.SWITCH
 */
class StringSwitchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val category = intent.getStringExtra("category")
        when (category) {
            "food" -> Log.d("Switch", "Food selected")
            "drink" -> Log.d("Switch", "Drink selected")
            "dessert" -> Log.d("Switch", "Dessert selected")
        }
    }
}
