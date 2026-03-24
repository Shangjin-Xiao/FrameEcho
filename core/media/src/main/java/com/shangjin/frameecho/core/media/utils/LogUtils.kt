package com.shangjin.frameecho.core.media.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Centralized utility for sanitized logging in the core:media module.
 *
 * Checks ApplicationInfo.FLAG_DEBUGGABLE to log full stack traces in debug builds
 * while sanitizing to only the exception class name in release builds, preventing
 * PII leakage (e.g., file paths in exception messages).
 */
object LogUtils {

    private fun isDebuggable(context: Context): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Log a warning message with an optional exception.
     * Sanitizes the exception output in release builds.
     */
    fun w(context: Context, tag: String, message: String, exception: Throwable? = null) {
        if (exception != null) {
            if (isDebuggable(context)) {
                Log.w(tag, message, exception)
            } else {
                Log.w(tag, "$message: ${exception.javaClass.simpleName}")
            }
        } else {
            Log.w(tag, message)
        }
    }

    /**
     * Log an error message with an optional exception.
     * Sanitizes the exception output in release builds.
     */
    fun e(context: Context, tag: String, message: String, exception: Throwable? = null) {
        if (exception != null) {
            if (isDebuggable(context)) {
                Log.e(tag, message, exception)
            } else {
                Log.e(tag, "$message: ${exception.javaClass.simpleName}")
            }
        } else {
            Log.e(tag, message)
        }
    }
}
