// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashReporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val storageDir get() = File(context.filesDir, StorageManager.STORAGE_DIR_NAME)

    @Before
    fun clearStorage() {
        storageDir.deleteRecursively()
    }

    private fun reporter(sessionId: String = "session-1") = CrashReporter(context) { sessionId }

    /**
     * Regression test for a PII-exposure bug found in the same audit as the Swift SDK's
     * equivalent `CrashReporter`: `throwable.message` was captured verbatim with no bound. App
     * code routinely builds exception messages by interpolating live state — user input, a URL,
     * a malformed value — so an unbounded capture could carry arbitrary app data off the device
     * in a crash report despite neither SDK adding PII of its own. `writeMarker` is exercised
     * directly (it's `internal` for exactly this purpose) so the test doesn't have to install a
     * real `Thread.UncaughtExceptionHandler` and mutate JVM-global state.
     */
    @Test
    fun `an oversized exception message is capped at capture time`() {
        val hugeMessage = "x".repeat(5_000)
        val throwable = RuntimeException(hugeMessage)

        val target = reporter()
        target.writeMarker(Thread.currentThread(), throwable)

        val crash = target.consumePreviousCrash()
        assertThat(crash).isNotNull()
        // Pinned to exactly the cap, not just "under some limit" — proves the cap actually bit
        // rather than the message coincidentally being short.
        assertThat(crash!!.message.length).isEqualTo(1000)
    }

    /**
     * A chained exception's rendered stack trace repeats each cause's own message in its
     * "Caused by:" section. Capping MESSAGE alone would not bound that — the whole rendered
     * trace must be capped too, which this pins down with a cause message big enough to blow
     * past the cap on its own.
     */
    @Test
    fun `a chained exception's cause message is bounded via the stack trace cap`() {
        val cause = RuntimeException("y".repeat(5_000))
        val throwable = RuntimeException("outer", cause)

        val target = reporter()
        target.writeMarker(Thread.currentThread(), throwable)

        val crash = target.consumePreviousCrash()
        assertThat(crash).isNotNull()
        assertThat(crash!!.stackTrace.length).isAtMost(4000)
    }

    @Test
    fun `a normal-sized crash round-trips untouched and is consumed only once`() {
        val throwable = IllegalStateException("boom")
        val target = reporter(sessionId = "session-xyz")

        target.writeMarker(Thread.currentThread(), throwable)

        val crash = target.consumePreviousCrash()
        assertThat(crash).isNotNull()
        assertThat(crash!!.message).isEqualTo("boom")
        assertThat(crash.exception).isEqualTo("java.lang.IllegalStateException")
        assertThat(crash.sessionId).isEqualTo("session-xyz")
        assertThat(crash.stackTrace).contains("IllegalStateException")

        // Deletes the marker on read so the same crash isn't reported twice.
        assertThat(target.consumePreviousCrash()).isNull()
    }
}
