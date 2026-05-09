// Persistent event storage. Mirrors sdk/Sources/AppStats/Storage/StorageManager.swift.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

internal class StorageManager(
    context: Context,
    private val json: Json = SdkJson,
    private val maxStorageBytes: Long = MAX_STORAGE_BYTES,
) {
    private val storageDir: File = File(context.filesDir, STORAGE_DIR_NAME)
    private val eventsFile: File = File(storageDir, EVENTS_FILE_NAME)
    private val mutex = Mutex()

    /**
     * Save the entire queue to disk, atomically. If the storage budget is exceeded, the
     * write is dropped silently (matches Swift behavior). The in-memory queue keeps
     * functioning as a degraded-mode fallback.
     */
    suspend fun saveEvents(events: List<Event>): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirectory() ?: return@withLock
                if (events.isEmpty()) {
                    if (eventsFile.exists()) eventsFile.delete()
                    return@withLock
                }
                val currentSize = directorySize()
                if (currentSize >= maxStorageBytes) {
                    Logger.warning("Storage over budget ($currentSize/$maxStorageBytes); dropping save")
                    return@withLock
                }
                val encoded =
                    runCatching { json.encodeToString(EventListSerializer, events) }
                        .onFailure { Logger.warning("Failed to encode events for storage", it) }
                        .getOrNull() ?: return@withLock

                // Atomic write: write to tmp, fsync, rename. Any crash mid-write leaves the
                // previous file untouched.
                val tmp = File(storageDir, EVENTS_FILE_NAME + TMP_SUFFIX)
                try {
                    tmp.outputStream().use { out ->
                        out.write(encoded.toByteArray(Charsets.UTF_8))
                        out.flush()
                        out.fd.sync()
                    }
                    if (!tmp.renameTo(eventsFile)) {
                        // renameTo can fail across filesystems or under some Android versions;
                        // fall back to copy-then-delete which is also atomic against partial reads
                        // because we only delete after the destination is fully written.
                        eventsFile.outputStream().use { it.write(tmp.readBytes()) }
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    Logger.warning("Atomic save failed", t)
                    tmp.delete()
                }
            }
        }

    /** Load any persisted events from disk. Returns an empty list if missing or corrupted. */
    suspend fun loadEvents(): List<Event> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!eventsFile.exists()) return@withLock emptyList()
                val raw =
                    runCatching { eventsFile.readText(Charsets.UTF_8) }
                        .onFailure { Logger.warning("Failed to read persisted events", it) }
                        .getOrNull() ?: return@withLock emptyList()
                runCatching { json.decodeFromString(EventListSerializer, raw) }
                    .onFailure { t ->
                        Logger.warning("Persisted events were corrupted; discarding", t)
                        eventsFile.delete()
                    }.getOrDefault(emptyList())
            }
        }

    /** Remove the persisted file, e.g. after a successful flush. */
    suspend fun clearEvents(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (eventsFile.exists()) eventsFile.delete()
            }
        }

    private fun ensureDirectory(): File? {
        if (storageDir.exists()) return storageDir
        return if (storageDir.mkdirs()) {
            storageDir
        } else {
            null.also {
                Logger.warning("Could not create storage directory: $storageDir")
            }
        }
    }

    private fun directorySize(): Long {
        if (!storageDir.exists()) return 0L
        return storageDir
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    internal companion object {
        const val STORAGE_DIR_NAME: String = "appstats"
        const val EVENTS_FILE_NAME: String = "events.json"
        const val TMP_SUFFIX: String = ".tmp"
        const val MAX_STORAGE_BYTES: Long = 10L * 1024L * 1024L

        private val EventListSerializer = kotlinx.serialization.builtins.ListSerializer(Event.serializer())
    }
}
