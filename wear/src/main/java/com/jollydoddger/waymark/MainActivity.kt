package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.Locator
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The watch: the same map, nearly no chrome. The bottom (Back) button is the
 * whole control scheme — single press zoom in, double press zoom out, long
 * press leaves the app. (The top button is the Home key; the OS keeps it.)
 * Swiping right from the left edge still exits too, as watches do.
 */
class MainActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var map: BngMapView
    private lateinit var hint: TextView
    private lateinit var recentreBtn: Button
    private var locator: Locator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingle: Runnable? = null
    private var longPressUsed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        map = BngMapView(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val reverseBtn = Button(this).apply {
            text = "⇄"
            textSize = 16f
            setOnClickListener {
                routeReversed = !routeReversed
                map.routeReversed = routeReversed
            }
        }
        recentreBtn = Button(this).apply {
            text = "◉"
            textSize = 16f
            visibility = View.GONE
            setOnClickListener { map.recentre() }
        }

        hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(reverseBtn, FrameLayout.LayoutParams(dp(44), dp(36), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(24); bottomMargin = dp(8)
            })
            addView(recentreBtn, FrameLayout.LayoutParams(dp(44), dp(36), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(24); bottomMargin = dp(8)
            })
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(24) })
        }
        setContentView(root)

        map.routeReversed = routeReversed
        map.onFollowChanged = { following ->
            recentreBtn.visibility = if (following) View.GONE else View.VISIBLE
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1,
            )
        }
    }

    // --- the bottom button --------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            longPressUsed = true
            finish()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        if (longPressUsed) { longPressUsed = false; return true }
        val pending = pendingSingle
        if (pending != null) {
            // Second press inside the window: it was a double — zoom out.
            handler.removeCallbacks(pending)
            pendingSingle = null
            map.zoomOut()
        } else {
            // Wait one beat to see whether a second press follows.
            val r = Runnable { pendingSingle = null; map.zoomIn() }
            pendingSingle = r
            handler.postDelayed(r, 300)
        }
        return true
    }

    // --- lifecycle ----------------------------------------------------------

    override fun onResume() {
        super.onResume()
        map.setRoute(RouteStore.load(this))
        hint.visibility = when {
            osApiKey.isEmpty() -> { hint.text = "Open Waymark on the phone\nto set the OS map key"; View.VISIBLE }
            RouteStore.load(this) == null -> { hint.text = "Import a GPX on the phone\nand it appears here"; View.VISIBLE }
            else -> View.GONE
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locator = Locator(this, { en, stale -> map.setFix(en.e, en.n, stale) }, { map.setHeading(it) })
                .also { it.start() }
        }
        Wearable.getDataClient(this).addListener(this)
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
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
                    val key = DataMapItem.fromDataItem(item).dataMap.getString("key")
                    if (!key.isNullOrEmpty()) {
                        osApiKey = key
                        if (hint.text.startsWith("Open Waymark")) hint.visibility = View.GONE
                        map.invalidate()
                    }
                }
            }
        }
    }
}
