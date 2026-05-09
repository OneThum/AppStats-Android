// Shared JSON configuration. Mirrors the Swift SDK's JSONEncoder/JSONDecoder usage
// (ISO-8601 dates, no extra whitespace, fields preserved in declaration order).
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import kotlinx.serialization.json.Json

internal val SdkJson: Json =
    Json {
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = false
    }
