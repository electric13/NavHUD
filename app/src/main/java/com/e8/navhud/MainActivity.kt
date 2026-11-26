package com.e8.navhud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Transparent trampoline activity that verifies overlay permission and starts [HudService].
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {
            }
        }
        startHudServiceAndFinish()
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startHudServiceAndFinish()
        }
    }

    @Suppress("DEPRECATION")
    private fun startHudServiceAndFinish() {
        startForegroundService(Intent(this, HudService::class.java))
        finish()
        overridePendingTransition(0, 0)
    }
}
