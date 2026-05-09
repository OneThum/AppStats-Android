// Sample app entry point. Demonstrates manifest-driven auto-init via androidx.startup.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.sample

import android.app.Application

class SampleApplication : Application() {
    // No code required — AppStatsInitializer reads the API_KEY meta-data from the
    // manifest and configures the SDK during the androidx.startup boot phase.
}
