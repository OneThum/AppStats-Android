# Changelog

All notable changes to the AppStats Android SDK will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.17] - 2026-09-06

### Added

- **Native crash and ANR reporting.** A crash in native (NDK/ART) code kills the process without ever producing a Throwable, so `Thread.setDefaultUncaughtExceptionHandler` never sees it — those deaths, and ANRs, were previously invisible to AppStats. `NativeExitReporter` now reads Android's own record of process deaths (`ActivityManager.getHistoricalProcessExitReasons`) on the next launch and replays them as `crash` events, closing the gap against the Swift SDK's POSIX signal handler.
  - Read from the platform record rather than an in-process signal handler, so the SDK still ships **no native code** (the AAR contains no `.so` for any ABI) and cannot deadlock inside a crashing process the way a signal handler can. The trade is an API floor: this requires **API 30+** (Android 11), and on Android 7–10 native crashes remain unreported.
  - The signal number is resolved to the same name the Swift SDK reports (`SIGSEGV`, `SIGABRT`, …) using Linux numbering — deliberately not Darwin's, where `SIGBUS` is 10 rather than 7.
  - ANRs are reported with `exception` set to `ANR`, carrying the platform's thread dump as the stack trace. Native tombstones are protobuf-encoded on API 31+ and are dropped rather than shipped as mojibake; the platform's `description` still carries the summary.
  - Crashes are attributed to the session that actually died, by matching the recorded exit's pid against a pid → session id record written each launch.
  - Deliberately excluded: `REASON_CRASH` (a JVM crash, already reported once by the exception handler — reporting both would double-count every Kotlin crash), and ordinary deaths such as low-memory kills and user-requested exits, which are not faults.
  - The first launch after upgrading adopts the platform's current high-water mark and reports nothing, so the backlog Android already remembers does not arrive as a burst of crashes dated before the SDK was integrated.

No wire-protocol change: `crash` is an existing event type and these events use the existing property set.

## [1.0.14] - 2026-08-11

### Added

- Sticky user properties set via `setUserProperty`/`identify` are now persisted to disk (`StorageManager.saveUserProperties`/`loadUserProperties`) and reloaded on `initialize()`, so they survive a cold relaunch without every caller re-setting them on every launch. (Previously they lived only in the in-memory `userProps` map for the life of the process — attachment to events already worked correctly, this only adds cross-restart persistence, matching the equivalent fix shipped in the Swift SDK.)

### Fixed

- Closed a race where `setUserProperty` called immediately after `configure()` could run on a different `Dispatchers.Default` thread than `initialize()` and see the `StorageManager` reference still null, silently skipping the disk write with no retry. `initialize()` now does a catch-up persist once storage is assigned.

## [1.0.12] - 2026-06-13

### Changed

- **Version alignment**: the Android SDK now versions in lockstep with the Swift SDK
  (`OneThum/AppStats-iOS`). This release jumps from `0.1.2` to `1.0.12` so that a given
  version number identifies the same protocol surface and behavior on both platforms.
  No functional or wire-protocol changes — both SDKs continue to conform to protocol
  v1 (`/v1/ingest`) and are distinguished server-side by `X-AS-SDK-Platform`
  (`kotlin` vs `swift`), not by version number.

## [0.1.2] - 2026-05-09

### Added

- **`AppStats.isConfigured()`** — public predicate for whether **`configure`** has run (stable for bridge code).
- **`AppStats.identify(userId)`** — sets sticky **`user_id`** + **`signed_in`**; **`null`** / blank clears **`user_id`** and sets **`signed_in`** to **`false`**.

### Changed

- **`setUserProperty(key, null)`** removes **`key`** from the sticky map so it is omitted from payloads (previously the key could remain with JSON **`null`**).

### Documentation

- README: Compose / single-activity guidance for **`autoTrackScreens`**, **`setUserProperty`** semantics, dotted **`track`** names, JitPack → Maven Central migration + ProGuard note.

## [0.1.1] - 2026-05-09

### Fixed

- Default `RELEASE_SIGNING_ENABLED=false` so JitPack builds (which lack GPG keys)
  succeed. The Maven Central release workflow opts back in via
  `-PRELEASE_SIGNING_ENABLED=true`.

## [0.1.0] - 2026-05-09

### Added

- Initial public release of `com.onethumsoftware:appstats-android`.
- Conforms to AppStats SDK Protocol v1 (see `docs/SDK_PROTOCOL.md`).
- Public API: `configure`, `track`, `trackScreen`, `flush`, `flushAsync`,
  `setUserProperty`.
- Auto-tracking: app launch, foreground/background sessions, screen views, crashes.
- Persistent event queue with 10 MB disk budget, atomic writes, 48-hour staleness window.
- OkHttp transport with zlib deflate compression, exponential-backoff retries
  (max 2), circuit breaker after 10 consecutive failures.
- `androidx.startup`-based opt-in auto-configuration via manifest meta-data.
- `WorkManager` expedited background-flush worker to finish in-flight sends after
  backgrounding.
- Distributed via JitPack (`com.github.OneThum:appstats-android:0.1.0`). Maven
  Central graduation tracked in [Phase 9](#).
