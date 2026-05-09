// AppStats Android SDK — public entry point.
// Mirrors sdk/Sources/AppStats/AppStats.swift in API and behavior.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.onethumsoftware.appstats.internal.AppInfo
import com.onethumsoftware.appstats.internal.BackgroundFlushWorker
import com.onethumsoftware.appstats.internal.CrashReporter
import com.onethumsoftware.appstats.internal.DeviceInfo
import com.onethumsoftware.appstats.internal.EventCollector
import com.onethumsoftware.appstats.internal.EventFactory
import com.onethumsoftware.appstats.internal.EventType
import com.onethumsoftware.appstats.internal.Logger
import com.onethumsoftware.appstats.internal.NetworkManager
import com.onethumsoftware.appstats.internal.ScreenTracker
import com.onethumsoftware.appstats.internal.SdkInfo
import com.onethumsoftware.appstats.internal.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Public façade for the AppStats Android SDK.
 *
 * Configure once during app launch:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         AppStats.configure(this, BuildConfig.APPSTATS_API_KEY)
 *     }
 * }
 * ```
 *
 * After configuration, all public API methods are safe to call from any thread.
 *
 * The SDK auto-tracks app lifecycle (`session_start` / `session_end`),
 * screen views (when [AppStatsConfiguration.autoTrackScreens] is true), and
 * uncaught exceptions. Custom events are sent via [track].
 */
public object AppStats {
    /** Internal singleton holder. Atomic to avoid double-configure races. */
    private val instanceRef = AtomicReference<Internal?>(null)

    // ---------------- Public API ----------------

    /**
     * Configure the SDK. Idempotent — subsequent calls log a warning and are ignored.
     * Heavy initialization happens off the main thread; events submitted before init
     * completes are queued and replayed automatically.
     */
    @AnyThread
    public fun configure(
        context: Context,
        apiKey: String,
        autoTrackScreens: Boolean = true,
        flushInterval: Duration = AppStatsConfiguration.DEFAULT_FLUSH_INTERVAL,
    ) {
        configure(
            context = context,
            configuration =
                AppStatsConfiguration(
                    apiKey = apiKey,
                    autoTrackScreens = autoTrackScreens,
                    flushInterval = flushInterval,
                ),
        )
    }

    /** Configure the SDK with a fully-customized [AppStatsConfiguration]. */
    @AnyThread
    public fun configure(
        context: Context,
        configuration: AppStatsConfiguration,
    ) {
        val appContext = context.applicationContext
        val newInstance = Internal(appContext, configuration)
        if (!instanceRef.compareAndSet(null, newInstance)) {
            Logger.warning("AppStats.configure called more than once; ignoring subsequent call")
            return
        }
        Logger.debugLoggingEnabled = configuration.debugLogging
        newInstance.initializeAsync()
    }

    /** Track a custom event with optional primitive properties. */
    @AnyThread
    public fun track(
        eventName: String,
        properties: Map<String, Any?>? = null,
    ) {
        val instance =
            instanceRef.get() ?: run {
                Logger.warning("AppStats.track called before configure(); ignored")
                return
            }
        instance.scope.launch { instance.trackOrQueue(eventName, properties) }
    }

    /**
     * Track a screen view. Use this from non-Activity navigation surfaces (e.g. Compose
     * with NavController, custom Fragment routers, or split-screen flows where the
     * Activity name is not informative).
     */
    @AnyThread
    public fun trackScreen(screenName: String) {
        val instance = instanceRef.get() ?: return
        instance.scope.launch { instance.collectScreen(screenName) }
    }

    /** Force an immediate flush. Returns instantly; the actual send happens async. */
    @AnyThread
    public fun flush() {
        val instance = instanceRef.get() ?: return
        instance.scope.launch { instance.flushNow() }
    }

    /** Suspend until the in-flight flush completes. Safe from `LifecycleOwner.lifecycleScope`. */
    @AnyThread
    public suspend fun flushAsync() {
        val instance = instanceRef.get() ?: return
        instance.flushNow()
    }

    /** Set a sticky property included with every subsequent event. */
    @AnyThread
    public fun setUserProperty(
        key: String,
        value: Any?,
    ) {
        val instance = instanceRef.get() ?: return
        instance.scope.launch { instance.setUserProperty(key, value) }
    }

    /** Used internally and by tests. */
    internal val sdkVersion: String get() = SdkInfo.version
    internal val isConfigured: Boolean get() = instanceRef.get() != null

    /** Test-only escape hatch. Not part of the stable API. */
    internal fun resetForTests() {
        instanceRef.getAndSet(null)?.shutdown()
    }

    // ---------------- Internals ----------------

    private class Internal(
        val context: Context,
        val configuration: AppStatsConfiguration,
    ) : DefaultLifecycleObserver {
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile private var collector: EventCollector? = null

        @Volatile private var factory: EventFactory? = null

        @Volatile private var screenTracker: ScreenTracker? = null

        @Volatile private var crashReporter: CrashReporter? = null

        @Volatile private var initialized = false

        @Volatile private var disabled = false

        @Volatile private var sessionId: String = UUID.randomUUID().toString()

        private val pending = mutableListOf<PendingTrack>()
        private val pendingMutex = Mutex()

        private val userProps = mutableMapOf<String, Any?>()
        private val userPropsMutex = Mutex()

        private var flushJob: Job? = null

        fun initializeAsync() {
            scope.launch { initialize() }
        }

        private suspend fun initialize() {
            try {
                val deviceInfo = DeviceInfo(context)
                val appInfo = AppInfo(context)
                val storage = StorageManager(context)
                val network =
                    NetworkManager(
                        apiKey = configuration.apiKey,
                        baseUrl = configuration.baseUrl,
                    )
                val factory = EventFactory(deviceInfo, appInfo)
                val collector = EventCollector(storage, network, scope)

                this.factory = factory
                this.collector = collector

                // Crash reporter must be installed BEFORE any other Throwable can fire.
                val reporter = CrashReporter(context) { sessionId }.apply { install() }
                this.crashReporter = reporter

                // Lifecycle observation on main thread (ProcessLifecycleOwner requirement).
                postOnMain {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
                }

                // Optional auto-screen-tracking.
                if (configuration.autoTrackScreens) {
                    val app = context.applicationContext as? android.app.Application
                    if (app != null) {
                        val tracker = ScreenTracker { screenName -> trackScreenInternal(screenName) }
                        app.registerActivityLifecycleCallbacks(tracker)
                        screenTracker = tracker
                    }
                }

                // Recurring flush timer.
                startFlushTimer()

                // Replay any crash from the previous launch.
                reporter.consumePreviousCrash()?.let { crash ->
                    val event =
                        factory.build(
                            type = EventType.CRASH,
                            sessionId = crash.sessionId.ifBlank { sessionId },
                            properties =
                                mapOf(
                                    "exception" to crash.exception,
                                    "message" to crash.message,
                                    "thread" to crash.thread,
                                    "timestamp_ms" to crash.timestampMs,
                                    "stack_trace" to crash.stackTrace,
                                ),
                        )
                    collector.collect(event)
                }

                // Bootstrap session_start + app_launch.
                val sessionStart =
                    factory.build(
                        type = EventType.SESSION_START,
                        sessionId = sessionId,
                        properties = mergedProps(emptyMap()),
                    )
                collector.collect(sessionStart)

                val launch =
                    factory.build(
                        type = EventType.APP_LAUNCH,
                        sessionId = sessionId,
                        properties = mergedProps(emptyMap()),
                    )
                collector.collect(launch)

                initialized = true
                Logger.info("AppStats initialized (sdk=${SdkInfo.version}, platform=${SdkInfo.PLATFORM})")
                drainPending()
            } catch (t: Throwable) {
                Logger.error("AppStats initialization failed", t)
                disabled = true
            }
        }

        @MainThread
        override fun onStart(owner: LifecycleOwner) {
            // Foreground transition. The first foreground after configure() is handled by
            // initialize(); subsequent foregrounds are handled here as new sessions.
            if (!initialized) return
            scope.launch {
                sessionId = UUID.randomUUID().toString()
                val f = factory ?: return@launch
                val c = collector ?: return@launch
                c.collect(f.build(EventType.SESSION_START, sessionId, properties = mergedProps(emptyMap())))
                c.collect(f.build(EventType.APP_FOREGROUND, sessionId, properties = mergedProps(emptyMap())))
            }
        }

        @MainThread
        override fun onStop(owner: LifecycleOwner) {
            if (!initialized) return
            scope.launch {
                val f = factory ?: return@launch
                val c = collector ?: return@launch
                c.collect(f.build(EventType.APP_BACKGROUND, sessionId, properties = mergedProps(emptyMap())))
                c.collect(f.build(EventType.SESSION_END, sessionId, properties = mergedProps(emptyMap())))
                c.flush()
            }
            // Belt-and-suspenders: hand the flush to WorkManager so the OS can finish it
            // even if the process is killed.
            BackgroundFlushWorker.enqueue(context)
        }

        suspend fun trackOrQueue(
            eventName: String,
            properties: Map<String, Any?>?,
        ) {
            if (disabled) return
            if (!initialized) {
                pendingMutex.withLock { pending += PendingTrack.Custom(eventName, properties) }
                return
            }
            collectCustom(eventName, properties)
        }

        suspend fun collectScreen(screenName: String) {
            if (disabled) return
            if (!initialized) {
                pendingMutex.withLock { pending += PendingTrack.Screen(screenName) }
                return
            }
            val f = factory ?: return
            val c = collector ?: return
            c.collect(
                f.build(
                    type = EventType.SCREEN_VIEW,
                    sessionId = sessionId,
                    screenName = screenName,
                    properties = mergedProps(emptyMap()),
                ),
            )
        }

        private suspend fun collectCustom(
            eventName: String,
            properties: Map<String, Any?>?,
        ) {
            val f = factory ?: return
            val c = collector ?: return
            c.collect(
                f.build(
                    type = EventType.CUSTOM,
                    sessionId = sessionId,
                    name = eventName,
                    properties = mergedProps(properties.orEmpty()),
                ),
            )
        }

        private fun trackScreenInternal(screenName: String) {
            scope.launch { collectScreen(screenName) }
        }

        suspend fun flushNow() {
            collector?.flush()
        }

        suspend fun setUserProperty(
            key: String,
            value: Any?,
        ) {
            userPropsMutex.withLock { userProps[key] = value }
        }

        private suspend fun mergedProps(extras: Map<String, Any?>): Map<String, Any?> {
            val sticky = userPropsMutex.withLock { userProps.toMap() }
            return if (sticky.isEmpty()) extras else sticky + extras
        }

        private fun startFlushTimer() {
            flushJob?.cancel()
            val intervalMs = configuration.flushInterval.inWholeMilliseconds
            flushJob =
                scope.launch {
                    while (true) {
                        delay(intervalMs)
                        runCatching { collector?.flush() }
                    }
                }
        }

        private suspend fun drainPending() {
            val drained =
                pendingMutex.withLock {
                    val out = pending.toList()
                    pending.clear()
                    out
                }
            for (item in drained) {
                when (item) {
                    is PendingTrack.Custom -> collectCustom(item.name, item.properties)
                    is PendingTrack.Screen -> collectScreen(item.name)
                }
            }
        }

        fun shutdown() {
            flushJob?.cancel()
            (context.applicationContext as? android.app.Application)?.let { app ->
                screenTracker?.let { app.unregisterActivityLifecycleCallbacks(it) }
            }
            scope.coroutineContext[Job]?.cancel()
        }

        private fun postOnMain(action: () -> Unit) {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }

    private sealed interface PendingTrack {
        data class Custom(
            val name: String,
            val properties: Map<String, Any?>?,
        ) : PendingTrack

        data class Screen(
            val name: String,
        ) : PendingTrack
    }
}
