// Event model. Mirrors sdk/Sources/AppStats/Events/Event.swift on the wire.
// Field names and order are governed by docs/SDK_PROTOCOL.md and schemas/event.v1.json.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class Event(
    val id: String,
    val timestamp: String,
    @SerialName("event_type") val type: EventType,
    @SerialName("event_name") val name: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("screen_name") val screenName: String? = null,
    @SerialName("app_version") val appVersion: String,
    @SerialName("build_number") val buildNumber: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("os_version") val osVersion: String,
    val platform: String,
    @SerialName("screen_resolution") val screenResolution: String,
    val locale: String,
    val timezone: String,
    @SerialName("sdk_version") val sdkVersion: String,
    val properties: Map<String, EventValue>? = null,
)

@Serializable
internal enum class EventType {
    @SerialName("session_start")
    SESSION_START,

    @SerialName("session_end")
    SESSION_END,

    @SerialName("screen_view")
    SCREEN_VIEW,

    @SerialName("app_launch")
    APP_LAUNCH,

    @SerialName("app_background")
    APP_BACKGROUND,

    @SerialName("app_foreground")
    APP_FOREGROUND,

    @SerialName("crash")
    CRASH,

    @SerialName("custom")
    CUSTOM,
}
