package com.droidprobe.testapp.fileprovider

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * Tests FileProviderExtractor detection of getUriForFile() calls
 * and File constructor resolution.
 */
class FileHelper(private val context: Context) {

    fun shareReport() {
        val file = File(context.filesDir, "report.pdf")
        val uri = FileProvider.getUriForFile(
            context,
            "com.droidprobe.testapp.fileprovider",
            file
        )
        // Use uri
        uri.toString()
    }

    fun shareExport() {
        val exportDir = File(context.cacheDir, "export")
        val file = File(exportDir, "data.csv")
        val uri = FileProvider.getUriForFile(
            context,
            "com.droidprobe.testapp.fileprovider",
            file
        )
        uri.toString()
    }
}
