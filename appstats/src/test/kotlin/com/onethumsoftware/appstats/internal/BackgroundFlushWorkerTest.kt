// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundFlushWorkerTest {
    /**
     * An expedited request runs as a foreground service below API 31, where WorkManager
     * calls `getForegroundInfo()` — which this worker doesn't implement, so it crashed the
     * host app with `IllegalStateException("Not implemented")` each time it backgrounded.
     */
    @Test
    fun `flush request is not expedited`() {
        assertThat(BackgroundFlushWorker.buildRequest().workSpec.expedited).isFalse()
    }
}
