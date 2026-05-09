// Manifest-driven auto-initialization via androidx.startup.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats

import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.Initializer
import com.onethumsoftware.appstats.internal.Logger
import kotlin.time.Duration.Companion.seconds

/**
 * Auto-configures AppStats from `<meta-data>` declarations in the host app's
 * AndroidManifest.xml. Consumers who prefer explicit configuration can ignore this
 * — without manifest meta-data the initializer is a no-op.
 *
 * Supported manifest entries (all optional except API_KEY):
 *
 * ```xml
 * <meta-data android:name="com.onethumsoftware.appstats.API_KEY"               android:value="as_live_…"/>
 * <meta-data android:name="com.onethumsoftware.appstats.AUTO_TRACK_SCREENS"    android:value="true"/>
 * <meta-data android:name="com.onethumsoftware.appstats.FLUSH_INTERVAL_SECONDS" android:value="30"/>
 * <meta-data android:name="com.onethumsoftware.appstats.DEBUG_LOGGING"         android:value="false"/>
 * <meta-data android:name="com.onethumsoftware.appstats.BASE_URL"              android:value="https://…"/>
 * ```
 */
public class AppStatsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val meta = readManifestMetadata(context) ?: return
        val apiKey = meta.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() } ?: return

        val configuration =
            AppStatsConfiguration(
                apiKey = apiKey,
                baseUrl =
                    meta
                        .getString(KEY_BASE_URL)
                        ?.takeIf { it.isNotBlank() }
                        ?: AppStatsConfiguration.DEFAULT_BASE_URL,
                autoTrackScreens = meta.getBoolean(KEY_AUTO_TRACK_SCREENS, true),
                flushInterval = meta.getInt(KEY_FLUSH_INTERVAL_SECONDS, 30).coerceAtLeast(5).seconds,
                debugLogging = meta.getBoolean(KEY_DEBUG_LOGGING, false),
            )

        AppStats.configure(context.applicationContext, configuration)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun readManifestMetadata(context: Context): android.os.Bundle? =
        try {
            val pm = context.packageManager
            val info =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(
                        context.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                }
            info.metaData
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.warning("Could not read app meta-data for AppStats auto-init", e)
            null
        }

    private companion object {
        const val KEY_API_KEY = "com.onethumsoftware.appstats.API_KEY"
        const val KEY_BASE_URL = "com.onethumsoftware.appstats.BASE_URL"
        const val KEY_AUTO_TRACK_SCREENS = "com.onethumsoftware.appstats.AUTO_TRACK_SCREENS"
        const val KEY_FLUSH_INTERVAL_SECONDS = "com.onethumsoftware.appstats.FLUSH_INTERVAL_SECONDS"
        const val KEY_DEBUG_LOGGING = "com.onethumsoftware.appstats.DEBUG_LOGGING"
    }
}
