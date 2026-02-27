package com.droidprobe.app.test

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object TestFileProviderInit {

    private const val AUTHORITY = "com.droidprobe.test.fileprovider"

    fun ensureSampleFile(context: Context) {
        val dir = File(context.filesDir, "test")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "test_file.txt")
        if (!file.exists()) {
            file.writeText("DroidProbe test file.\nThis verifies FileProvider access is working.")
        }
        val uri: Uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        Log.d("TestFileProvider", "Test URI: $uri")
    }
}
