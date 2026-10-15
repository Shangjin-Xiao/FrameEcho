package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat

/**
 * Safely get an integer from MediaFormat.
 */
internal fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
    return try { getInteger(key) } catch (_: Exception) { default }
}
