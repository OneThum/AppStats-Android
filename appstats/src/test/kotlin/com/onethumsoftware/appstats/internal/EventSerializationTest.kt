// Verifies that on-the-wire JSON conforms to schemas/event.v1.json field names and types.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class EventSerializationTest {
    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    @Test
    fun `session_start event has the canonical v1 field set`() {
        val event =
            Event(
                id = "00000000-0000-4000-8000-000000000001",
                timestamp = "2026-05-09T19:00:00.000Z",
                type = EventType.SESSION_START,
                sessionId = "00000000-0000-4000-8000-000000000002",
                appVersion = "1.0.0",
                buildNumber = "1",
                deviceModel = "Pixel 7",
                osVersion = "14",
                platform = "android",
                screenResolution = "1080x2400",
                locale = "en_US",
                timezone = "America/New_York",
                sdkVersion = "0.1.0",
            )

        val obj = json.encodeToString(Event.serializer(), event).let { Json.parseToJsonElement(it).jsonObject }

        // Field names per docs/SDK_PROTOCOL.md §3.1
        assertThat(obj.keys).containsAtLeast(
            "id",
            "timestamp",
            "event_type",
            "session_id",
            "app_version",
            "build_number",
            "device_model",
            "os_version",
            "platform",
            "screen_resolution",
            "locale",
            "timezone",
            "sdk_version",
        )
        assertThat(obj["event_type"]?.jsonPrimitive?.contentOrNull).isEqualTo("session_start")
        assertThat(obj["platform"]?.jsonPrimitive?.contentOrNull).isEqualTo("android")
        // session_start MUST NOT include event_name / screen_name
        assertThat(obj.containsKey("event_name")).isFalse()
        assertThat(obj.containsKey("screen_name")).isFalse()
    }

    @Test
    fun `custom event includes event_name`() {
        val event =
            Event(
                id = "00000000-0000-4000-8000-000000000003",
                timestamp = "2026-05-09T19:00:00.000Z",
                type = EventType.CUSTOM,
                name = "purchase_completed",
                sessionId = "00000000-0000-4000-8000-000000000004",
                appVersion = "1.0.0",
                buildNumber = "1",
                deviceModel = "Pixel 7",
                osVersion = "14",
                platform = "android",
                screenResolution = "1080x2400",
                locale = "en_US",
                timezone = "America/New_York",
                sdkVersion = "0.1.0",
                properties =
                    mapOf(
                        "amount" to EventValue.DoubleValue(9.99),
                        "currency" to EventValue.StringValue("USD"),
                        "is_subscription" to EventValue.BoolValue(true),
                        "qty" to EventValue.LongValue(1L),
                        "discount" to EventValue.NullValue,
                    ),
            )

        val obj = Json.parseToJsonElement(json.encodeToString(Event.serializer(), event)).jsonObject
        assertThat(obj["event_name"]?.jsonPrimitive?.contentOrNull).isEqualTo("purchase_completed")
        val props = obj["properties"]!!.jsonObject
        // Schema requires primitives: number / string / bool / null only.
        assertThat(props["amount"]!!.jsonPrimitive.double).isEqualTo(9.99)
        assertThat(props["currency"]!!.jsonPrimitive.contentOrNull).isEqualTo("USD")
        assertThat(props["is_subscription"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(props["qty"]!!.jsonPrimitive.long).isEqualTo(1L)
        assertThat(
            props["discount"] is JsonPrimitive ||
                props["discount"] == null ||
                props["discount"].toString() == "null",
        ).isTrue()
    }

    @Test
    fun `screen_view event includes screen_name`() {
        val event =
            Event(
                id = "00000000-0000-4000-8000-000000000005",
                timestamp = "2026-05-09T19:00:00.000Z",
                type = EventType.SCREEN_VIEW,
                screenName = "MainActivity",
                sessionId = "00000000-0000-4000-8000-000000000006",
                appVersion = "1.0.0",
                buildNumber = "1",
                deviceModel = "Pixel 7",
                osVersion = "14",
                platform = "android",
                screenResolution = "1080x2400",
                locale = "en_US",
                timezone = "America/New_York",
                sdkVersion = "0.1.0",
            )

        val obj = Json.parseToJsonElement(json.encodeToString(Event.serializer(), event)).jsonObject
        assertThat(obj["event_type"]?.jsonPrimitive?.contentOrNull).isEqualTo("screen_view")
        assertThat(obj["screen_name"]?.jsonPrimitive?.contentOrNull).isEqualTo("MainActivity")
    }

    @Test
    fun `EventValue from coerces non-primitives to null`() {
        assertThat(EventValue.from(listOf(1, 2, 3))).isEqualTo(EventValue.NullValue)
        assertThat(EventValue.from(mapOf("a" to 1))).isEqualTo(EventValue.NullValue)
        assertThat(EventValue.from(42)).isEqualTo(EventValue.LongValue(42))
        assertThat(EventValue.from(3.14)).isEqualTo(EventValue.DoubleValue(3.14))
        assertThat(EventValue.from("hi")).isEqualTo(EventValue.StringValue("hi"))
        assertThat(EventValue.from(true)).isEqualTo(EventValue.BoolValue(true))
        assertThat(EventValue.from(null)).isEqualTo(EventValue.NullValue)
    }

    @Test
    fun `event_type enum names match the protocol spec`() {
        // Each enum name → expected serialized form.
        val expected =
            mapOf(
                EventType.SESSION_START to "session_start",
                EventType.SESSION_END to "session_end",
                EventType.SCREEN_VIEW to "screen_view",
                EventType.APP_LAUNCH to "app_launch",
                EventType.APP_BACKGROUND to "app_background",
                EventType.APP_FOREGROUND to "app_foreground",
                EventType.CRASH to "crash",
                EventType.CUSTOM to "custom",
            )
        for ((enumValue, serialized) in expected) {
            val out =
                json
                    .encodeToString(EventType.serializer(), enumValue)
                    .removeSurrounding("\"")
            assertThat(out).isEqualTo(serialized)
        }
    }
}
