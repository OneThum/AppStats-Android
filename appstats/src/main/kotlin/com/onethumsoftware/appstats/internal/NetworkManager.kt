// HTTP transport. Mirrors sdk/Sources/AppStats/Network/NetworkManager.swift behavior.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Sealed network error type used by [NetworkManager.sendEvents]. Mirrors
 * `sdk/Sources/AppStats/Network/NetworkManager.swift`'s `NetworkError`.
 */
internal sealed class NetworkError : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)

    object InvalidResponse : NetworkError("Invalid response from server")

    object InBackoff : NetworkError("Network manager is in circuit-breaker backoff")

    object CompressionFailed : NetworkError("Failed to deflate request body")

    class ClientError(
        val code: Int,
    ) : NetworkError("Client error: $code")

    class ServerError(
        val code: Int,
    ) : NetworkError("Server error: $code")

    class TransportError(
        cause: Throwable,
    ) : NetworkError("Transport error", cause)

    class UnexpectedStatus(
        val code: Int,
    ) : NetworkError("Unexpected status code: $code")
}

internal class NetworkManager(
    private val apiKey: String,
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = SdkJson,
    private val now: () -> Long = System::currentTimeMillis,
    // Deliberately injectable so tests can run instantly.
    private val sleeper: suspend (Long) -> Unit = { ms -> delay(ms) },
) {
    private val mutex = Mutex()
    private var consecutiveFailures = 0
    private var inBackoff = false
    private var backoffUntilMs: Long? = null

    /**
     * Send a batch of events. Throws [NetworkError] on hard failures so callers can
     * decide whether to re-queue.
     */
    @Throws(NetworkError::class)
    suspend fun sendEvents(events: List<Event>): Unit =
        withContext(Dispatchers.IO) {
            sendEventsWithRetry(events, retryCount = 0)
        }

    @Throws(NetworkError::class)
    private suspend fun sendEventsWithRetry(
        events: List<Event>,
        retryCount: Int,
    ) {
        mutex.withLock {
            // Check circuit breaker
            backoffUntilMs?.let { until ->
                if (now() < until) throw NetworkError.InBackoff
                inBackoff = false
                backoffUntilMs = null
                consecutiveFailures = 0
            }
        }

        val payload =
            runCatching {
                val jsonBytes =
                    json
                        .encodeToString(ListSerializer(Event.serializer()), events)
                        .toByteArray(Charsets.UTF_8)
                deflate(jsonBytes)
            }.getOrElse {
                handleFailure()
                throw NetworkError.CompressionFailed
            }

        val url = baseUrl.trimEnd('/') + INGEST_PATH
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader(HEADER_API_KEY, apiKey)
                .addHeader(HEADER_SDK_VERSION, SdkInfo.version)
                .addHeader(HEADER_SDK_PLATFORM, SdkInfo.SDK_PLATFORM_HEADER)
                .addHeader("Content-Encoding", "deflate")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

        try {
            client.newCall(request).execute().use { response ->
                when (val code = response.code) {
                    in 200..299 -> {
                        // Success — reset failure counter. 202 is what the server returns.
                        mutex.withLock { consecutiveFailures = 0 }
                    }
                    in 400..499 -> {
                        // Client-side problem (auth/schema). Drop the batch; do not retry.
                        mutex.withLock { consecutiveFailures = 0 }
                        throw NetworkError.ClientError(code)
                    }
                    in 500..599 -> {
                        handleFailure()
                        throw NetworkError.ServerError(code)
                    }
                    else -> {
                        handleFailure()
                        throw NetworkError.UnexpectedStatus(code)
                    }
                }
            }
        } catch (e: NetworkError) {
            throw e
        } catch (e: IOException) {
            if (shouldRetry(e) && retryCount < MAX_RETRIES) {
                val delayMs = (1L shl retryCount) * SECOND_MS
                sleeper(delayMs)
                sendEventsWithRetry(events, retryCount + 1)
                return
            }
            handleFailure()
            throw NetworkError.TransportError(e)
        }
    }

    private fun shouldRetry(e: IOException): Boolean =
        when (e) {
            is SocketTimeoutException -> true
            is UnknownHostException -> true
            is SocketException -> true
            else -> false
        }

    private suspend fun handleFailure() {
        mutex.withLock {
            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                inBackoff = true
                val multiplier = 1L shl (consecutiveFailures - MAX_CONSECUTIVE_FAILURES)
                val seconds = minOf(BASE_BACKOFF_SECONDS * multiplier, MAX_BACKOFF_SECONDS)
                backoffUntilMs = now() + seconds * SECOND_MS
            }
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(data) }
        return out.toByteArray()
    }

    internal companion object {
        const val INGEST_PATH: String = "/v1/ingest"
        const val HEADER_API_KEY: String = "X-AS-Key"
        const val HEADER_SDK_VERSION: String = "X-AS-SDK-Version"
        const val HEADER_SDK_PLATFORM: String = "X-AS-SDK-Platform"
        const val MAX_RETRIES: Int = 2
        const val MAX_CONSECUTIVE_FAILURES: Int = 10
        const val BASE_BACKOFF_SECONDS: Long = 60
        const val MAX_BACKOFF_SECONDS: Long = 3600
        const val SECOND_MS: Long = 1000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                // Match the Swift SDK's aggressive timeouts for mobile networks.
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false) // we own retries
                .build()
    }
}
