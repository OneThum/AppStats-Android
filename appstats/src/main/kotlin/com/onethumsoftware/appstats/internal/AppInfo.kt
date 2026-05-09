// Host app metadata. Mirrors sdk/Sources/AppStats/Core/AppInfo.swift.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

internal class AppInfo(
    private val context: Context,
) {
    /** App marketing version (e.g. "1.4.2"). Falls back to "unknown" if PackageManager fails. */
    val version: String by lazy { resolvePackageInfo()?.versionName ?: "unknown" }

    /** App build number / version code as a string. */
    val buildNumber: String by lazy {
        val pi = resolvePackageInfo() ?: return@lazy "0"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toString()
        }
    }

    /** App package name (e.g. "com.griddle.app"). Used for log/diagnostics only. */
    val packageName: String get() = context.packageName.orEmpty()

    private fun resolvePackageInfo(): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Logger.warning("PackageInfo lookup failed for ${context.packageName}", e)
            null
        }
}
