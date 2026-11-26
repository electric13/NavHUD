package com.e8.navhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Starts [HudService] on boot or package update and self-enables AccessibilityService.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NavHUD_Boot"
        private const val LOCKED_BOOT_DELAY_MS = 8000L
        private const val DEFAULT_BOOT_DELAY_MS = 3000L
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "onReceive: $action")
        val delayMs = if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            LOCKED_BOOT_DELAY_MS
        } else {
            DEFAULT_BOOT_DELAY_MS
        }

        Handler(Looper.getMainLooper()).postDelayed({
            enableA11y(context)
            startService(context)
        }, delayMs)
    }

    private fun startService(context: Context) {
        val canDraw = try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            true
        }
        if (!canDraw) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted")
            return
        }
        try {
            context.startForegroundService(Intent(context, HudService::class.java))
            Log.i(TAG, "HudService start requested")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
        }
    }

    private fun enableA11y(context: Context) {
        SystemSettingsEnabler.enableA11yViaContentResolver(context, TAG, "boot")
        Thread {
            SystemSettingsEnabler.enableA11yViaShell(TAG, "boot")
        }.start()
    }
}
