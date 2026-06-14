# AppStats Android SDK

[![Build](https://github.com/OneThum/AppStats-Android/actions/workflows/build.yml/badge.svg)](https://github.com/OneThum/AppStats-Android/actions/workflows/build.yml)
[![Release](https://jitpack.io/v/OneThum/AppStats-Android.svg)](https://jitpack.io/#OneThum/AppStats-Android)

Privacy-first analytics SDK for Android apps. Native peer to the
[AppStats Swift SDK](https://github.com/OneThum/AppStats-iOS), conforming to the
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
    implementation("com.github.OneThum:AppStats-Android:1.0.12")
}
```

### Repository & JitPack artifact id (May 2026)

The public GitHub repository is **`OneThum/AppStats-Android`** (older links to `OneThum/appstats-android` redirect).  
On JitPack the module id matches the repo name: use **`com.github.OneThum:AppStats-Android`** — replace any legacy `com.github.OneThum:appstats-android` lines.

### From Maven Central (post-Phase 9)

After the Sonatype Central Portal namespace `com.onethumsoftware` is verified and CI secrets are configured (see checklist below):

```kotlin
dependencies {
    implementation("com.onethumsoftware:appstats-android:1.0.12")
}
```

#### Maintainer checklist — Maven Central graduation

1. **Namespace**: In [Central Portal](https://central.sonatype.com/), claim `com.onethumsoftware` (DNS TXT verification as documented by Sonatype).
2. **Signing**: Create a dedicated GPG key for artifacts; publish the public key; store private key + passphrase in GitHub Actions secrets for the **AppStats-Android** repo (names depend on `release.yml`; typically along the lines of `SIGNING_KEY`, `SIGNING_PASSWORD`).
3. **Publishing**: The Android repo uses the Vanniktech Maven Publish plugin with `RELEASE_SIGNING_ENABLED=true` only in the release workflow (JitPack builds leave signing off).
4. **Release**: Tag `v1.0.12` on **OneThum/AppStats-Android**, run the release workflow, confirm staging → release on Central.
5. **Consumers**: Update apps from JitPack coordinates to `com.onethumsoftware:appstats-android:1.0.12` (or newer).

Until these steps are complete, stay on **JitPack** coordinates above.

#### Consumer migration: JitPack → Maven Central

When **`com.onethumsoftware:appstats-android`** is published on Central, switch the Gradle
coordinate only — the same AAR ships **`consumer-rules.pro`**, so **R8 / ProGuard integration does
not change** (no extra `-keep` rules required beyond what the SDK merges).

| Channel | Gradle dependency |
| --- | --- |
| JitPack | `implementation("com.github.OneThum:AppStats-Android:<tag>")` |
| Maven Central | `implementation("com.onethumsoftware:appstats-android:<version>")` |

Repository block still needs `google()` + `mavenCentral()`; drop **`https://jitpack.io`** once
nothing else in the app uses JitPack.

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

### Compose / single-activity apps

With **`autoTrackScreens = true`** (the default, including manifest **`AUTO_TRACK_SCREENS`**),
the SDK registers **`ActivityLifecycleCallbacks`** and emits a **`screen_view`** for every
resumed activity title. That is often **too noisy** for **Compose** or **single-activity** shells
where one `Activity` hosts many logical screens.

Prefer **`autoTrackScreens = false`** and call **`AppStats.trackScreen("YourRoute")`** from your
navigator (e.g. `NavController` `DisposableEffect`) when you want a meaningful screen name.

## API surface

```kotlin
AppStats.configure(context, apiKey, autoTrackScreens = true, flushInterval = 30.seconds)
AppStats.isConfigured()
AppStats.identify(userId)              // null / blank clears sticky user_id + sets signed_in false
AppStats.track("purchase_completed", mapOf("amount" to 9.99, "currency" to "USD"))
AppStats.trackScreen("HomeView")       // Compose / non-Activity navigation
AppStats.flush()                       // fire-and-forget
suspend fun shutdown() = AppStats.flushAsync()
AppStats.setUserProperty("plan", "pro")
AppStats.setUserProperty("plan", null) // removes sticky key — see below
```

### Sticky user properties (`setUserProperty`)

- Values are merged into the JSON **`properties`** object on **every** subsequent event (including
  automatic lifecycle events), unless overridden per event via **`track`** (event keys win on merge).
- Passing **`null`** as the value **removes that key** from the sticky map so it **does not appear**
  on later payloads. It does **not** send JSON `null` as the property value.

### Custom event names (`track`)

Event names are opaque strings. Namespaced names such as **`game.level_complete`** or
**`notification.opened`** match common backend-style analytics and ingest as **`custom`** events
with **`event_name`** set to the full string (including dots).

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

The Android SDK version is kept **in lockstep with the Swift SDK**
([OneThum/AppStats-iOS](https://github.com/OneThum/AppStats-iOS)): a given version
number (e.g. `1.0.12`) identifies the same protocol surface and behavior on both
platforms. Both conform to the same wire-protocol version (`/v1/ingest`); the
producer is distinguished server-side by the `X-AS-SDK-Platform` header
(`kotlin` vs `swift`), not by the version number.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE)
