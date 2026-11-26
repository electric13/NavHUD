package com.e8.navhud

import android.app.Notification
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens strictly for Yandex.Navigator guidance notifications.
 * Music metadata is delivered solely by WeCarFlow via [HudService] broadcast receiver.
 */
class NavNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "NavHUD_NLS"
        const val YANDEX_NAVI_PKG = "ru.yandex.yandexnavi"

        @Volatile
        var listenerConnected: Boolean = false

        @Volatile
        var instance: NavNotificationListener? = null

        @Volatile
        var onNavUpdate: ((title: String, text: String, subText: String, icon: Icon?) -> Unit)? = null

        @Volatile
        var onNavGone: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        instance = this
        Log.d(TAG, "Listener connected (navigation-only mode)")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerConnected = false
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == YANDEX_NAVI_PKG) {
            extractAndNotify(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != YANDEX_NAVI_PKG) return
        val stillPresent = try {
            activeNotifications?.any { it.packageName == YANDEX_NAVI_PKG } == true
        } catch (_: Exception) {
            false
        }
        if (!stillPresent) {
            onNavGone?.invoke()
        }
    }

    private fun extractAndNotify(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val effectiveText = bigText.ifBlank { text }
        val icon = try {
            notification.getLargeIcon() ?: notification.smallIcon
        } catch (_: Exception) {
            null
        }

        if (title.isBlank() && effectiveText.isBlank()) return
        onNavUpdate?.invoke(title, effectiveText, subText, icon)
    }
}
