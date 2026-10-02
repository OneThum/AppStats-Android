// WorkManager job that finishes in-flight flushes after the app is backgrounded.
// Conceptual equivalent of `UIApplication.beginBackgroundTask` used in
// sdk/Sources/AppStats/AppStats.swift:handleAppBackground().
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Internal worker — never invoked directly by SDK consumers. The library schedules
 * this worker on `ON_STOP` lifecycle transitions to give the OS-mediated flush a
 * chance to complete after the process loses foreground priority.
 *
 * Up to 1.0.20 this class was `BackgroundFlushWorker`, enqueued as expedited work, which
 * crashes on API < 31 (see [buildRequest]). WorkManager persists pending requests by
 * worker class name across app updates, so a host upgrading from an affected version
 * still holds that expedited request and would run it — and crash — at the next launch,
 * before any new enqueue could replace it. The rename is the fix for that: a persisted
 * request naming a class that no longer exists fails to instantiate and is dropped, with
 * no crash. Never reintroduce a worker named `BackgroundFlushWorker`.
 */
internal class FlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            // Resolve the live SDK instance via the public façade. The flush is a no-op
            // if the SDK was never configured (e.g., consumer uninstalled the SDK between
            // builds but left the worker registration around).
            com.onethumsoftware.appstats.AppStats
                .flushAsync()
            Result.success()
        } catch (t: Throwable) {
            Logger.warning("FlushWorker failed", t)
            Result.retry()
        }

    internal companion object {
        const val UNIQUE_NAME: String = "appstats.flush"

        /** The unique-work name the pre-1.0.21 `BackgroundFlushWorker` was enqueued under. */
        const val LEGACY_UNIQUE_NAME: String = "appstats.background_flush"

        /**
         * Deliberately not expedited. Below API 31 WorkManager runs expedited work as a
         * foreground service and calls [getForegroundInfo] for its notification; a worker
         * that doesn't override it throws `IllegalStateException("Not implemented")` on a
         * background thread and kills the host app's process. The flush is best-effort, so
         * ordinary scheduling is enough and needs no notification.
         */
        fun buildRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FlushWorker>().build()

        fun enqueue(context: Context) {
            // Use REPLACE so a freshly-backgrounded session always gets a fresh attempt.
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, buildRequest())
        }

        /**
         * Drops any request a pre-1.0.21 SDK left behind. Not what prevents the crash (the
         * rename does that); this just clears the dead entry out of WorkManager's database.
         */
        fun cancelLegacy(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_UNIQUE_NAME)
        }
    }
}
