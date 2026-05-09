// EventCollector unit tests. Uses Robolectric for a real Context (for StorageManager).
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventCollectorTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun newCollector(
        maxQueueSize: Int = EventCollector.MAX_QUEUE_SIZE,
        batchSize: Int = EventCollector.BATCH_SIZE,
    ): Pair<EventCollector, StorageManager> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = StorageManager(context)
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        val network =
            NetworkManager(
                apiKey = "as_test_key",
                baseUrl = server.url("").toString().trimEnd('/'),
                client = client,
                sleeper = { /* no-op */ },
            )
        return EventCollector(storage, network, scope, maxQueueSize, batchSize) to storage
    }

    private fun event(id: String) =
        Event(
            id = id,
            timestamp = "2026-05-09T19:00:00.000Z",
            type = EventType.CUSTOM,
            name = id,
            sessionId = "session-1",
            appVersion = "1.0.0",
            buildNumber = "1",
            deviceModel = "Pixel",
            osVersion = "14",
            platform = "android",
            screenResolution = "1080x2400",
            locale = "en_US",
            timezone = "UTC",
            sdkVersion = "0.1.0",
        )

    @Test
    fun `queue evicts oldest when over capacity`() =
        runTest {
            val (collector, _) = newCollector(maxQueueSize = 3, batchSize = 100)
            collector.collect(event("a"))
            collector.collect(event("b"))
            collector.collect(event("c"))
            collector.collect(event("d"))
            assertThat(collector.queueSize()).isEqualTo(3)
        }

    @Test
    fun `successful flush clears the queue`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(202))
            val (collector, storage) = newCollector(batchSize = 100)
            collector.collect(event("a"))
            val result = collector.flush()
            assertThat(result).isInstanceOf(EventCollector.FlushResult.Sent::class.java)
            assertThat(collector.queueSize()).isEqualTo(0)
            assertThat(storage.loadEvents()).isEmpty()
        }

    @Test
    fun `failed flush re-queues events`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            val (collector, storage) = newCollector(batchSize = 100)
            collector.collect(event("a"))
            val before = collector.queueSize()
            val result = collector.flush()
            assertThat(result).isInstanceOf(EventCollector.FlushResult.Failed::class.java)
            // Re-queued at the head, so the queue size is preserved.
            assertThat(collector.queueSize()).isEqualTo(before)
            // Persisted snapshot still contains the event for retry on next launch.
            assertThat(storage.loadEvents().map { it.id }).contains("a")
        }

    @Test
    fun `client error drops the batch and clears storage if no remainder`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            val (collector, storage) = newCollector(batchSize = 100)
            collector.collect(event("a"))
            val result = collector.flush()
            assertThat(result).isInstanceOf(EventCollector.FlushResult.Dropped::class.java)
            assertThat(collector.queueSize()).isEqualTo(0)
            assertThat(storage.loadEvents()).isEmpty()
        }

    @Test
    fun `flush returns NothingToSend when queue is empty`() =
        runTest {
            val (collector, _) = newCollector()
            assertThat(collector.flush()).isEqualTo(EventCollector.FlushResult.NothingToSend)
        }
}
