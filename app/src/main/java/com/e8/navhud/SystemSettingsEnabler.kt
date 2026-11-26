package com.e8.navhud

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Keeps NavHUD's AccessibilityService and NotificationListenerService enabled in Settings.Secure
 * using WRITE_SECURE_SETTINGS via ContentResolver and `/system/bin/sh settings` fallback.
 */
object SystemSettingsEnabler {
    const val A11Y_COMPONENT = "com.e8.navhud/com.e8.navhud.NavAccessibilityService"
    const val NLS_COMPONENT = "com.e8.navhud/com.e8.navhud.NavNotificationListener"

    private const val KEY_A11Y_SERVICES = "enabled_accessibility_services"
    private const val KEY_A11Y_ENABLED = "accessibility_enabled"
    private const val KEY_NLS_SERVICES = "enabled_notification_listeners"

    fun enableA11yViaContentResolver(context: Context, tag: String, reason: String) {
        enableComponentViaContentResolver(
            context = context,
            settingKey = KEY_A11Y_SERVICES,
            component = A11Y_COMPONENT,
            tag = tag,
            logLabel = "a11y-CR[$reason]"
        ) {
            Settings.Secure.putInt(context.contentResolver, KEY_A11Y_ENABLED, 1)
        }
    }

    fun enableNlsViaContentResolver(context: Context, tag: String, reason: String) {
        enableComponentViaContentResolver(
            context = context,
            settingKey = KEY_NLS_SERVICES,
            component = NLS_COMPONENT,
            tag = tag,
            logLabel = "NLS-CR[$reason]"
        )
    }

    fun enableA11yViaShell(tag: String, reason: String) {
        enableComponentViaShell(
            settingKey = KEY_A11Y_SERVICES,
            component = A11Y_COMPONENT,
            tag = tag,
            logLabel = "a11y[$reason]"
        ) {
            ShellHelper.execSh("settings put secure $KEY_A11Y_ENABLED 1")
        }
    }

    fun enableNlsViaShell(tag: String, reason: String) {
        enableComponentViaShell(
            settingKey = KEY_NLS_SERVICES,
            component = NLS_COMPONENT,
            tag = tag,
            logLabel = "NLS[$reason]"
        )
    }

    internal fun appendComponentIfMissing(existing: String?, component: String): String? {
        val current = existing.orEmpty()
        if (current.contains(component)) return null
        return if (current.isBlank() || current == "null") {
            component
        } else {
            "$current:$component"
        }
    }

    private inline fun enableComponentViaContentResolver(
        context: Context,
        settingKey: String,
        component: String,
        tag: String,
        logLabel: String,
        afterWrite: () -> Unit = {}
    ) {
        try {
            val resolver = context.contentResolver
            val updated = appendComponentIfMissing(Settings.Secure.getString(resolver, settingKey), component) ?: return
            Settings.Secure.putString(resolver, settingKey, updated)
            afterWrite()
            Log.i(tag, "$logLabel: enabled")
        } catch (e: Exception) {
            Log.w(tag, "$logLabel: ${e.message}")
        }
    }

    private inline fun enableComponentViaShell(
        settingKey: String,
        component: String,
        tag: String,
        logLabel: String,
        afterWrite: () -> Unit = {}
    ) {
        try {
            val current = ShellHelper.execSh("settings get secure $settingKey").trim()
            val updated = appendComponentIfMissing(current, component) ?: return
            ShellHelper.execSh("settings put secure $settingKey \"$updated\"")
            afterWrite()
            Log.i(tag, "$logLabel: shell done")
        } catch (e: Exception) {
            Log.w(tag, "$logLabel shell: ${e.message}")
        }
    }
}
