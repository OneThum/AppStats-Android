// Internal logger. Mirrors the Swift SDK's `Logger` enum.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.util.Log

internal object Logger {
    private const val TAG = "AppStats"

    /**
     * `info` and `warning` are gated by [debugLoggingEnabled]; `error` is always emitted
     * so misconfiguration is visible in release builds. This matches the Swift SDK behavior
     * in `sdk/Sources/AppStats/AppStats.swift`.
     */
    @Volatile
    var debugLoggingEnabled: Boolean = false

    fun info(message: String) {
        if (debugLoggingEnabled) Log.i(TAG, message)
    }

    fun warning(message: String) {
        if (debugLoggingEnabled) Log.w(TAG, message)
    }

    fun warning(
        message: String,
        throwable: Throwable,
    ) {
        if (debugLoggingEnabled) Log.w(TAG, message, throwable)
    }

    fun error(message: String) {
        Log.e(TAG, message)
    }

    fun error(
        message: String,
        throwable: Throwable,
    ) {
        Log.e(TAG, message, throwable)
    }
}
