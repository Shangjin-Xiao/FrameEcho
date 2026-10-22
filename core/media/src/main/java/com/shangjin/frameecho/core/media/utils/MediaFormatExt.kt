package com.shangjin.frameecho.core.media.utils

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/**
 * Safely get an integer from MediaFormat.
 */
internal fun MediaFormat.getIntegerSafe(key: String, default: Int): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return default
    }
    return try {
        getInteger(key)
    } catch (_: Exception) {
        default
    }
}

/**
 * Safely get an integer from MediaFormat or null.
 */
internal fun MediaFormat.getIntegerSafeOrNull(key: String): Int? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return null
    }
    return try {
        getInteger(key)
    } catch (_: Exception) {
        null
    }
}

/**
 * Safely get a long from MediaFormat or null.
 */
internal fun MediaFormat.getLongSafeOrNull(key: String): Long? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return null
    }
    return try {
        getLong(key)
    } catch (_: Exception) {
        null
    }
}

/**
 * Safely get a string from MediaFormat or null.
 */
internal fun MediaFormat.getStringSafeOrNull(key: String): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return null
    }
    return try {
        getString(key)
    } catch (_: Exception) {
        null
    }
}

/**
 * Safely get a ByteBuffer from MediaFormat or null.
 */
internal fun MediaFormat.getByteBufferSafeOrNull(key: String): ByteBuffer? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !containsKey(key)) {
        return null
    }
    return try {
        getByteBuffer(key)
    } catch (_: Exception) {
        null
    }
}
