// Sample activity exercising the SDK's public API.
// Copyright © 2026 One Thum Software

package com.onethumsoftware.appstats.sample

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onethumsoftware.appstats.AppStats
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.track_event).setOnClickListener {
            AppStats.track(
                "sample_button_tap",
                mapOf("source" to "main", "n" to System.currentTimeMillis()),
            )
        }

        findViewById<Button>(R.id.flush_now).setOnClickListener {
            lifecycleScope.launch { AppStats.flushAsync() }
        }

        findViewById<Button>(R.id.crash_now).setOnClickListener {
            error("AppStats sample-app intentional crash")
        }
    }
}
