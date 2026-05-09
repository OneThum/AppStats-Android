// Device/host metadata. Mirrors sdk/Sources/AppStats/Core/DeviceInfo.swift.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import java.util.TimeZone

internal class DeviceInfo(
    private val context: Context,
) {
    /** Human-readable device model, e.g. "Pixel 7", "SM-G991U". */
    val deviceModel: String
        get() {
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            return when {
                model.isBlank() -> manufacturer.ifBlank { "unknown" }
                model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model".trim()
            }
        }

    /** OS marketing version, e.g. "14". */
    val osVersion: String
        get() =
            Build.VERSION.RELEASE
                .orEmpty()
                .ifBlank { "unknown" }

    /** Always "android" for this SDK. */
    val platform: String = SdkInfo.PLATFORM

    /** "WIDTHxHEIGHT" in raw pixels, or "unknown". */
    val screenResolution: String
        get() =
            runCatching {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return "unknown"
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(metrics)
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                if (w > 0 && h > 0) "${w}x$h" else "unknown"
            }.getOrDefault("unknown")

    /** POSIX-style locale identifier, e.g. "en_US". */
    val locale: String
        get() =
            runCatching {
                val resourceLocale = currentResourcesLocale()
                val lang = resourceLocale.language.orEmpty()
                val country = resourceLocale.country.orEmpty()
                when {
                    lang.isBlank() -> "unknown"
                    country.isBlank() -> lang
                    else -> "${lang}_$country"
                }
            }.getOrDefault("unknown")

    /** IANA timezone, e.g. "America/New_York". */
    val timezone: String
        get() =
            runCatching { TimeZone.getDefault().id.ifBlank { "unknown" } }
                .getOrDefault("unknown")

    private fun currentResourcesLocale(): Locale {
        val cfg: Configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cfg.locales[0] ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            cfg.locale ?: Locale.getDefault()
        }
    }
}
