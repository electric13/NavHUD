package com.e8.navhud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads Yandex.Navigator UI hierarchy via AccessibilityService and pushes structured [NavData] to [HudService].
 */
class NavAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "NavHUD_A11y"
        const val YANDEX_NAVI_PKG = "ru.yandex.yandexnavi"

        const val ACTION_NAV_DATA = "com.e8.navhud.NAV_DATA"
        const val ACTION_NAV_GONE = "com.e8.navhud.NAV_GONE"

        const val EXTRA_SPEED = "speed"
        const val EXTRA_SPEED_LIMIT = "speedLimit"
        const val EXTRA_MANEUVER = "maneuver"
        const val EXTRA_DIST = "dist"
        const val EXTRA_STREET = "street"
        const val EXTRA_NEXT_STREET = "nextStreet"
        const val EXTRA_ETA = "eta"
        const val EXTRA_TOTAL_DIST = "totalDist"
        const val EXTRA_TRAVEL_TIME = "travelTime"

        private const val SCAN_INTERVAL_MS = 500L
        private const val POLL_INTERVAL_MS = 2000L

        @Volatile
        var onNavDataDirect: ((NavData) -> Unit)? = null

        @Volatile
        var onNavGoneDirect: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastNavData: NavData? = null
    private var yanWasVisible = false
    private var lastScanTime = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            scanYanWindows()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "NavAccessibilityService connected")
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
            Log.i(TAG, "a11y flags set: ${info.flags}")
        } catch (e: Exception) {
            Log.w(TAG, "serviceInfo update: ${e.message}")
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()
        if (now - lastScanTime < SCAN_INTERVAL_MS) return
        lastScanTime = now
        scanYanWindows()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "NavAccessibilityService destroyed")
    }

    private fun scanYanWindows() {
        try {
            val activeWindows = try {
                windows.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            for (window in activeWindows) {
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                }
                if (root?.packageName?.toString() == YANDEX_NAVI_PKG) {
                    processYanRoot(root)
                    return
                }
            }

            if (yanWasVisible) {
                yanWasVisible = false
                val directGone = onNavGoneDirect
                if (directGone != null) {
                    directGone.invoke()
                } else {
                    sendBroadcast(Intent(ACTION_NAV_GONE).setPackage(packageName))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan: ${e.message}")
        }
    }

    private fun processYanRoot(root: AccessibilityNodeInfo) {
        try {
            val nodes = mutableListOf<AccessibilityNodeSnapshot>()
            collectNodes(root, depth = 0, out = nodes)

            val parsed = NavAccessibilityParser.parseNavData(nodes)
            val hadPreviousRoute = lastNavData?.hasRoute == true
            if ((parsed.hasRoute || parsed.hasSpeed || hadPreviousRoute) && parsed != lastNavData) {
                lastNavData = parsed
                yanWasVisible = true
                val directCallback = onNavDataDirect
                if (directCallback != null) {
                    directCallback.invoke(parsed)
                    return
                }
                val intent = Intent(ACTION_NAV_DATA).setPackage(packageName).apply {
                    putExtra(EXTRA_MANEUVER, parsed.maneuverDesc)
                    putExtra(EXTRA_DIST, parsed.distToManeuver)
                    putExtra(EXTRA_STREET, parsed.currentStreet)
                    putExtra(EXTRA_NEXT_STREET, parsed.nextStreet)
                    putExtra(EXTRA_SPEED, parsed.speed)
                    putExtra(EXTRA_SPEED_LIMIT, parsed.speedLimit)
                    putExtra(EXTRA_ETA, parsed.eta)
                    putExtra(EXTRA_TOTAL_DIST, parsed.totalDistance)
                    putExtra(EXTRA_TRAVEL_TIME, parsed.travelTime)
                }
                try {
                    sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "broadcast: ${e.message}")
                }
            } else if (!yanWasVisible && nodes.isNotEmpty()) {
                yanWasVisible = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "processYanRoot: ${e.message}")
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<AccessibilityNodeSnapshot>
    ) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        if (text.isNotBlank() || desc.isNotBlank()) {
            out.add(AccessibilityNodeSnapshot(depth = depth, cls = cls, text = text, desc = desc))
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                collectNodes(child, depth + 1, out)
            }
        }
    }
}
