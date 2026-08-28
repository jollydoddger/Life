package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.Locator
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.Prefs.screenTimeoutSec
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TrackingService
import com.jollydoddger.waymark.shared.TrailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The watch: the map, and one button.
 *
 * There is room on a 45mm circle for the map and almost nothing else, so
 * everything that can live on the phone does. What remains is ◉ — centre on
 * me and zoom in — plus tapping the map to zoom in and holding it to zoom out.
 * Colours, arrow direction, recording and this screen's timeout are all set on
 * the phone and arrive over the Data Layer.
 */
class MainActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var map: BngMapView
    private lateinit var hint: TextView
    private var locator: Locator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())
    private val sleepScreen = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val trailWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            map.setTrail(TrailStore.points(this@MainActivity))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        map = BngMapView(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Left edge, vertical centre: the widest part of a round screen, and
        // where he expected this button to be. A dark disc with a white glyph,
        // because a default button is invisible against pale map paper.
        val recentreBtn = TextView(this).apply {
            text = "◉"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(205, 20, 20, 20))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener {
                map.recentre()
                keepAwake()
            }
        }

        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(recentreBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(6)
            })
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(26) })
        }
        setContentView(root)

        applySettings()

        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Recording shows an ongoing notification; without this it cannot.
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    /** Everything the phone decides, applied to this screen. */
    private fun applySettings() {
        map.routeReversed = routeReversed
        map.setColours(routeColour, arrowColour, trailColour)
        keepAwake()
    }

    // --- screen timeout -----------------------------------------------------

    /**
     * Hold the screen on, then let it go after the chosen idle time. Holding
     * it for the whole session is a real battery hole on a walk, and Wear's
     * own dimming is perfectly good once we stop overriding it. Any touch
     * re-arms it, so a map being looked at never goes dark.
     *
     * Sleeping does not stop a recording: the trail comes from
     * TrackingService, which runs regardless of this screen.
     */
    private fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(sleepScreen)
        val seconds = screenTimeoutSec
        if (seconds > 0) handler.postDelayed(sleepScreen, seconds * 1000L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) keepAwake()
        return super.dispatchTouchEvent(ev)
    }

    // --- lifecycle ----------------------------------------------------------

    override fun onResume() {
        super.onResume()
        map.setRoute(RouteStore.load(this))
        map.setTrail(TrailStore.points(this))
        applySettings()
        showHint()

        // Ask the Data Layer what the settings actually are, rather than only
        // waiting to be told. A change sent while this app was closed or the
        // watch was asleep would otherwise never land — which is exactly how
        // the colours came to be stuck on the old ones.
        scope.launch {
            try {
                if (Sync.pullAll(this@MainActivity)) {
                    applySettings()
                    showHint()
                    startRecordingIfWanted()
                }
            } catch (e: Exception) {
                // Phone not paired, or Play Services busy: whatever was last
                // received still stands.
            }
        }

        startRecordingIfWanted()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locator = Locator(this, { en, stale -> map.setFix(en.e, en.n, stale) }, { map.setHeading(it) })
                .also { it.start() }
        }
        val filter = IntentFilter(TrackingService.BROADCAST_TRAIL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trailWatcher, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(trailWatcher, filter)
        }
        Wearable.getDataClient(this).addListener(this)
    }

    /**
     * A recording started on the phone begins here the moment the app is
     * opened — Android forbids starting a foreground service from the
     * background, so this is the earliest honest moment.
     */
    private fun startRecordingIfWanted() {
        if (wantRecording && !recording) TrackingService.start(this)
    }

    private fun showHint() {
        hint.visibility = when {
            osApiKey.isEmpty() -> { hint.text = "Open Waymark on the phone\nto set the OS map key"; View.VISIBLE }
            RouteStore.load(this) == null -> { hint.text = "Import a GPX on the phone\nand it appears here"; View.VISIBLE }
            else -> View.GONE
        }
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        try { unregisterReceiver(trailWatcher) } catch (e: IllegalArgumentException) { }
        handler.removeCallbacks(sleepScreen)
        locator?.stop()
        locator = null
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Live arrival while the map is on the wrist and being looked at. */
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val data = DataMapItem.fromDataItem(item).dataMap
            when (item.uri.path) {
                Sync.PATH_ROUTE -> {
                    val mapItem = DataMapItem.fromDataItem(item)
                    scope.launch {
                        Sync.applyRouteItem(this@MainActivity, mapItem)?.let {
                            map.setRoute(it)
                            hint.visibility = View.GONE
                        }
                    }
                }
                Sync.PATH_KEY -> {
                    Sync.applyKey(this, data)
                    showHint()
                    map.invalidate()
                }
                Sync.PATH_STYLE -> {
                    Sync.applyStyle(this, data)
                    applySettings()
                }
                Sync.PATH_RECORD -> {
                    Sync.applyRecordWish(this, data)
                    startRecordingIfWanted()
                }
            }
        }
    }
}
