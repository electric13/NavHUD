package com.e8.navhud

import android.app.Notification
import android.util.Log

/**
 * Fallback poller reading active Yandex.Navigator notifications via `dumpsys notification`.
 */
class NavInfoPoller(
    private val onUpdate: (title: String, text: String, subText: String) -> Unit,
    private val onGone: () -> Unit
) {
    companion object {
        private const val TAG = "NavHUD_Poller"
        private const val POLL_INTERVAL_MS = 3000L
        private const val STARTUP_DELAY_MS = 3000L
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                Thread.sleep(STARTUP_DELAY_MS)
                Log.d(TAG, "Poller started")
                var lastTitle = ""
                var lastText = ""
                var active = false

                while (running && !Thread.currentThread().isInterrupted) {
                    try {
                        var dump = ShellHelper.execSh(
                            "dumpsys notification --noredact 2>/dev/null | grep -A 25 'pkg=ru.yandex.yandexnavi'"
                        )
                        if (dump.isBlank()) {
                            dump = ShellHelper.execSh(
                                "dumpsys notification 2>/dev/null | grep -A 25 'pkg=ru.yandex.yandexnavi'"
                            )
                        }

                        if (dump.isNotBlank()) {
                            val title = extractField(dump, Notification.EXTRA_TITLE)
                            val text = extractField(dump, Notification.EXTRA_TEXT)
                            val subText = extractField(dump, Notification.EXTRA_SUB_TEXT)

                            if (title.isNotBlank() || text.isNotBlank()) {
                                if (title != lastTitle || text != lastText) {
                                    lastTitle = title
                                    lastText = text
                                    onUpdate(title, text, subText)
                                }
                                active = true
                            }
                        } else if (active) {
                            active = false
                            lastTitle = ""
                            lastText = ""
                            onGone()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "poll error: ${e.message}")
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
            } finally {
                running = false
            }
        }.apply {
            name = "NavHUD-Poller"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun extractField(dump: String, field: String): String {
        val prefix = "$field="
        for (line in dump.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(prefix)) {
                val rawValue = trimmed.substringAfter(prefix)
                return if (rawValue.startsWith("String (") && rawValue.endsWith(")")) {
                    rawValue.removePrefix("String (").removeSuffix(")")
                } else {
                    rawValue
                }
            }
        }
        return ""
    }
}
