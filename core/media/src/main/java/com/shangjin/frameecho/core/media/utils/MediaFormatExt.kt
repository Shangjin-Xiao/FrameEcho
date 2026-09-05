package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat

/**
 * Safely get an integer from MediaFormat.
 */
internal fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
    return runCatching { getInteger(key) }.getOrDefault(default)
}
