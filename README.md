# AppStats Android SDK

[![Build](https://github.com/OneThum/appstats-android/actions/workflows/build.yml/badge.svg)](https://github.com/OneThum/appstats-android/actions/workflows/build.yml)
[![Release](https://jitpack.io/v/OneThum/appstats-android.svg)](https://jitpack.io/#OneThum/appstats-android)

Privacy-first analytics SDK for Android apps. Native peer to the
[AppStats Swift SDK](https://github.com/OneThum/AppStats), conforming to the
same [SDK Protocol Specification](../docs/SDK_PROTOCOL.md).

- Auto-tracks: app lifecycle, screens, crashes
- Custom events with primitive properties
- Offline-resilient: in-memory + on-disk queue, deflate-compressed batches
- Tiny dependency footprint: OkHttp, kotlinx.serialization, AndroidX lifecycle

---

## Requirements

| Item | Version |
|---|---|
| `minSdk` | 24 (Android 7.0) |
| Kotlin | 1.9+ |
| Java target | 17 |

## Installation

### From JitPack (current)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.OneThum:appstats-android:0.1.0")
}
```

### From Maven Central (post-Phase 9)

```kotlin
dependencies {
    implementation("com.onethumsoftware:appstats-android:1.0.0")
}
```

## Quick start

### Manual configuration

```kotlin
import com.onethumsoftware.appstats.AppStats

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStats.configure(
            context = this,
            apiKey = BuildConfig.APPSTATS_API_KEY,
        )
    }
}
```

### Manifest-driven auto-configuration

```xml
<!-- AndroidManifest.xml -->
<application>
    <meta-data
        android:name="com.onethumsoftware.appstats.API_KEY"
        android:value="as_live_xxxxxxxxxxxx" />
    <meta-data
        android:name="com.onethumsoftware.appstats.AUTO_TRACK_SCREENS"
        android:value="true" />
</application>
```

The SDK initializes via `androidx.startup` on the first content provider
boot, with no explicit `Application.onCreate` call required.

## API surface

```kotlin
AppStats.configure(context, apiKey, autoTrackScreens = true, flushInterval = 30.seconds)
AppStats.track("purchase_completed", mapOf("amount" to 9.99, "currency" to "USD"))
AppStats.trackScreen("HomeView")          // for Compose / non-Activity navigation
AppStats.flush()                          // fire-and-forget
suspend fun shutdown() = AppStats.flushAsync()
AppStats.setUserProperty("plan", "pro")
```

## Architecture

The SDK mirrors the Swift SDK module-for-module so cross-platform analytics
behave identically. See [docs/SDK_PROTOCOL.md](../docs/SDK_PROTOCOL.md) for the
wire-protocol contract both SDKs implement.

| Layer | Responsibility |
|---|---|
| `AppStats` | Public façade. Singleton with internal coroutine scope. |
| `EventCollector` | In-memory queue (cap 500), batches at 20, sends up to 100/request. |
| `NetworkManager` | OkHttp + zlib deflate, exponential-backoff retries, circuit breaker. |
| `StorageManager` | Atomic JSON file at `filesDir/appstats/events.json`, 10 MB budget. |
| `ScreenTracker` | `Application.ActivityLifecycleCallbacks`-based auto-tracking. |
| `CrashReporter` | `Thread.setDefaultUncaughtExceptionHandler` writes a marker, sends on next launch. |
| `Lifecycle observer` | `ProcessLifecycleOwner` to emit `session_start` / `session_end`. |
| `BackgroundFlushWorker` | Expedited `WorkManager` job to finish flushing after backgrounding. |

## Privacy

The SDK collects only what is documented in the protocol spec. No advertising
identifiers, no contacts, no precise location. IP-based geolocation is performed
server-side and uses only country/city granularity.

## Versioning

The Android SDK uses independent semver from the Swift SDK. Both conform to the
same wire-protocol version (`/v1/ingest`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
