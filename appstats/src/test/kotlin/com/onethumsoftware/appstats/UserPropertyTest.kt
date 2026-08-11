// Regression coverage for setUserProperty (attachment + cross-restart persistence).
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.onethumsoftware.appstats.internal.Event
import com.onethumsoftware.appstats.internal.EventValue
import com.onethumsoftware.appstats.internal.StorageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserPropertyTest {

    @After
    fun tearDown() {
        AppStats.resetForTests()
    }

    /** Regression test: a sticky property set before `track()` must attach to the event. */
    @Test
    fun `setUserProperty is included on tracked events`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            AppStats.configure(context, "as_test_user_property", autoTrackScreens = false)
            AppStats.setUserProperty("subscription_tier", "premium")
            AppStats.track("unit_test_event")

            val event = waitForEvent(StorageManager(context), "unit_test_event")

            val value = event.properties?.get("subscription_tier")
            assertThat(value).isInstanceOf(EventValue.StringValue::class.java)
            assertThat((value as EventValue.StringValue).value).isEqualTo("premium")
        }

    /** Sticky properties are persisted, so they must survive a cold relaunch. */
    @Test
    fun `setUserProperty survives a cold relaunch`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val storage = StorageManager(context)

            AppStats.configure(context, "as_test_user_property_restart", autoTrackScreens = false)
            AppStats.setUserProperty("subscription_tier", "premium")
            waitFor { storage.loadUserProperties().containsKey("subscription_tier") }

            // Simulate process death + relaunch: tear down the singleton and configure again
            // against the same on-disk storage.
            AppStats.resetForTests()
            AppStats.configure(context, "as_test_user_property_restart", autoTrackScreens = false)
            AppStats.track("relaunch_event")

            val event = waitForEvent(storage, "relaunch_event")
            val value = event.properties?.get("subscription_tier")
            assertThat(value).isInstanceOf(EventValue.StringValue::class.java)
            assertThat((value as EventValue.StringValue).value).isEqualTo("premium")
        }

    /** Polls the on-disk event queue since `track()`/`setUserProperty()` are fire-and-forget. */
    private suspend fun waitForEvent(
        storage: StorageManager,
        name: String,
        timeoutMs: Long = 5000,
    ): Event {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            storage.loadEvents().firstOrNull { it.name == name }?.let { return it }
            delay(50)
        }
        error("Timed out waiting for event \"$name\" to persist")
    }

    private suspend fun waitFor(
        timeoutMs: Long = 5000,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        error("Timed out waiting for condition")
    }
}
