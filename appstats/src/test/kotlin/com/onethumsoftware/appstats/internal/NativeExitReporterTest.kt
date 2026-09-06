// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.R])
class NativeExitReporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storageDir get() = File(context.filesDir, StorageManager.STORAGE_DIR_NAME)

    @Before
    fun clearStorage() {
        storageDir.deleteRecursively()
    }

    private fun reporter(
        sessionId: String = "session-current",
        exits: List<NativeExitReporter.ExitRecord>,
    ) = NativeExitReporter(context, { sessionId }, { exits })

    private fun nativeCrash(
        timestampMs: Long,
        pid: Int = 4321,
        status: Int = 11,
        description: String = "Segmentation fault",
        trace: String = "",
    ) = NativeExitReporter.ExitRecord(
        reason = NativeExitReporter.REASON_CRASH_NATIVE,
        status = status,
        pid = pid,
        timestampMs = timestampMs,
        description = description,
        readTrace = { trace },
    )

    /**
     * The platform remembers deaths from long before the SDK was integrated. Replaying that
     * backlog would look like a burst of crashes on the day AppStats was added.
     */
    @Test
    fun `first launch adopts the watermark and reports nothing`() =
        runTest {
            val exits = reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            assertThat(exits).isEmpty()
            assertThat(File(storageDir, NativeExitReporter.WATERMARK_FILE_NAME).readText()).isEqualTo("1000")
        }

    @Test
    fun `a native crash after the watermark is reported once and never again`() =
        runTest {
            // Establish the watermark.
            reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            val records = listOf(nativeCrash(timestampMs = 2_000), nativeCrash(timestampMs = 1_000))
            val first = reporter(exits = records).consumePreviousNativeExits()
            assertThat(first.map { it.timestampMs }).containsExactly(2_000L)

            val second = reporter(exits = records).consumePreviousNativeExits()
            assertThat(second).isEmpty()
        }

    @Test
    fun `signal number is resolved to the same name the Swift SDK reports`() =
        runTest {
            reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            val exits =
                reporter(
                    exits =
                        listOf(
                            nativeCrash(timestampMs = 2_000, status = 11),
                            nativeCrash(timestampMs = 3_000, status = 6),
                            nativeCrash(timestampMs = 4_000, status = 7),
                        ),
                ).consumePreviousNativeExits()

            // SIGBUS is 7 on Linux and 10 on Darwin; this must use the Linux numbering.
            assertThat(exits.map { it.exception }).containsExactly("SIGSEGV", "SIGABRT", "SIGBUS").inOrder()
        }

    @Test
    fun `an ANR is reported as ANR rather than a signal name`() =
        runTest {
            reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            val anr =
                NativeExitReporter.ExitRecord(
                    reason = NativeExitReporter.REASON_ANR,
                    status = 0,
                    pid = 99,
                    timestampMs = 2_000,
                    description = "Input dispatching timed out",
                    readTrace = { "main (state=BLOCKED)" },
                )

            val exits = reporter(exits = listOf(anr)).consumePreviousNativeExits()

            assertThat(exits).hasSize(1)
            assertThat(exits.single().exception).isEqualTo(NativeExitReporter.ANR_EXCEPTION)
            assertThat(exits.single().stackTrace).isEqualTo("main (state=BLOCKED)")
        }

    /**
     * A JVM crash already produces a marker via CrashReporter's UncaughtExceptionHandler.
     * Reporting the platform's record of it too would double-count every Kotlin crash.
     */
    @Test
    fun `a JVM crash is not reported here because the exception handler already covers it`() =
        runTest {
            reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            val jvmCrash =
                NativeExitReporter.ExitRecord(
                    reason = 4, // REASON_CRASH
                    status = 1,
                    pid = 99,
                    timestampMs = 2_000,
                    description = "java.lang.IllegalStateException",
                    readTrace = { "" },
                )

            assertThat(reporter(exits = listOf(jvmCrash)).consumePreviousNativeExits()).isEmpty()
        }

    @Test
    fun `low-memory and user-requested deaths are not crashes`() =
        runTest {
            reporter(exits = listOf(nativeCrash(timestampMs = 1_000))).consumePreviousNativeExits()

            val benign =
                listOf(3, 10, 11).mapIndexed { index, reason ->
                    NativeExitReporter.ExitRecord(
                        reason = reason,
                        status = 0,
                        pid = 99,
                        timestampMs = 2_000L + index,
                        description = "not a fault",
                        readTrace = { "" },
                    )
                }

            assertThat(reporter(exits = benign).consumePreviousNativeExits()).isEmpty()
        }

    /**
     * The crash must be attributed to the session that actually died, not to the session that
     * happens to be live when the record is read — the same guarantee the Swift SDK gets by
     * writing SESSION_ID into its crash marker.
     */
    @Test
    fun `a crash is attributed to the session of the process that died`() =
        runTest {
            val dyingPid = Process.myPid()

            // Launch 1: establishes the watermark and records this pid against its session.
            reporter(sessionId = "session-that-died", exits = listOf(nativeCrash(timestampMs = 1_000)))
                .consumePreviousNativeExits()

            // Launch 2: the platform reports that pid died natively.
            val exits =
                reporter(
                    sessionId = "session-after-restart",
                    exits = listOf(nativeCrash(timestampMs = 2_000, pid = dyingPid)),
                ).consumePreviousNativeExits()

            assertThat(exits.single().sessionId).isEqualTo("session-that-died")
        }

    @Test
    fun `an unmatched pid yields no session so the caller can fall back to the live one`() =
        runTest {
            reporter(sessionId = "session-that-died", exits = listOf(nativeCrash(timestampMs = 1_000)))
                .consumePreviousNativeExits()

            val exits =
                reporter(
                    sessionId = "session-after-restart",
                    exits = listOf(nativeCrash(timestampMs = 2_000, pid = Process.myPid() + 1_000)),
                ).consumePreviousNativeExits()

            assertThat(exits.single().sessionId).isEmpty()
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class NativeExitReporterLegacyApiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `does nothing at all on API 29`() =
        runTest {
            val exits =
                NativeExitReporter(
                    context,
                    { "session" },
                    {
                        error("the platform query must not be reached below API 30")
                    },
                ).consumePreviousNativeExits()

            assertThat(exits).isEmpty()
        }
}
