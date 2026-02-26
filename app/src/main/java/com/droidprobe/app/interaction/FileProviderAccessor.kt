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
        try {
            val mimeType = contentResolver.getType(uri)
            var name: String? = null
            var size: Long? = null

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }

            // Try to read a preview
            var preview: String? = null
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = ByteArray(2048)
                    val read = stream.read(bytes)
                    if (read > 0) {
                        preview = String(bytes, 0, read, Charsets.UTF_8)
                    }
                }
            } catch (_: Exception) {
                // Can't read content, but URI might still be valid
            }

            FileInfo(
                uri = uri,
                name = name,
                size = size,
                mimeType = mimeType,
                isAccessible = true,
                previewText = preview,
                error = null
            )
        } catch (e: SecurityException) {
            FileInfo(uri, null, null, null, false, null, "Access denied: ${e.message}")
        } catch (e: Exception) {
            FileInfo(uri, null, null, null, false, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
