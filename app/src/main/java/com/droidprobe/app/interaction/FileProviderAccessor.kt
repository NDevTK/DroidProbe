package com.droidprobe.app.interaction

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileProviderAccessor(private val contentResolver: ContentResolver) {

    data class FileInfo(
        val uri: Uri,
        val name: String?,
        val size: Long?,
        val mimeType: String?,
        val isAccessible: Boolean,
        val previewText: String?,
        val error: String?
    )

    suspend fun probeUri(uri: Uri): FileInfo = withContext(Dispatchers.IO) {
        // Check if the provider authority is reachable at all
        val authority = uri.authority ?: ""
        val client = contentResolver.acquireContentProviderClient(authority)
        if (client == null) {
            return@withContext FileInfo(uri, null, null, null, false, null, "Provider not found")
        }
        client.close()

        // Provider exists — try to read file metadata individually
        val mimeType = try { contentResolver.getType(uri) } catch (_: Exception) { null }

        var name: String? = null
        var size: Long? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) { }

        var preview: String? = null
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(2048)
                val read = stream.read(bytes)
                if (read > 0) {
                    preview = String(bytes, 0, read, Charsets.UTF_8)
                }
            }
        } catch (_: Exception) { }

        FileInfo(
            uri = uri,
            name = name,
            size = size,
            mimeType = mimeType,
            isAccessible = true,
            previewText = preview,
            error = null
        )
    }
}
