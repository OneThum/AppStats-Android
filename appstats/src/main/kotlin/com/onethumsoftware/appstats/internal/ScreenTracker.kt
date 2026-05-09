// Auto-tracks Activity-driven screen views.
// Mirrors sdk/Sources/AppStats/Tracking/ScreenTracker.swift in spirit; on Android we use
// the documented Application.ActivityLifecycleCallbacks API rather than any kind of
// bytecode rewriting.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ScreenTracker(
    private val onScreen: (String) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        val name = activity.javaClass.simpleName.ifBlank { activity.javaClass.name }
        onScreen(name)
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
