// Reporting for process deaths the JVM UncaughtExceptionHandler can never observe.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reports native (NDK/ART) crashes and ANRs.
 *
 * [CrashReporter] installs a `Thread.UncaughtExceptionHandler`, which only ever sees Throwables
 * on the JVM side. A SIGSEGV in native code, or an ANR, kills the process without one, so those
 * deaths were previously invisible to AppStats — the gap this closes, giving Android the same
 * "the app died and here is why" coverage the Swift SDK gets from its POSIX signal handler.
 *
 * The mechanism is deliberately *not* a signal handler. Android records every process death
 * itself, so this reads the platform's own record on the next launch
 * ([ActivityManager.getHistoricalProcessExitReasons]) instead of installing an in-process
 * handler. That keeps the SDK free of native code, and — more importantly — free of the
 * async-signal-safety hazards that make the Swift-side equivalent so delicate: everything here
 * runs on an ordinary thread, after the fact.
 *
 * The trade is an API floor. `getHistoricalProcessExitReasons` is API 30+, so native crashes on
 * Android 7–10 remain unreported; on those versions this class does nothing at all.
 */
internal class NativeExitReporter(
    private val context: Context,
    private val sessionIdProvider: () -> String,
    private val platformExits: () -> List<ExitRecord> = { queryPlatformExits(context) },
) {
    private val storageDir: File = File(context.filesDir, StorageManager.STORAGE_DIR_NAME)
    private val watermarkFile: File = File(storageDir, WATERMARK_FILE_NAME)
    private val sessionPidFile: File = File(storageDir, SESSION_PID_FILE_NAME)

    /**
     * One process death, already mapped off the platform type so the reporting logic can be
     * tested without fabricating an [ApplicationExitInfo] (which has no public constructor).
     */
    internal data class ExitRecord(
        val reason: Int,
        val status: Int,
        val pid: Int,
        val timestampMs: Long,
        val description: String,
        val readTrace: () -> String,
    )

    internal data class NativeExit(
        val exception: String,
        val message: String,
        val timestampMs: Long,
        val stackTrace: String,
        val sessionId: String,
    )

    /**
     * Returns process deaths not yet reported, oldest first, and advances the bookkeeping so the
     * same death is never reported twice.
     *
     * Call exactly once per launch, at startup: it also rewrites the pid → session record, so a
     * second call would attribute the *next* crash to this process rather than to the one that
     * actually died.
     */
    suspend fun consumePreviousNativeExits(): List<NativeExit> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()

            // Read before recording: the file still holds the *previous* process's identity.
            val previous = readPreviousSessionRecord()
            recordCurrentSession()

            runCatching { collectUnreported(previous) }
                .onFailure { Logger.warning("Could not read historical process exits", it) }
                .getOrDefault(emptyList())
        }

    private fun collectUnreported(previous: SessionRecord?): List<NativeExit> {
        val records = platformExits()
        if (records.isEmpty()) return emptyList()

        val newest = records.maxOf { it.timestampMs }
        val watermark = readWatermark()

        // No watermark means this is the first launch that has ever run this code. The platform
        // remembers deaths from long before AppStats was integrated, and replaying that backlog
        // would show up as a burst of crashes on the day the SDK was added, all attributed to a
        // session that never existed. Adopt the current high-water mark and report nothing.
        if (watermark == null) {
            writeWatermark(newest)
            return emptyList()
        }

        val unreported =
            records
                .filter { it.timestampMs > watermark && it.reason in REPORTABLE_REASONS }
                .sortedBy { it.timestampMs }
                .map { it.toNativeExit(previous) }

        writeWatermark(maxOf(newest, watermark))
        return unreported
    }

    private fun ExitRecord.toNativeExit(previous: SessionRecord?): NativeExit =
        NativeExit(
            exception = if (reason == REASON_ANR) ANR_EXCEPTION else signalName(status),
            message = description,
            timestampMs = timestampMs,
            stackTrace = runCatching { readTrace() }.getOrDefault(""),
            // Only trust the stored session when it belongs to the process that actually died;
            // an unmatched pid falls back to the live session at the call site.
            sessionId = previous?.takeIf { it.pid == pid }?.sessionId.orEmpty(),
        )

    // MARK: - Bookkeeping

    private data class SessionRecord(
        val pid: Int,
        val sessionId: String,
    )

    private fun readPreviousSessionRecord(): SessionRecord? =
        runCatching {
            if (!sessionPidFile.exists()) return@runCatching null
            val lines = sessionPidFile.readText(Charsets.UTF_8).lineSequence().toList()
            val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return@runCatching null
            val session = lines.getOrNull(1)?.trim().orEmpty()
            if (session.isEmpty()) null else SessionRecord(pid, session)
        }.getOrNull()

    private fun recordCurrentSession() {
        runCatching {
            ensureDirectory()
            sessionPidFile.writeText("${Process.myPid()}\n${sessionIdProvider()}\n", Charsets.UTF_8)
        }.onFailure { Logger.warning("Could not record session for native-exit attribution", it) }
    }

    private fun readWatermark(): Long? =
        runCatching {
            if (!watermarkFile.exists()) null else watermarkFile.readText(Charsets.UTF_8).trim().toLongOrNull()
        }.getOrNull()

    private fun writeWatermark(timestampMs: Long) {
        runCatching {
            ensureDirectory()
            watermarkFile.writeText(timestampMs.toString(), Charsets.UTF_8)
        }.onFailure { Logger.warning("Could not persist native-exit watermark", it) }
    }

    private fun ensureDirectory() {
        if (!storageDir.exists()) storageDir.mkdirs()
    }

    internal companion object {
        const val WATERMARK_FILE_NAME: String = "native_exit_watermark"
        const val SESSION_PID_FILE_NAME: String = "session_pid"
        const val ANR_EXCEPTION: String = "ANR"

        /** Matches [ApplicationExitInfo.REASON_ANR] without needing the API-30 type at runtime. */
        const val REASON_ANR: Int = 6

        /** Matches [ApplicationExitInfo.REASON_CRASH_NATIVE]. */
        const val REASON_CRASH_NATIVE: Int = 5

        /**
         * Deliberately excludes `REASON_CRASH` (JVM crash): [CrashReporter]'s
         * UncaughtExceptionHandler already writes a marker for those, and reporting both would
         * double-count every Kotlin crash. Also excludes ordinary deaths — user-requested exits,
         * low-memory kills and the like — which are not faults and would swamp the crash signal.
         */
        val REPORTABLE_REASONS: Set<Int> = setOf(REASON_CRASH_NATIVE, REASON_ANR)

        /** How far back to look. The platform itself keeps at most ~16 records per package. */
        const val MAX_EXITS_EXAMINED: Int = 16

        /** Cap on a captured trace, so one enormous ANR dump cannot dominate the event queue. */
        const val MAX_TRACE_BYTES: Int = 16 * 1024

        /**
         * Linux signal numbers — deliberately not Darwin's. The two differ (`SIGBUS` is 7 here
         * and 10 on Darwin), so the Swift SDK's constants must not be reused for this mapping
         * even though the resulting names are intentionally identical across both SDKs.
         */
        private const val SIGILL = 4
        private const val SIGABRT = 6
        private const val SIGBUS = 7
        private const val SIGFPE = 8
        private const val SIGSEGV = 11
        private const val SIGPIPE = 13

        fun signalName(status: Int): String =
            when (status) {
                SIGILL -> "SIGILL"
                SIGABRT -> "SIGABRT"
                SIGBUS -> "SIGBUS"
                SIGFPE -> "SIGFPE"
                SIGSEGV -> "SIGSEGV"
                SIGPIPE -> "SIGPIPE"
                else -> "SIGNAL_$status"
            }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun queryPlatformExits(context: Context): List<ExitRecord> {
            val manager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return emptyList()
            return manager
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS_EXAMINED)
                .map { info ->
                    ExitRecord(
                        reason = info.reason,
                        status = info.status,
                        pid = info.pid,
                        timestampMs = info.timestamp,
                        description = info.description.orEmpty(),
                        readTrace = { info.readTraceText() },
                    )
                }
        }

        /**
         * Reads the platform's trace for this exit, if there is one worth keeping.
         *
         * For an ANR that is the plain-text thread dump. For a native crash on API 31+ it is a
         * *protobuf-encoded* tombstone, which would be meaningless as an event property — so
         * anything containing a NUL byte is dropped rather than shipped as mojibake. The
         * `description` field still carries the useful summary in that case.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        private fun ApplicationExitInfo.readTraceText(): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ""
            return runCatching {
                traceInputStream
                    ?.use { stream ->
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(DEFAULT_CHUNK_BYTES)
                        while (buffer.size() < MAX_TRACE_BYTES) {
                            val read = stream.read(chunk)
                            if (read <= 0) break
                            buffer.write(chunk, 0, minOf(read, MAX_TRACE_BYTES - buffer.size()))
                        }
                        val bytes = buffer.toByteArray()
                        if (bytes.any { it == 0.toByte() }) "" else String(bytes, Charsets.UTF_8)
                    }.orEmpty()
            }.getOrDefault("")
        }

        private const val DEFAULT_CHUNK_BYTES = 8 * 1024
    }
}
