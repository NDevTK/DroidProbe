package com.droidprobe.testapp.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Tests all 18 intent extra types for IntentExtraExtractor detection.
 * Exported with action: com.droidprobe.testapp.action.DIRECT
 */
class DirectExtraActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // String types
        val userName = intent.getStringExtra("user_name")

        // Numeric types
        val age = intent.getIntExtra("age", 0)
        val balance = intent.getLongExtra("balance", 0L)
        val rating = intent.getFloatExtra("rating", 0f)
        val preciseValue = intent.getDoubleExtra("precise_value", 0.0)
        val flags = intent.getByteExtra("flags", 0)
        val priority = intent.getShortExtra("priority", 0)
        val initial = intent.getCharExtra("initial", ' ')

        // Boolean
        val isActive = intent.getBooleanExtra("is_active", false)

        // Array types
        val names = intent.getStringArrayExtra("names")
        val ids = intent.getIntArrayExtra("ids")

        // ArrayList types
        val tagList = intent.getStringArrayListExtra("tag_list")
        val idList = intent.getIntegerArrayListExtra("id_list")

        // Parcelable types
        val data = intent.getParcelableExtra("data", Intent::class.java)
        val items = intent.getParcelableArrayExtra("items")
        val parcelList = intent.getParcelableArrayListExtra<Intent>("parcel_list")

        // Serializable
        val serialData = intent.getSerializableExtra("serial_data")

        // Bundle
        val extraBundle = intent.getBundleExtra("extra_bundle")

        Log.d("DirectExtra", "userName=$userName age=$age balance=$balance " +
            "rating=$rating precise=$preciseValue flags=$flags priority=$priority " +
            "initial=$initial active=$isActive names=${names?.size} ids=${ids?.size} " +
            "tagList=${tagList?.size} idList=${idList?.size} data=$data " +
            "items=${items?.size} parcelList=${parcelList?.size} serial=$serialData " +
            "bundle=$extraBundle")
    }
}
