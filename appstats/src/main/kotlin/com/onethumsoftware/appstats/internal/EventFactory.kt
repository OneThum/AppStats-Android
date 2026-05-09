// Builds Event instances with consistent device/app metadata.
// Mirrors the Swift SDK's `Event.init(...)` in sdk/Sources/AppStats/Events/Event.swift.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal class EventFactory(
    private val deviceInfo: DeviceInfo,
    private val appInfo: AppInfo,
    private val now: () -> Date = { Date() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun build(
        type: EventType,
        sessionId: String,
        name: String? = null,
        screenName: String? = null,
        properties: Map<String, Any?>? = null,
    ): Event =
        Event(
            id = newId(),
            timestamp = isoFormatter.format(now()),
            type = type,
            name = name,
            sessionId = sessionId,
            screenName = screenName,
            appVersion = appInfo.version,
            buildNumber = appInfo.buildNumber,
            deviceModel = deviceInfo.deviceModel,
            osVersion = deviceInfo.osVersion,
            platform = deviceInfo.platform,
            screenResolution = deviceInfo.screenResolution,
            locale = deviceInfo.locale,
            timezone = deviceInfo.timezone,
            sdkVersion = SdkInfo.version,
            properties = properties?.mapValues { EventValue.from(it.value) },
        )

    private companion object {
        // ISO-8601 with millisecond precision, UTC. Matches the Swift SDK's
        // .iso8601 JSONEncoder dateEncodingStrategy.
        private val isoFormatter =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
