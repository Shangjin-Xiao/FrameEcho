package com.shangjin.frameecho.core.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Common file utilities.
 */
object FileUtils {

    /**
     * Get the display name of a file from its URI.
     */
    fun getFileName(context: Context, uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        } else null
                    }
                }
                "file" -> uri.lastPathSegment
                else -> uri.lastPathSegment
            }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Get file size from a URI.
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.SIZE), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                        } else 0L
                    } ?: 0L
                }
                "file" -> uri.path?.let { java.io.File(it).length() } ?: 0L
                else -> 0L
            }
        } catch (_: SecurityException) {
            0L
        }
    }

    /**
     * Check if a URI points to a video file based on MIME type.
     */
    fun isVideoUri(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: return false
            mimeType.startsWith("video/")
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Format file size to human-readable string.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 0) return "$bytes B"
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    /**
     * Format duration in milliseconds to human-readable string.
     */
    fun formatDuration(durationMs: Long): String {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeDurationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Format duration in milliseconds with millisecond precision.
     */
    fun formatDurationWithMillis(durationMs: Long): String {
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeDurationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = safeDurationMs % 1000

        return if (hours > 0) {
            "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        } else {
            "%d:%02d.%03d".format(minutes, seconds, millis)
        }
    }
}
