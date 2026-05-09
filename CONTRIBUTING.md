# Contributing to appstats-android

Thanks for your interest! This SDK is a peer to the
[AppStats Swift SDK](https://github.com/OneThum/AppStats) and shares the same
on-the-wire contract documented in
[docs/SDK_PROTOCOL.md](../docs/SDK_PROTOCOL.md).

## Local setup

```bash
git clone https://github.com/OneThum/appstats-android.git
cd appstats-android
./gradlew :appstats:assembleDebug
./gradlew :appstats:testDebugUnitTest
./gradlew :appstats:ktlintCheck :appstats:detekt
```

JDK 17 is required.

## Coding standards

- Public API is annotated and documented; we ship sources & javadoc jars.
- `ktlint` controls formatting. Run `./gradlew :appstats:ktlintFormat` before
  committing if the check fails.
- `detekt` controls static analysis. CI rejects new issues.
- Public-API stability is maintained via Kotlin `explicit-api` mode (warning).

## Pull request workflow

1. Fork & branch from `main`.
2. Open a draft PR early. CI must be green.
3. Add a `CHANGELOG.md` entry under `[Unreleased]`.
4. If you change anything that affects the wire protocol, also open a PR in the
   private spec repo updating `docs/SDK_PROTOCOL.md` and `schemas/event.v1.json`.
   Cross-link both PRs.
5. Tag a maintainer.

## Releases

Releases are tag-driven. Pushing `vX.Y.Z` triggers
`.github/workflows/release.yml`, which:

1. Verifies lint, tests, and that the AAR builds.
2. Publishes to Maven Central via the Vanniktech plugin.
3. Creates a GitHub Release with the AAR attached.

Maintainers must update `gradle.properties` `VERSION_NAME` (or pass
`-PVERSION_NAME=…`) to match the tag before pushing.
