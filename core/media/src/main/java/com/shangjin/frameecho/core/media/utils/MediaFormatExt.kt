package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat
import android.os.Build

/**
 * Safely get an integer from MediaFormat.
 */
internal fun MediaFormat.getIntegerSafe(
    key: String,
    default: Int,
    sdkInt: Int = Build.VERSION.SDK_INT
): Int {
    if (sdkInt >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return default
    }
    return try {
        getInteger(key)
    } catch (_: Exception) {
        default
    }
}
