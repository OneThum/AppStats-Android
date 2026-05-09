# Changelog

All notable changes to the AppStats Android SDK will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - TBD

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
