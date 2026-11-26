package com.e8.navhud

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.geely.platformapi.binder.IConnectable
import com.geely.platformapi.car.Car
import com.geely.platformapi.car.ICar
import com.geely.platformapi.car.sensor.ISensor
import geely.car.GeelyCar
import geely.car.IGeelyCar
import geely.car.hardware.signal.CarSignalManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service hosting the 800x480 secondary-display HUD overlay:
 * - Yandex.Navigator turn-by-turn pointers, distances, streets, speed limit & ETA
 * - WeCarFlow media track + cover art strictly via [HudMediaPayload.ACTION_GEELY_NAVHUD_UPDATE]
 * - Geely E8 HV battery SoC % & turn-signal blink indicators + clock
 * - Yandex.Navigator keep-alive & call-time microphone suppression
 */
class HudService : Service() {
    companion object {
        private const val TAG = "NavHUD_Svc"
        private const val YANDEX_NAVI_PKG = "ru.yandex.yandexnavi"
        private const val HUD_NOTIFICATION_CHANNEL = "hud_ch"
        private const val HUD_NOTIFICATION_ID = 1

        private const val HUD_AUTO_HIDE_MS = 20_000L
        private const val YAN_KEEP_ALIVE_MS = 30_000L
        private const val CLOCK_UPDATE_MS = 60_000L
        private const val MIC_POLL_MS = 2_000L
        private const val TURN_POLL_MS = 80L
        private const val TURN_HOLD_MS = 700L
        private const val TURN_ANIM_MS = 90L
        private const val SOC_POLL_MS = 10_000L

        private const val COLOR_BATTERY_OK = 0xFF00E676.toInt()
        private const val COLOR_BATTERY_LOW = 0xFFFF9100.toInt()
        private const val COLOR_BATTERY_CRITICAL = 0xFFFF1744.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var navPoller: NavInfoPoller? = null
    private var hudNavActive = false
    private var routeSeen = false

    private var hudDisplayWm: WindowManager? = null
    private var hudDisplayView: View? = null
    private var hudDisplayVisible = false

    private var hudDisplayArrow: TextView? = null
    private var hudDisplayArrowImage: ImageView? = null
    private var hudDisplayDistance: TextView? = null
    private var hudDisplayTitle: TextView? = null
    private var hudDisplaySubtitle: TextView? = null
    private var hudDisplaySpeedLimit: TextView? = null
    private var hudDisplaySpeedLimitContainer: FrameLayout? = null
    private var hudDisplayStreet: TextView? = null
    private var hudDisplayEta: TextView? = null
    private var hudDisplayStatusRight: TextView? = null
    private var hudDisplayMusicTitle: TextView? = null
    private var hudDisplayMusicArtist: TextView? = null
    private var hudDisplayMusicArt: ImageView? = null
    private var hudDisplayMusicArtPlaceholder: TextView? = null
    private var hudMusicRow: ViewGroup? = null
    private var hudBatteryLevel: TextView? = null
    private var hudBatteryIcon: ImageView? = null
    private var hudTurnLeft: TextView? = null
    private var hudTurnRight: TextView? = null

    private var geelyCarApi: ICar? = null
    private var geelySignalMgr: CarSignalManager? = null
    private var signalHandlerThread: HandlerThread? = null
    private var signalHandler: Handler? = null
    private var lastBattPct = 0
    private var lastTurnState = 0
    private var lastLeOnMs = 0L
    private var lastRiOnMs = 0L
    private var blinkPhase = false
    @Volatile
    private var micDenied = false

    private val hudMusicSink = object : HudMusicSink<Bitmap> {
        override fun showTrackText(title: String, artist: String) {
            updateMusicDisplay(title, artist)
        }

        override fun applyArt(bitmap: Bitmap?) {
            applyArtBitmap(bitmap)
        }

        override fun clearMusicDisplay() {
            this@HudService.clearMusicDisplay()
        }
    }

    private val hudMediaController = HudMediaController(
        sink = hudMusicSink,
        decodeBytes = { bytes ->
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.w(TAG, "art bytes decode fail: ${e.message}")
                null
            }
        },
        loadUriAsync = { uri, onResult ->
            Thread {
                val bmp = loadBitmapFromUri(uri)
                mainHandler.post { onResult(bmp) }
            }.start()
        }
    )

    private val hideHudRunnable = object : Runnable {
        override fun run() {
            if (hudNavActive && isNavAppRunning()) {
                mainHandler.postDelayed(this, HUD_AUTO_HIDE_MS)
            } else {
                hideHud()
            }
        }
    }

    private val updateClockRunnable = object : Runnable {
        override fun run() {
            hudDisplayStatusRight?.text = timeFormat.format(Date())
            mainHandler.postDelayed(this, CLOCK_UPDATE_MS)
        }
    }

    private val yanKeeperRunnable = object : Runnable {
        override fun run() {
            Thread { keepYanAlive("periodic") }.start()
            mainHandler.postDelayed(this, YAN_KEEP_ALIVE_MS)
        }
    }

    private val micPollRunnable = object : Runnable {
        override fun run() {
            try {
                val telecom = getSystemService(TELECOM_SERVICE) as? TelecomManager
                if (telecom != null) {
                    val inCall = telecom.isInManagedCall
                    if (inCall && !micDenied) {
                        Thread { ShellHelper.execSh("appops set $YANDEX_NAVI_PKG RECORD_AUDIO deny") }.start()
                        micDenied = true
                        Log.i(TAG, "MicPoll: denied RECORD_AUDIO for yandexnavi (call active)")
                    } else if (!inCall && micDenied) {
                        Thread { ShellHelper.execSh("appops set $YANDEX_NAVI_PKG RECORD_AUDIO allow") }.start()
                        micDenied = false
                        Log.i(TAG, "MicPoll: restored RECORD_AUDIO for yandexnavi (call ended)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MicPoll error: ${e.message}")
            }
            mainHandler.postDelayed(this, MIC_POLL_MS)
        }
    }

    private val turnPollRunnable = object : Runnable {
        override fun run() {
            pollTurnSignals()
            signalHandler?.postDelayed(this, TURN_POLL_MS)
        }
    }

    private val socPollRunnable = object : Runnable {
        override fun run() {
            try {
                val pct = geelyCarApi
                    ?.getSensorManager()
                    ?.getSensorLatestValue(ISensor.SENSOR_TYPE_EV_BATTERY_PERCENT)
                    ?.let { Math.round(it) }
                    ?: 0
                if (pct in 1..100) {
                    if (pct != lastBattPct) {
                        Log.i(TAG, "vehicle SOC=$pct")
                    }
                    updateBatteryDisplay(pct)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "vehicle SOC poll error", t)
            }
            mainHandler.postDelayed(this, SOC_POLL_MS)
        }
    }

    private val a11yObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            SystemSettingsEnabler.enableA11yViaContentResolver(this@HudService, TAG, "observer")
        }
    }

    private val navDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                NavAccessibilityService.ACTION_NAV_DATA -> {
                    val data = NavData(
                        maneuverDesc = intent.getStringExtra(NavAccessibilityService.EXTRA_MANEUVER).orEmpty(),
                        distToManeuver = intent.getStringExtra(NavAccessibilityService.EXTRA_DIST).orEmpty(),
                        currentStreet = intent.getStringExtra(NavAccessibilityService.EXTRA_STREET).orEmpty(),
                        nextStreet = intent.getStringExtra(NavAccessibilityService.EXTRA_NEXT_STREET).orEmpty(),
                        speed = intent.getStringExtra(NavAccessibilityService.EXTRA_SPEED).orEmpty(),
                        speedLimit = intent.getStringExtra(NavAccessibilityService.EXTRA_SPEED_LIMIT).orEmpty(),
                        eta = intent.getStringExtra(NavAccessibilityService.EXTRA_ETA).orEmpty(),
                        totalDistance = intent.getStringExtra(NavAccessibilityService.EXTRA_TOTAL_DIST).orEmpty(),
                        travelTime = intent.getStringExtra(NavAccessibilityService.EXTRA_TRAVEL_TIME).orEmpty()
                    )
                    mainHandler.post { showHudFromNavData(data) }
                }

                NavAccessibilityService.ACTION_NAV_GONE -> {
                    mainHandler.post { hideHud() }
                }
            }
        }
    }

    private val navHudMediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HudMediaPayload.ACTION_GEELY_NAVHUD_UPDATE) return
            val payload = HudMediaPayload.fromIntent(intent)
            Log.i(
                TAG,
                "navHudMediaReceiver: title='${payload.title}', artist='${payload.artist}', " +
                    "cover='${payload.coverUri}', bytes=${payload.coverBytes?.size ?: 0}, playing=${payload.isPlaying}"
            )
            mainHandler.post { hudMediaController.onMediaUpdate(payload) }
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "SCREEN_ON — scheduling a11y re-check + overlay recovery")
            mainHandler.postDelayed({
                Thread {
                    SystemSettingsEnabler.enableA11yViaShell(TAG, "screenOn")
                    keepYanAlive("screenOn")
                }.start()
                SystemSettingsEnabler.enableA11yViaContentResolver(this@HudService, TAG, "screenOn")
                checkAndRecreateHudOverlay()
            }, 2000L)

            mainHandler.postDelayed({
                Thread {
                    SystemSettingsEnabler.enableA11yViaShell(TAG, "screenOn+6s")
                    keepYanAlive("screenOn+6s")
                }.start()
                SystemSettingsEnabler.enableA11yViaContentResolver(this@HudService, TAG, "screenOn+6s")
            }, 6000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(HUD_NOTIFICATION_ID, buildNotification())

        createHudDisplay()
        showBaseHudOverlay()

        registerNavCallbacks()
        enableAccessibilityAndNotificationListener()
        startPollerFallback()

        Thread { startVehicleMonitor() }.start()

        val navFilter = IntentFilter().apply {
            addAction(NavAccessibilityService.ACTION_NAV_DATA)
            addAction(NavAccessibilityService.ACTION_NAV_GONE)
        }
        val mediaFilter = IntentFilter(HudMediaPayload.ACTION_GEELY_NAVHUD_UPDATE)
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navDataReceiver, navFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(navHudMediaReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
            registerReceiver(screenOnReceiver, screenFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(navDataReceiver, navFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(navHudMediaReceiver, mediaFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOnReceiver, screenFilter)
        }

        mainHandler.postDelayed(updateClockRunnable, 0L)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_accessibility_services"),
            false,
            a11yObserver
        )
        Thread { keepYanAlive("init") }.start()
        mainHandler.postDelayed(micPollRunnable, MIC_POLL_MS)
        mainHandler.postDelayed(yanKeeperRunnable, YAN_KEEP_ALIVE_MS)
        requestHudMetadata()
        mainHandler.postDelayed({ requestHudMetadata() }, 2000L)
        Log.i(TAG, "HudService started (WeCarFlow HUD mode)")
    }

    override fun onDestroy() {
        super.onDestroy()
        NavAccessibilityService.onNavDataDirect = null
        NavAccessibilityService.onNavGoneDirect = null
        NavNotificationListener.onNavUpdate = null
        NavNotificationListener.onNavGone = null
        navPoller?.stop()

        try {
            unregisterReceiver(navDataReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(navHudMediaReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: Exception) {
        }
        try {
            contentResolver.unregisterContentObserver(a11yObserver)
        } catch (_: Exception) {
        }

        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.removeCallbacks(updateClockRunnable)
        mainHandler.removeCallbacks(yanKeeperRunnable)
        mainHandler.removeCallbacks(micPollRunnable)

        stopVehicleMonitor()

        if (micDenied) {
            ShellHelper.execSh("appops set $YANDEX_NAVI_PKG RECORD_AUDIO allow")
            micDenied = false
        }

        hudMediaController.clear()

        try {
            hudDisplayView?.let { hudDisplayWm?.removeView(it) }
        } catch (_: Exception) {
        }
        hudDisplayView = null
        hudDisplayWm = null
        hudDisplayVisible = false
        Log.i(TAG, "HudService destroyed")
    }

    private fun createHudDisplay() {
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val hudDisplay = selectHudDisplay(dm)
            if (hudDisplay == null) {
                Log.w(TAG, "HUD display not found")
                return
            }

            val mode = hudDisplay.mode
            Log.i(TAG, "HUD display found: id=${hudDisplay.displayId} ${mode.physicalWidth}x${mode.physicalHeight}")
            val displayContext = createDisplayContext(hudDisplay)
            val wm = displayContext.getSystemService(WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(displayContext).inflate(R.layout.view_hud_display, null)

            hudDisplayWm = wm
            hudDisplayView = view
            bindHudViews(view)
            updateBatteryDisplay(lastBattPct)
            refreshTurnVisibility()

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.OPAQUE
            )
            view.visibility = View.GONE

            try {
                wm.addView(view, params)
                Log.i(TAG, "HUD overlay added (TYPE_APPLICATION_OVERLAY)")
            } catch (e: Exception) {
                Log.w(TAG, "TYPE_APPLICATION_OVERLAY failed: ${e.message}, trying TYPE_SYSTEM_OVERLAY")
                try {
                    @Suppress("DEPRECATION")
                    params.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                    wm.addView(view, params)
                    Log.i(TAG, "HUD overlay added (TYPE_SYSTEM_OVERLAY)")
                } catch (e2: Exception) {
                    Log.e(TAG, "HUD overlay failed: ${e2.message}")
                    hudDisplayView = null
                    hudDisplayWm = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createHudDisplay: ${e.message}", e)
        }
    }

    private fun selectHudDisplay(dm: DisplayManager): Display? {
        dm.getDisplay(2)?.let { return it }
        val all = dm.displays
        return all.firstOrNull { d ->
            d.displayId != Display.DEFAULT_DISPLAY &&
                d.mode.physicalWidth == 800 &&
                d.mode.physicalHeight == 480
        } ?: all.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun bindHudViews(root: View) {
        hudDisplayArrow = root.findViewById(R.id.hudDisplayArrow)
        hudDisplayArrowImage = root.findViewById(R.id.hudDisplayArrowImage)
        hudDisplayDistance = root.findViewById(R.id.hudDisplayDistance)
        hudDisplayTitle = root.findViewById(R.id.hudDisplayTitle)
        hudDisplaySubtitle = root.findViewById(R.id.hudDisplaySubtitle)
        hudDisplaySpeedLimit = root.findViewById(R.id.hudDisplaySpeedLimit)
        hudDisplaySpeedLimitContainer = root.findViewById(R.id.hudDisplaySpeedLimitContainer)
        hudDisplayStreet = root.findViewById(R.id.hudDisplayStreet)
        hudDisplayEta = root.findViewById(R.id.hudDisplayEta)
        hudDisplayStatusRight = root.findViewById(R.id.hudDisplayStatusRight)
        hudDisplayMusicTitle = root.findViewById(R.id.hudDisplayMusicTitle)
        hudDisplayMusicArtist = root.findViewById(R.id.hudDisplayMusicArtist)
        hudDisplayMusicArt = root.findViewById(R.id.hudDisplayMusicArt)
        hudDisplayMusicArtPlaceholder = root.findViewById(R.id.hudDisplayMusicArtPlaceholder)
        hudMusicRow = root.findViewById(R.id.hudMusicRow)
        hudBatteryLevel = root.findViewById(R.id.hudBatteryLevel)
        hudBatteryIcon = root.findViewById(R.id.hudBatteryIcon)
        hudTurnLeft = root.findViewById(R.id.hudTurnLeft)
        hudTurnRight = root.findViewById(R.id.hudTurnRight)
    }

    private fun showBaseHudOverlay() {
        val view = hudDisplayView ?: return
        view.visibility = View.VISIBLE
        hudDisplayVisible = true
        hudDisplayStatusRight?.text = timeFormat.format(Date())
    }

    private fun checkAndRecreateHudOverlay() {
        try {
            val view = hudDisplayView
            if (view == null || view.windowToken == null) {
                Log.w(TAG, "HUD overlay detached after wake — recreating")
                try {
                    view?.let { hudDisplayWm?.removeView(it) }
                } catch (_: Exception) {
                }
                hudDisplayView = null
                hudDisplayWm = null
                hudDisplayVisible = false
                createHudDisplay()
            }
        } catch (e: Exception) {
            Log.w(TAG, "overlay check: ${e.message}")
            hudDisplayView = null
            hudDisplayWm = null
            hudDisplayVisible = false
            createHudDisplay()
        }
        showBaseHudOverlay()
        hudMediaController.replayLastState()
        requestHudMetadata()
    }

    private fun requestHudMetadata() {
        try {
            sendBroadcast(
                Intent(HudMediaPayload.ACTION_NAVHUD_REQUEST_METADATA).apply {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        } catch (e: Exception) {
            Log.d(TAG, "requestHudMetadata failed: ${e.message}")
        }
    }

    private fun registerNavCallbacks() {
        NavAccessibilityService.onNavDataDirect = { data ->
            mainHandler.post { showHudFromNavData(data) }
        }
        NavAccessibilityService.onNavGoneDirect = {
            mainHandler.post { hideHud() }
        }
        NavNotificationListener.onNavUpdate = { title, text, subText, _ ->
            mainHandler.post { showHud(title, text, subText) }
        }
        NavNotificationListener.onNavGone = {
            mainHandler.post { hideHud() }
        }
        Log.i(TAG, "Nav callbacks registered")
    }

    private fun enableAccessibilityAndNotificationListener() {
        SystemSettingsEnabler.enableA11yViaContentResolver(this, TAG, "init")
        SystemSettingsEnabler.enableNlsViaContentResolver(this, TAG, "init")

        Thread {
            SystemSettingsEnabler.enableA11yViaShell(TAG, "init")
            SystemSettingsEnabler.enableNlsViaShell(TAG, "init")
        }.start()

        mainHandler.postDelayed({
            Thread {
                SystemSettingsEnabler.enableA11yViaContentResolver(this, TAG, "retry@2s")
                SystemSettingsEnabler.enableNlsViaContentResolver(this, TAG, "retry@2s")
                SystemSettingsEnabler.enableA11yViaShell(TAG, "retry@2s")
                SystemSettingsEnabler.enableNlsViaShell(TAG, "retry@2s")
            }.start()
        }, 2000L)

        mainHandler.postDelayed({
            Thread {
                SystemSettingsEnabler.enableA11yViaShell(TAG, "retry@8s")
                SystemSettingsEnabler.enableNlsViaShell(TAG, "retry@8s")
            }.start()
        }, 8000L)
    }

    private fun startPollerFallback() {
        navPoller = NavInfoPoller(
            onUpdate = { title, text, subText ->
                mainHandler.post { showHud(title, text, subText) }
            },
            onGone = {
                mainHandler.post { hideHud() }
            }
        ).also { it.start() }
    }

    private fun showHudFromNavData(data: NavData) {
        val hasRoute = data.hasRoute
        hudNavActive = hasRoute
        routeSeen = hasRoute
        updateHudDisplayFromNavData(data)
        if (!hasRoute) {
            hideHud()
            return
        }
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS)
        Thread { keepYanAlive("navData") }.start()
    }

    private fun showHud(title: String, text: String, subText: String) {
        if (!NavAccessibilityParser.isGuidanceText(title, text, subText)) return
        hudNavActive = true
        routeSeen = true
        updateHudDisplay(title, text, subText)
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS)
    }

    private fun setHudArrow(maneuverText: String) {
        val iconRes = ManeuverMapper.resolveDrawableRes(maneuverText)
        if (iconRes != 0) {
            hudDisplayArrowImage?.apply {
                setImageResource(iconRes)
                rotation = 0.0f
                visibility = View.VISIBLE
            }
            hudDisplayArrow?.visibility = View.GONE
        } else if (maneuverText.isBlank()) {
            hudDisplayArrow?.visibility = View.GONE
            hudDisplayArrowImage?.visibility = View.GONE
        } else {
            hudDisplayArrow?.apply {
                this.text = ManeuverMapper.getFallbackArrowGlyph(maneuverText)
                visibility = View.VISIBLE
            }
            hudDisplayArrowImage?.visibility = View.GONE
        }
    }

    private fun updateHudDisplay(title: String, text: String, subText: String) {
        val view = hudDisplayView ?: return
        setHudArrow(title)
        val (dist, _) = NavAccessibilityParser.splitDistanceAndManeuver(title)
        hudDisplayDistance?.text = dist
        hudDisplaySubtitle?.text = subText.ifBlank { text }
        hudDisplayStreet?.text = text.ifBlank { "" }
        hudDisplayEta?.text = subText
        hudDisplaySpeedLimitContainer?.visibility = View.GONE
        hudDisplayStatusRight?.text = timeFormat.format(Date())
        if (!hudDisplayVisible) {
            view.visibility = View.VISIBLE
            hudDisplayVisible = true
        }
    }

    private fun updateHudDisplayFromNavData(data: NavData) {
        val view = hudDisplayView ?: return
        setHudArrow(data.maneuverDesc)
        hudDisplayDistance?.text = data.distToManeuver
        hudDisplayTitle?.text = data.maneuverDesc
        hudDisplaySubtitle?.text = data.nextStreet

        if (data.speedLimit.isNotBlank()) {
            hudDisplaySpeedLimit?.text = data.speedLimit
            hudDisplaySpeedLimitContainer?.visibility = View.VISIBLE
        } else {
            hudDisplaySpeedLimitContainer?.visibility = View.GONE
        }

        hudDisplayStreet?.text = if (data.hasRoute) data.currentStreet else ""

        val footer = buildList {
            if (data.eta.isNotBlank()) add(data.eta)
            if (data.totalDistance.isNotBlank()) add(data.totalDistance)
            if (data.travelTime.isNotBlank()) add(data.travelTime)
        }.joinToString(" · ")
        hudDisplayEta?.text = footer
        hudDisplayStatusRight?.text = timeFormat.format(Date())

        if (!hudDisplayVisible) {
            view.visibility = View.VISIBLE
            hudDisplayVisible = true
            Log.i(TAG, "HUD shown (a11y)")
        }
    }

    private fun clearNavDisplay() {
        hudDisplayArrow?.visibility = View.GONE
        hudDisplayArrowImage?.visibility = View.GONE
        hudDisplaySpeedLimitContainer?.visibility = View.GONE
        hudDisplayDistance?.text = null
        hudDisplayStreet?.text = null
        hudDisplaySubtitle?.text = null
        hudDisplayEta?.text = null
        hudDisplayTitle?.text = null
    }

    private fun hideHud() {
        mainHandler.removeCallbacks(hideHudRunnable)
        hudNavActive = false
        routeSeen = false
        clearNavDisplay()
    }

    private fun updateMusicDisplay(title: String, artist: String) {
        hudDisplayMusicTitle?.text = title
        hudDisplayMusicArtist?.apply {
            text = artist
            visibility = if (artist.isNotBlank()) View.VISIBLE else View.GONE
        }
        hudMusicRow?.visibility = View.VISIBLE
        hudDisplayStatusRight?.text = timeFormat.format(Date())
        if (!hudDisplayVisible) {
            hudDisplayView?.let {
                it.visibility = View.VISIBLE
                hudDisplayVisible = true
                Log.i(TAG, "HUD shown (music only)")
            }
        }
    }

    private fun clearMusicDisplay() {
        hudDisplayMusicTitle?.text = ""
        hudDisplayMusicArtist?.text = ""
        hudMusicRow?.visibility = View.GONE
        applyArtBitmap(null)
    }

    private fun applyArtBitmap(bitmap: Bitmap?) {
        val artView = hudDisplayMusicArt
        val placeholder = hudDisplayMusicArtPlaceholder
        if (bitmap != null) {
            artView?.apply {
                setImageBitmap(bitmap)
                visibility = View.VISIBLE
            }
            placeholder?.visibility = View.GONE
        } else {
            artView?.apply {
                setImageDrawable(null)
                visibility = View.GONE
            }
            placeholder?.visibility = View.VISIBLE
        }
    }

    private fun loadBitmapFromUri(uriStr: String): Bitmap? {
        return try {
            when {
                uriStr.startsWith("content://") ||
                    uriStr.startsWith("file://") ||
                    uriStr.startsWith("android.resource://") -> {
                    contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }

                uriStr.startsWith("http://") || uriStr.startsWith("https://") -> {
                    loadHttpBitmap(uriStr, redirectsLeft = 3)
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "art load fail: ${e.message}")
            null
        }
    }

    private fun loadHttpBitmap(urlStr: String, redirectsLeft: Int): Bitmap? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) WeCarFlow-NavHUD")
        return try {
            val code = conn.responseCode
            if (code in 300..399 && redirectsLeft > 0) {
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    val resolved = URL(URL(urlStr), location).toString()
                    return loadHttpBitmap(resolved, redirectsLeft - 1)
                }
            }
            conn.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
        } finally {
            conn.disconnect()
        }
    }

    private fun startVehicleMonitor() {
        try {
            val serviceBinder = ServiceManager.getService(GeelyCar.SERVICE_NAME)
            val igc = serviceBinder?.let { IGeelyCar.Stub.asInterface(it) }
            val car = igc?.let { GeelyCar.createCar(this, it) }
            val sigMgr = car?.getCarManager(GeelyCar.SIGNAL_SERVICE) as? CarSignalManager
            if (sigMgr != null) {
                geelySignalMgr = sigMgr
                val thread = HandlerThread("NavHudSignal").apply { start() }
                signalHandlerThread = thread
                val handler = Handler(thread.looper)
                signalHandler = handler
                handler.postDelayed(turnPollRunnable, 1000L)
                Log.i(TAG, "vehicle turn-signal poller started")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "vehicle: signal manager init failed", t)
        }

        try {
            val car = Car.create(this)
            if (car == null) {
                Log.w(TAG, "vehicle: Geely Car.create returned null")
            } else {
                geelyCarApi = car
                (car as? IConnectable)?.connect()
            }
            mainHandler.postDelayed(socPollRunnable, 3000L)
            Log.i(TAG, "vehicle monitor started")
        } catch (t: Throwable) {
            Log.e(TAG, "vehicle: geely SOC init failed", t)
        }
    }

    private fun stopVehicleMonitor() {
        signalHandler?.removeCallbacks(turnPollRunnable)
        signalHandlerThread?.quitSafely()
        mainHandler.removeCallbacks(socPollRunnable)
        try {
            (geelyCarApi as? IConnectable)?.disconnect()
        } catch (_: Exception) {
        }
        geelyCarApi = null
        geelySignalMgr = null
        signalHandler = null
        signalHandlerThread = null
    }

    private fun pollTurnSignals() {
        try {
            val mgr = geelySignalMgr ?: return
            val le = mgr.getExtrLtgStsTurnIndrLe()
            val ri = mgr.getExtrLtgStsTurnIndrRi()
            val anyRawOn = (le or ri) != 0
            val now = SystemClock.elapsedRealtime()
            if (le != 0) lastLeOnMs = now
            if (ri != 0) lastRiOnMs = now

            val leHold = (now - lastLeOnMs) < TURN_HOLD_MS
            val riHold = (now - lastRiOnMs) < TURN_HOLD_MS
            val turnState = when {
                leHold && riHold -> 3
                leHold -> 1
                riHold -> 2
                else -> 0
            }
            if (turnState != lastTurnState) {
                Log.i(TAG, "vehicle turn=$turnState")
            }
            lastTurnState = turnState
            blinkPhase = if (turnState != 0) anyRawOn else false
            mainHandler.post { refreshTurnVisibility() }
        } catch (t: Throwable) {
            Log.w(TAG, "vehicle turn poll error", t)
        }
    }

    private fun refreshTurnVisibility() {
        val state = lastTurnState
        val phase = blinkPhase
        val showLeft = phase && (state == 1 || state == 3)
        val showRight = phase && (state == 2 || state == 3)

        hudTurnLeft?.let { view ->
            if (showLeft) {
                view.visibility = View.VISIBLE
                view.animate().alpha(1.0f).setDuration(TURN_ANIM_MS).start()
            } else {
                view.alpha = 0.0f
                view.visibility = View.INVISIBLE
            }
        }
        hudTurnRight?.let { view ->
            if (showRight) {
                view.visibility = View.VISIBLE
                view.animate().alpha(1.0f).setDuration(TURN_ANIM_MS).start()
            } else {
                view.alpha = 0.0f
                view.visibility = View.INVISIBLE
            }
        }
    }

    private fun updateBatteryDisplay(pct: Int) {
        lastBattPct = pct
        val levelView = hudBatteryLevel ?: return
        if (pct <= 0) return
        levelView.text = "$pct%"
        val iconView = hudBatteryIcon ?: return
        iconView.visibility = View.VISIBLE
        iconView.drawable?.let { drawable ->
            drawable.level = pct * 100
            val tint = when {
                pct < 10 -> COLOR_BATTERY_CRITICAL
                pct <= 20 -> COLOR_BATTERY_LOW
                else -> COLOR_BATTERY_OK
            }
            drawable.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
        }
    }

    private fun keepYanAlive(reason: String) {
        try {
            ShellHelper.execSh("dumpsys deviceidle whitelist +$YANDEX_NAVI_PKG")
            ShellHelper.execSh("am set-inactive $YANDEX_NAVI_PKG false")
            ShellHelper.execSh("am set-standby-bucket $YANDEX_NAVI_PKG active")
            if (reason == "init") {
                Log.i(TAG, "YaN keepAlive[init]: quick-start (whitelist+bucket)")
                return
            }

            val pidsRaw = ShellHelper.execSh("pidof $YANDEX_NAVI_PKG").trim()
            val pids = pidsRaw.split(Regex("\\s+")).filter { it.isNotBlank() }
            for (pid in pids) {
                val res = ShellHelper.execSh("echo 0 > /proc/$pid/oom_score_adj 2>&1")
                if (res.contains("Permission denied", ignoreCase = true)) {
                    ShellHelper.exec("echo 0 > /proc/$pid/oom_score_adj")
                }
            }

            val fgCount = ShellHelper.execSh(
                "dumpsys activity services $YANDEX_NAVI_PKG 2>/dev/null | grep -c 'isForeground=true'"
            ).trim()
            if ((fgCount == "0" || fgCount.isBlank()) && routeSeen) {
                ShellHelper.execSh(
                    "am startservice $YANDEX_NAVI_PKG/com.yandex.navikit_platform.guidance.service.GuidanceService 2>/dev/null || true"
                )
                Log.i(TAG, "YaN keepAlive[$reason]: re-started GuidanceService (was not foreground)")
            }

            if (pidsRaw.isNotBlank()) {
                Log.i(TAG, "YaN keepAlive[$reason]: whitelist+bucket, pids=$pidsRaw fgSvc=$fgCount")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keepYanAlive[$reason]: ${e.message}")
        }
    }

    private fun isNavAppRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.runningAppProcesses?.any { it.processName == YANDEX_NAVI_PKG } == true
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(HUD_NOTIFICATION_CHANNEL, "NavHUD", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, HUD_NOTIFICATION_CHANNEL)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_bubble)
            .setOngoing(true)
            .build()
    }
}
