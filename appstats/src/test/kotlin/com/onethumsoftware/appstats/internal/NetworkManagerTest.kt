// Network layer tests — pure JVM, no Android dependency required.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

class NetworkManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var clock: AtomicClock

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        clock = AtomicClock()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newManager(): NetworkManager {
        // Tight client with no SDK-managed retries (NetworkManager owns retries).
        val client =
            OkHttpClient
                .Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build()
        return NetworkManager(
            apiKey = "as_test_key",
            baseUrl = server.url("").toString().trimEnd('/'),
            client = client,
            now = clock::now,
            sleeper = { /* no-op for tests */ },
        )
    }

    @Test
    fun `success path sends one request and resets failure counter`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(202))
            val manager = newManager()

            manager.sendEvents(listOf(sampleEvent()))

            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertThat(recorded.path).isEqualTo("/v1/ingest")
            assertThat(recorded.getHeader(NetworkManager.HEADER_API_KEY)).isEqualTo("as_test_key")
            assertThat(recorded.getHeader(NetworkManager.HEADER_SDK_PLATFORM)).isEqualTo("kotlin")
            assertThat(recorded.getHeader("Content-Encoding")).isEqualTo("deflate")

            // Body must be deflate-compressed JSON.
            val raw = recorded.body.readByteArray()
            val inflated = inflate(raw)
            assertThat(inflated).contains("session_start")
            assertThat(inflated).contains("\"platform\":\"android\"")
        }

    @Test
    fun `4xx fails fast without retry`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401))
            val manager = newManager()

            try {
                manager.sendEvents(listOf(sampleEvent()))
                fail("Expected ClientError")
            } catch (e: NetworkError.ClientError) {
                assertThat(e.code).isEqualTo(401)
            }
            // Exactly one request — no retries on 4xx.
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `5xx surfaces ServerError after non-IOException response`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            val manager = newManager()
            try {
                manager.sendEvents(listOf(sampleEvent()))
                fail("Expected ServerError")
            } catch (e: NetworkError.ServerError) {
                assertThat(e.code).isEqualTo(503)
            }
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `circuit breaker opens after 10 consecutive failures and rejects further sends`() =
        runTest {
            // Queue 10 failures so the breaker opens.
            repeat(10) { server.enqueue(MockResponse().setResponseCode(503)) }
            val manager = newManager()
            repeat(10) {
                try {
                    manager.sendEvents(listOf(sampleEvent()))
                } catch (_: NetworkError) {
                    // expected
                }
            }
            // Next send should be rejected by the circuit breaker without hitting the network.
            val priorCount = server.requestCount
            try {
                manager.sendEvents(listOf(sampleEvent()))
                fail("Expected InBackoff")
            } catch (e: NetworkError) {
                assertThat(e).isInstanceOf(NetworkError.InBackoff::class.java)
            }
            assertThat(server.requestCount).isEqualTo(priorCount)

            // Advance the clock past the backoff window — sender should attempt again.
            clock.advance(TimeUnit.HOURS.toMillis(2))
            server.enqueue(MockResponse().setResponseCode(202))
            manager.sendEvents(listOf(sampleEvent()))
            assertThat(server.requestCount).isEqualTo(priorCount + 1)
        }

    private fun sampleEvent(): Event =
        Event(
            id = "00000000-0000-4000-8000-000000000001",
            timestamp = "2026-05-09T19:00:00.000Z",
            type = EventType.SESSION_START,
            sessionId = "00000000-0000-4000-8000-000000000002",
            appVersion = "1.0.0",
            buildNumber = "1",
            deviceModel = "Pixel 7",
            osVersion = "14",
            platform = "android",
            screenResolution = "1080x2400",
            locale = "en_US",
            timezone = "UTC",
            sdkVersion = "0.1.0",
        )

    private fun inflate(compressed: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(compressed)
        val buf = ByteArray(8 * 1024)
        val out = ByteArrayOutputStreamSafe()
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Tiny shim so we don't pull in a third-party stream library. */
    private class ByteArrayOutputStreamSafe : java.io.ByteArrayOutputStream() {
        override fun toByteArray(): ByteArray = super.toByteArray()
    }
}

private class AtomicClock {
    @Volatile private var nowMs: Long = 1_700_000_000_000L

    fun now(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}
