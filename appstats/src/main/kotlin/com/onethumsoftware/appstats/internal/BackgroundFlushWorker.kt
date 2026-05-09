// Expedited WorkManager job that finishes in-flight flushes after the app is backgrounded.
// Conceptual equivalent of `UIApplication.beginBackgroundTask` used in
// sdk/Sources/AppStats/AppStats.swift:handleAppBackground().
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Internal worker — never invoked directly by SDK consumers. The library schedules
 * this worker on `ON_STOP` lifecycle transitions to give the OS-mediated flush a
 * chance to complete after the process loses foreground priority.
 */
internal class BackgroundFlushWorker(
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
            Logger.warning("BackgroundFlushWorker failed", t)
            Result.retry()
        }

    internal companion object {
        const val UNIQUE_NAME: String = "appstats.background_flush"

        fun enqueue(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<BackgroundFlushWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            // Use REPLACE so a freshly-backgrounded session always gets a fresh attempt.
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_NAME,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}
