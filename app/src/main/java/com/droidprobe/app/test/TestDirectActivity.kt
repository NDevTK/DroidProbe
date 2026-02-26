package com.droidprobe.app.test

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout

/**
 * Exported activity that reads extras directly in its own class.
 * Tests direct class → extras detection.
 */
class TestDirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userId = intent.getStringExtra("user_id")
        val messageCount = intent.getIntExtra("message_count", 0)
        val isAdmin = intent.getBooleanExtra("is_admin", false)
        val score = intent.getFloatExtra("score", 0f)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        layout.addView(TextView(this).apply {
            text = "Direct extras: user_id=$userId, message_count=$messageCount, " +
                    "is_admin=$isAdmin, score=$score"
            textSize = 16f
        })
        setContentView(layout)
    }
}
