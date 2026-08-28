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
import android.view.Gravity
import android.view.View
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
 * The watch: the same map, three controls.
 *
 * Zoom is by tapping the map (in) and holding it (out). The physical bottom
 * button is left alone deliberately — on Wear OS it is the same navigation
 * path as swipe-to-dismiss, so an app that grabs it for zoom is an app you
 * cannot reliably get out of.
 *
 * The buttons sit at the middle of the left and right edges, which on a round
 * screen is where there is most room, and are dark discs with white glyphs
 * because a default button on pale map paper is invisible in daylight.
 */
class MainActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var map: BngMapView
    private lateinit var hint: TextView
    private lateinit var recordBtn: TextView
    private var locator: Locator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val trailWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            map.setTrail(TrailStore.points(this@MainActivity))
            paintRecordButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        map = BngMapView(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        fun disc(glyph: String, size: Int, onTap: () -> Unit) = TextView(this).apply {
            text = glyph
            textSize = if (size >= 48) 20f else 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(205, 20, 20, 20))
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener { onTap() }
        }

        // Left edge: recentre — which is where he expected it, and now zooms in too.
        val recentreBtn = disc("◉", 48) { map.recentre() }
        // Right edge: record.
        recordBtn = disc("●", 48) { toggleRecording() }
        // Bottom, smaller: flip the route's direction arrows.
        val reverseBtn = disc("⇄", 38) {
            routeReversed = !routeReversed
            map.routeReversed = routeReversed
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
            addView(recordBtn, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(6)
            })
            addView(reverseBtn, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(10)
            })
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(26) })
        }
        setContentView(root)

        map.routeReversed = routeReversed
        map.setColours(routeColour, arrowColour, trailColour)

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

    private fun toggleRecording() {
        val turningOn = !recording
        if (turningOn) {
            TrailStore.clear(this)
            map.setTrail(emptyList())
            TrackingService.start(this)
        } else {
            TrackingService.stop(this)
        }
        recording = turningOn
        wantRecording = turningOn
        paintRecordButton()
        // One press covers both devices.
        scope.launch {
            try {
                Sync.sendRecording(this@MainActivity, turningOn)
            } catch (e: Exception) {
                // The phone is out of range; this watch still records itself.
            }
        }
    }

    private fun paintRecordButton() {
        recordBtn.text = if (recording) "■" else "●"
        (recordBtn.background as GradientDrawable).setColor(
            if (recording) Color.argb(220, 200, 30, 30) else Color.argb(205, 20, 20, 20),
        )
    }

    // --- lifecycle ----------------------------------------------------------

    override fun onResume() {
        super.onResume()
        map.setRoute(RouteStore.load(this))
        map.setTrail(TrailStore.points(this))
        map.setColours(routeColour, arrowColour, trailColour)
        // A Start pressed on the phone while this app was closed waits here.
        if (wantRecording && !recording) TrackingService.start(this)
        paintRecordButton()
        hint.visibility = when {
            osApiKey.isEmpty() -> { hint.text = "Open Waymark on the phone\nto set the OS map key"; View.VISIBLE }
            RouteStore.load(this) == null -> { hint.text = "Import a GPX on the phone\nand it appears here"; View.VISIBLE }
            else -> View.GONE
        }
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

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        try { unregisterReceiver(trailWatcher) } catch (e: IllegalArgumentException) { }
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
                    val key = data.getString("key")
                    if (!key.isNullOrEmpty()) {
                        osApiKey = key
                        if (hint.text.startsWith("Open Waymark")) hint.visibility = View.GONE
                        map.invalidate()
                    }
                }
                Sync.PATH_STYLE -> {
                    routeColour = data.getInt("route")
                    arrowColour = data.getInt("arrow")
                    trailColour = data.getInt("trail")
                    map.setColours(routeColour, arrowColour, trailColour)
                }
                Sync.PATH_RECORD -> applyRecording(data.getBoolean("on"))
            }
        }
    }

    private fun applyRecording(on: Boolean) {
        if (on == recording) return
        if (on) {
            TrailStore.clear(this)
            map.setTrail(emptyList())
            TrackingService.start(this)
        } else {
            TrackingService.stop(this)
        }
        recording = on
        wantRecording = on
        paintRecordButton()
    }
}
