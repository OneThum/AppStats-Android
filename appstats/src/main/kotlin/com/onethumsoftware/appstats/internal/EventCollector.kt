// Event queueing & batching. Mirrors sdk/Sources/AppStats/Events/EventCollector.swift.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal class EventCollector(
    private val storage: StorageManager,
    private val network: NetworkManager,
    private val scope: CoroutineScope,
    private val maxQueueSize: Int = MAX_QUEUE_SIZE,
    private val batchSize: Int = BATCH_SIZE,
    private val sendBatchSize: Int = SEND_BATCH_SIZE,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val queue = ArrayDeque<Event>()
    private var hydrated = false

    /**
     * Enqueue an event. Triggers an async flush when the queue reaches [batchSize].
     */
    suspend fun collect(event: Event) {
        ensureHydrated()
        mutex.withLock {
            if (queue.size >= maxQueueSize) {
                // Evict oldest to make room.
                queue.removeFirst()
            }
            queue.addLast(event)
        }
        // Persist a snapshot outside the mutex; storage has its own lock.
        storage.saveEvents(snapshot())

        if (queue.size >= batchSize) {
            scope.launch { runCatching { flush() } }
        }
    }

    /**
     * Flush up to [sendBatchSize] events. Re-queues on transient failure; drops on
     * client-error (4xx) per the protocol contract.
     */
    suspend fun flush(): FlushResult {
        ensureHydrated()
        val toSend =
            mutex.withLock {
                if (queue.isEmpty()) return FlushResult.NothingToSend
                val take = minOf(queue.size, sendBatchSize)
                List(take) { queue.removeFirst() }
            }

        return try {
            network.sendEvents(toSend)
            // On success, persist the (possibly smaller) remainder and recurse if
            // there's more to send.
            val remainder = snapshot()
            if (remainder.isEmpty()) {
                storage.clearEvents()
            } else {
                storage.saveEvents(remainder)
                scope.launch { runCatching { flush() } }
            }
            FlushResult.Sent(toSend.size)
        } catch (clientErr: NetworkError.ClientError) {
            // 4xx → drop and persist remainder.
            Logger.warning("Dropping batch after client error ${clientErr.code}")
            val remainder = snapshot()
            if (remainder.isEmpty()) storage.clearEvents() else storage.saveEvents(remainder)
            FlushResult.Dropped(toSend.size, clientErr)
        } catch (t: Throwable) {
            // Transient failure → re-queue at the head and persist.
            mutex.withLock {
                toSend.asReversed().forEach { queue.addFirst(it) }
                while (queue.size > maxQueueSize) queue.removeFirst()
            }
            storage.saveEvents(snapshot())
            FlushResult.Failed(t)
        }
    }

    /** Drain everything from disk (deduplicated by event id) on first use. */
    private suspend fun ensureHydrated() {
        if (hydrated) return
        mutex.withLock {
            if (hydrated) return
            val cutoff = nowMs() - staleAfterMs
            val persisted =
                storage
                    .loadEvents()
                    .filter { event ->
                        val ts = parseTimestampMs(event.timestamp) ?: return@filter true
                        ts >= cutoff
                    }
            persisted.take(maxQueueSize).forEach(queue::addLast)
            hydrated = true
            if (persisted.size > maxQueueSize) {
                Logger.warning("Dropped ${persisted.size - maxQueueSize} stale persisted events over budget")
            }
        }
    }

    private suspend fun snapshot(): List<Event> = mutex.withLock { queue.toList() }

    /** Test/inspection helper. */
    suspend fun queueSize(): Int = mutex.withLock { queue.size }

    sealed interface FlushResult {
        object NothingToSend : FlushResult

        data class Sent(
            val count: Int,
        ) : FlushResult

        data class Dropped(
            val count: Int,
            val cause: Throwable,
        ) : FlushResult

        data class Failed(
            val cause: Throwable,
        ) : FlushResult
    }

    internal companion object {
        const val MAX_QUEUE_SIZE: Int = 500
        const val BATCH_SIZE: Int = 20
        const val SEND_BATCH_SIZE: Int = 100
        val STALE_AFTER_MS: Long = TimeUnit.HOURS.toMillis(48)

        // ISO-8601 timestamp parsing. Extended ISO-8601 with optional fractional seconds.
        private val isoParser =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

        private val isoParserFallback =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }

        private fun parseTimestampMs(raw: String): Long? =
            runCatching { isoParser.parse(raw)?.time }.getOrNull()
                ?: runCatching { isoParserFallback.parse(raw)?.time }.getOrNull()
    }
}
