package com.shangjin.frameecho.core.common

/**
 * Common file utilities.
 */
object FileUtils {

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
