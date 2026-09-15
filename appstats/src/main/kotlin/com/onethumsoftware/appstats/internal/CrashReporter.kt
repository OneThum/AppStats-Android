// Crash reporting via the JVM's UncaughtExceptionHandler.
// Mirrors sdk/Sources/AppStats/Tracking/CrashReporter.swift in concept, with Android-native
// mechanism instead of POSIX signal handlers.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import java.io.File

internal class CrashReporter(
    context: Context,
    private val sessionIdProvider: () -> String,
) {
    private val markerFile: File =
        File(File(context.filesDir, StorageManager.STORAGE_DIR_NAME).apply { mkdirs() }, MARKER_FILE_NAME)

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installed = false

    fun install() {
        if (installed) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeMarker(thread, throwable)
            } catch (_: Throwable) {
                // Last-resort: never throw from inside an UEH.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        installed = true
    }

    /**
     * Returns crash details from the previous launch, or null if there was none.
     * Deletes the marker on read so it isn't reported twice.
     */
    fun consumePreviousCrash(): PreviousCrash? {
        if (!markerFile.exists()) return null
        return try {
            val raw = markerFile.readText(Charsets.UTF_8)
            markerFile.delete()
            parseMarker(raw)
        } catch (t: Throwable) {
            Logger.warning("Failed to read crash marker", t)
            markerFile.delete()
            null
        }
    }

    /**
     * Exposed at `internal` (rather than `private`) so tests in this module can drive it
     * directly, without installing a real [Thread.UncaughtExceptionHandler] and mutating JVM-
     * global state.
     */
    internal fun writeMarker(
        thread: Thread,
        throwable: Throwable,
    ) {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val payload =
            buildString {
                appendLine("CRASH_TIMESTAMP_MS: ${System.currentTimeMillis()}")
                appendLine("THREAD: ${thread.name}")
                appendLine("EXCEPTION: ${throwable.javaClass.name}")
                appendLine("MESSAGE: ${(throwable.message ?: "").take(MAX_MESSAGE_LENGTH)}")
                appendLine("SESSION_ID: ${sessionIdProvider()}")
                appendLine("STACK_TRACE:")
                append(sw.toString().take(MAX_STACK_TRACE_LENGTH))
            }
        markerFile.writeText(payload, Charsets.UTF_8)
    }

    private fun parseMarker(raw: String): PreviousCrash {
        var timestampMs = 0L
        var thread = ""
        var exception = ""
        var message = ""
        var sessionId = ""
        val stack = StringBuilder()
        var inStack = false
        for (line in raw.lineSequence()) {
            when {
                inStack -> {
                    stack.append(line).append('\n')
                }
                line.startsWith("CRASH_TIMESTAMP_MS:") ->
                    timestampMs = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                line.startsWith("THREAD:") -> thread = line.substringAfter(":").trim()
                line.startsWith("EXCEPTION:") -> exception = line.substringAfter(":").trim()
                line.startsWith("MESSAGE:") -> message = line.substringAfter(":").trim()
                line.startsWith("SESSION_ID:") -> sessionId = line.substringAfter(":").trim()
                line.startsWith("STACK_TRACE:") -> inStack = true
            }
        }
        return PreviousCrash(timestampMs, thread, exception, message, sessionId, stack.toString().trimEnd())
    }

    data class PreviousCrash(
        val timestampMs: Long,
        val thread: String,
        val exception: String,
        val message: String,
        val sessionId: String,
        val stackTrace: String,
    )

    private companion object {
        const val MARKER_FILE_NAME = "crash.marker"

        // `throwable.message` and its stack trace are app-authored strings, not something this
        // SDK controls the shape of: host code routinely builds exception messages by
        // interpolating live state (a value, a URL, a malformed input), which can carry
        // incidental PII. A chained exception's "Caused by:" sections repeat each cause's own
        // message inside the rendered trace too, so capping only MESSAGE would not bound that.
        // There's no way to redact this safely, but bounding both limits how much can leave the
        // device in any one crash report. Mirrors the Swift SDK's CrashReporter caps.
        const val MAX_MESSAGE_LENGTH = 1000
        const val MAX_STACK_TRACE_LENGTH = 4000
    }
}
