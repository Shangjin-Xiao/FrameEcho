package com.shangjin.frameecho.core.media.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Centralized utility for sanitized logging in the core:media module.
 *
 * This utility ensures that logs do not inadvertently leak Personally Identifiable Information (PII)
 * or sensitive system paths in production environments. It distinguishes between debug and release
 * builds by checking the `ApplicationInfo.FLAG_DEBUGGABLE` flag:
 * - **Debug Builds**: Full stack traces are logged to facilitate troubleshooting.
 * - **Release Builds**: Only the exception's class name (e.g., "IOException") is logged,
 *   stripping away potentially sensitive details in the exception message.
 */
object LogUtils {

    /**
     * Checks if the application is currently in a debuggable state.
     *
     * @param context The context used to retrieve application info and flags.
     * @return True if the `FLAG_DEBUGGABLE` is set, false otherwise.
     */
    private fun isDebuggable(context: Context): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Log a warning message with an optional exception.
     *
     * In release builds (where `FLAG_DEBUGGABLE` is not set), the exception details are
     * sanitized to only include its simple class name to prevent PII leakage.
     *
     * @param context The context used to determine the build type.
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An optional exception to log.
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
     *
     * In release builds (where `FLAG_DEBUGGABLE` is not set), the exception details are
     * sanitized to only include its simple class name to prevent PII leakage.
     *
     * @param context The context used to determine the build type.
     * @param tag Used to identify the source of a log message.
     * @param message The message you would like logged.
     * @param exception An optional exception to log.
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
