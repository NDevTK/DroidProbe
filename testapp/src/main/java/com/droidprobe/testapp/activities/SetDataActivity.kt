package com.droidprobe.testapp.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * Tests Intent.setData() and Intent.setDataAndType() detection.
 * IntentExtraExtractor should detect the data URIs passed to these methods.
 */
class SetDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent()
        intent.setData(Uri.parse("content://com.droidprobe.testapp.basic/items"))

        val intent2 = Intent()
        intent2.setDataAndType(
            Uri.parse("content://com.droidprobe.testapp.basic/export"),
            "application/json"
        )

        Log.d("SetData", "intent=$intent intent2=$intent2")
    }
}
