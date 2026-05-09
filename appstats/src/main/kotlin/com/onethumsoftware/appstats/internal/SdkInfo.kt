// SDK metadata. Mirrors sdk/Sources/AppStats/Core/SDKInfo.swift in the Swift SDK.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import com.onethumsoftware.appstats.BuildConfig

internal object SdkInfo {
    /** SDK version (semver). Sourced from gradle.properties → BuildConfig at compile time. */
    const val VERSION_FALLBACK: String = "0.1.0-SNAPSHOT"

    val version: String
        get() = runCatching { BuildConfig.SDK_VERSION }.getOrDefault(VERSION_FALLBACK)

    /** Platform identifier sent in `X-AS-SDK-Platform` and as the `platform` event field. */
    const val PLATFORM: String = "android"

    /** Header-friendly identifier used for analytics & bug triage. */
    const val SDK_PLATFORM_HEADER: String = "kotlin"

    /** Stable identifier (e.g., for storage keys, log tags). */
    const val IDENTIFIER: String = "com.onethumsoftware.appstats"
}
