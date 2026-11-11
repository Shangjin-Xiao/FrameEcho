package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat
import android.os.Build

/**
 * Safely get an integer from MediaFormat.
 * ⚡ Bolt Optimization: Uses containsKey (API 29+) to avoid expensive NoSuchFieldError exceptions
 * when the key is missing, and replaces allocating runCatching block with direct try-catch
 * to prevent unnecessary object allocations in hot paths.
 * Expected Impact: Eliminates Result<> allocations and exception throws on older APIs.
 */
internal fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) return default
    return try {
        getInteger(key)
    } catch (e: Exception) {
        default
    }
}

internal fun MediaFormat.getIntegerOrNullSafe(key: String): Int? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) return null
    return try {
        getInteger(key)
    } catch (e: Exception) {
        null
    }
}

internal fun MediaFormat.getLongOrNullSafe(key: String): Long? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) return null
    return try {
        getLong(key)
    } catch (e: Exception) {
        null
    }
}

internal fun MediaFormat.getStringOrNullSafe(key: String): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) return null
    return try {
        getString(key)
    } catch (e: Exception) {
        null
    }
}

internal fun MediaFormat.getByteBufferOrNullSafe(key: String): java.nio.ByteBuffer? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) return null
    return try {
        getByteBuffer(key)
    } catch (e: Exception) {
        null
    }
}
