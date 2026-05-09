// Type-safe wrapper for event property values.
// Mirrors sdk/Sources/AppStats/Events/Event.swift `AnyCodable`.
// Restricts to primitives per the v1 protocol spec (docs/SDK_PROTOCOL.md §3.3).
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable(with = EventValueSerializer::class)
internal sealed interface EventValue {
    @Serializable
    data class StringValue(
        val value: String,
    ) : EventValue

    @Serializable
    data class LongValue(
        val value: Long,
    ) : EventValue

    @Serializable
    data class DoubleValue(
        val value: Double,
    ) : EventValue

    @Serializable
    data class BoolValue(
        val value: Boolean,
    ) : EventValue

    @Serializable
    object NullValue : EventValue

    companion object {
        /**
         * Best-effort coercion from arbitrary user input to a primitive event value.
         * Drops to [NullValue] for unsupported types (collections, objects, etc.) so
         * we never reject the whole event.
         */
        fun from(any: Any?): EventValue =
            when (any) {
                null -> NullValue
                is Boolean -> BoolValue(any)
                is Byte -> LongValue(any.toLong())
                is Short -> LongValue(any.toLong())
                is Int -> LongValue(any.toLong())
                is Long -> LongValue(any)
                is Float -> DoubleValue(any.toDouble())
                is Double -> DoubleValue(any)
                is Number -> DoubleValue(any.toDouble())
                is String -> StringValue(any)
                else ->
                    NullValue.also {
                        Logger.warning("Dropping non-primitive event property value: ${any::class.java.name}")
                    }
            }
    }
}

internal object EventValueSerializer : KSerializer<EventValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EventValue", PrimitiveKind.STRING).let {
            // We override `kind` only to satisfy kotlinx.serialization's requirement that
            // contextual primitives have a kind. The actual encoding is JSON-aware below.
            object : SerialDescriptor by it {
                override val kind: SerialKind = PrimitiveKind.STRING
            }
        }

    override fun serialize(
        encoder: Encoder,
        value: EventValue,
    ) {
        when (encoder) {
            is JsonEncoder ->
                when (value) {
                    is EventValue.StringValue -> encoder.encodeJsonElement(JsonPrimitive(value.value))
                    is EventValue.LongValue -> encoder.encodeJsonElement(JsonPrimitive(value.value))
                    is EventValue.DoubleValue -> encoder.encodeJsonElement(JsonPrimitive(value.value))
                    is EventValue.BoolValue -> encoder.encodeJsonElement(JsonPrimitive(value.value))
                    is EventValue.NullValue -> encoder.encodeJsonElement(JsonNull)
                }
            else -> {
                // Fallback for non-JSON encoders (rare in this SDK).
                when (value) {
                    is EventValue.StringValue -> encoder.encodeString(value.value)
                    is EventValue.LongValue -> Long.serializer().serialize(encoder, value.value)
                    is EventValue.DoubleValue -> Double.serializer().serialize(encoder, value.value)
                    is EventValue.BoolValue -> Boolean.serializer().serialize(encoder, value.value)
                    is EventValue.NullValue -> encoder.encodeString("null")
                }
            }
        }
    }

    override fun deserialize(decoder: Decoder): EventValue {
        if (decoder !is JsonDecoder) {
            return EventValue.StringValue(decoder.decodeString())
        }
        val element = decoder.decodeJsonElement()
        if (element is JsonNull) return EventValue.NullValue
        val primitive = element.jsonPrimitive
        return when {
            primitive.isString -> EventValue.StringValue(primitive.content)
            primitive.booleanOrNull != null -> EventValue.BoolValue(primitive.booleanOrNull!!)
            primitive.longOrNull != null -> EventValue.LongValue(primitive.longOrNull!!)
            primitive.doubleOrNull != null -> EventValue.DoubleValue(primitive.doubleOrNull!!)
            else -> EventValue.StringValue(primitive.content)
        }
    }
}
