// Public configuration model for the AppStats SDK.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats

import androidx.annotation.IntRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable configuration for the AppStats SDK. Construct via [AppStats.configure].
 *
 * @property apiKey            Account/app API key, e.g. `as_live_xxxxxxxxxxxx`.
 * @property baseUrl           Ingestion API base URL. Override for self-hosted or staging.
 * @property autoTrackScreens  If true, the SDK auto-tracks Activity-driven screen views
 *                             via Application.ActivityLifecycleCallbacks. Compose users
 *                             should leave this on for Activity navigation and emit
 *                             [AppStats.trackScreen] manually for in-Activity routes.
 * @property flushInterval     How often to flush the in-memory queue. Minimum 5s.
 * @property debugLogging      Enables verbose logcat output. Off by default.
 */
public data class AppStatsConfiguration(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val autoTrackScreens: Boolean = true,
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    val debugLogging: Boolean = false,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(flushInterval.inWholeSeconds >= MIN_FLUSH_INTERVAL_SECONDS) {
            "flushInterval must be ≥ ${MIN_FLUSH_INTERVAL_SECONDS}s"
        }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "baseUrl must be an http(s) URL"
        }
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://ingest.appstats.app"
        public val DEFAULT_FLUSH_INTERVAL: Duration = 30.seconds

        @IntRange(from = 5)
        public const val MIN_FLUSH_INTERVAL_SECONDS: Long = 5L
    }
}
