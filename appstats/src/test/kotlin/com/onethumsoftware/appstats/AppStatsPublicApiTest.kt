// Smoke tests for public AppStats façade behavior.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppStatsPublicApiTest {
    @After
    fun tearDown() {
        AppStats.resetForTests()
    }

    @Test
    fun `isConfigured is false before configure`() {
        AppStats.resetForTests()
        assertThat(AppStats.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured is true immediately after configure returns`() {
        AppStats.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        AppStats.configure(ctx, "as_test_configure_only", autoTrackScreens = false)
        assertThat(AppStats.isConfigured()).isTrue()
    }
}
