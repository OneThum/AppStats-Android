# Changelog

All notable changes to the AppStats Android SDK will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
