package com.shangjin.frameecho.core.media.utils

/**
 * Utility functions for EXIF data formatting.
 *
 * Centralises helpers shared by [MetadataExtractor][com.shangjin.frameecho.core.media.metadata.MetadataExtractor],
 * [MetadataWriter][com.shangjin.frameecho.core.media.metadata.MetadataWriter] and
 * [FrameExporter][com.shangjin.frameecho.core.media.export.FrameExporter].
 */
object ExifUtils {

    /**
     * Format a double value as a rational number string for EXIF attributes.
     *
     * Uses a fixed denominator of 10 000 so the result is always a valid
     * EXIF rational (`numerator/denominator`).
     *
     * Example: `12.5` → `"125000/10000"`
     */
    fun formatRational(value: Double): String {
        val absValue = kotlin.math.abs(value)
        val numerator = Math.round(absValue * 10000)
        return "$numerator/10000"
    }

    /**
     * Normalize an exposure-time string to fraction format suitable for EXIF.
     *
     * Handles the following cases:
     * - `null` / blank → `null`
     * - Already fractional `"1/60"` → `"1/60"` (returned after validation)
     * - Decimal seconds `"0.016"` → `"1/63"` (converted to nearest fraction)
     * - Values ≥ 1 s → decimal string (e.g. `"2.0"`)
     * - Non-positive or unparseable → `null`
     */
    fun normalizeExposureTime(exposure: String?): String? {
        val raw = exposure?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Already in fractional form — validate before returning
        if (raw.contains('/')) {
            val parts = raw.split('/')
            if (parts.size == 2) {
                val num = parts[0].toLongOrNull()
                val den = parts[1].toLongOrNull()
                if (num != null && den != null && num > 0 && den > 0) {
                    return raw
                }
            }
            return null
        }

        val asDouble = raw.toDoubleOrNull() ?: return null
        if (asDouble <= 0.0 || asDouble.isNaN() || asDouble.isInfinite()) return null

        return if (asDouble < 1.0) {
            val denominator = Math.round(1.0 / asDouble).coerceAtLeast(1L)
            "1/$denominator"
        } else {
            asDouble.toString()
        }
    }
}
