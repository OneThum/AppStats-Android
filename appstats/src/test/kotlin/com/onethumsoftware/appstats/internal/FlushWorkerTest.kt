// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FlushWorkerTest {
    /**
     * An expedited request runs as a foreground service below API 31, where WorkManager
     * calls `getForegroundInfo()` — which this worker doesn't implement, so it crashed the
     * host app with `IllegalStateException("Not implemented")`.
     */
    @Test
    fun `flush request is not expedited`() {
        assertThat(FlushWorker.buildRequest().workSpec.expedited).isFalse()
    }

    /**
     * Hosts upgrading from <= 1.0.20 can still hold a persisted expedited request naming
     * the old class. It must not resolve, or WorkManager runs it and the host crashes.
     */
    @Test
    fun `the pre-1_0_21 worker class name no longer resolves`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.onethumsoftware.appstats.internal.BackgroundFlushWorker")
        }
    }
}
