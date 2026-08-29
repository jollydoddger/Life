package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.Corridor
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Glyph
import com.jollydoddger.waymark.shared.IconDrawable
import com.jollydoddger.waymark.shared.PoiStore
import com.jollydoddger.waymark.shared.Route
import com.jollydoddger.waymark.shared.Gpx
import com.jollydoddger.waymark.shared.Locator
import com.jollydoddger.waymark.shared.Prefs.allPathsEnabled
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.assistantEnabled
import com.jollydoddger.waymark.shared.Prefs.libraryFolder
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.prowEnabled
import com.jollydoddger.waymark.shared.Prefs.radarEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesEnabled
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.recordingStartedAt
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.routeHidden
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TileGrid
import com.jollydoddger.waymark.shared.TrackingService
import com.jollydoddger.waymark.shared.TrailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The whole phone app: the map, four small buttons, one status line.
 * Open → you are an arrow on an OS map; import a GPX → a line to follow,
 * offline tiles fetched, the lot pushed to the watch.
 */
class MainActivity : Activity() {

    private lateinit var map: BngMapView
    private lateinit var status: TextView
    private lateinit var recordIcon: IconDrawable
    /** Auto-centre once per opening, then leave him alone. */
    private var centredThisOpen = false
    private var locator: Locator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var importJob: Job? = null
    private var areaJob: Job? = null

    private var lastFix: En? = null
    private var lastFixAt = 0L
    private lateinit var voice: Voice
    private lateinit var askBox: EditText
    private lateinit var replyText: TextView
    private lateinit var replyPanel: ScrollView
    private lateinit var bottomStack: LinearLayout
    private var askBusy = false
    private val assistant by lazy {
        Assistant(
            this,
            GeoTools(
                this,
                { lastFix },
                { if (lastFixAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFixAt },
                // Planning is several calls to free servers; say what it is
                // doing rather than leaving a blank screen for half a minute.
                { note -> runOnUiThread { say(note) } },
            ),
        )
    }

    private val trailWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            map.setTrail(TrailStore.points(this@MainActivity))
            paintRecordButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        map = BngMapView(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        fun iconButton(glyph: Glyph, onClick: () -> Unit): View {
            val icon = IconDrawable(glyph, d)
            return View(this).apply {
                background = icon
                setOnClickListener { onClick() }
            }
        }

        val importBtn = iconButton(Glyph.ROUTE) { routeMenu() }
        val reverseBtn = iconButton(Glyph.REVERSE) {
            routeReversed = !routeReversed
            map.routeReversed = routeReversed
            say(
                if (routeReversed) "Arrows now point back the way — sending to the watch"
                else "Arrows point the route's own way — sending to the watch",
            )
            // The watch has no ⇄ of its own any more, so this has to travel.
            scope.launch {
                try {
                    Sync.sendStyle(this@MainActivity)
                } catch (e: Exception) {
                    say("Flipped here. The watch will follow when you next open it.")
                }
            }
        }
        val recentreBtn = iconButton(Glyph.LOCATE) { map.recentre() }
        recordIcon = IconDrawable(Glyph.RECORD, d)
        val recordBtn = View(this).apply {
            background = recordIcon
            setOnClickListener { toggleRecording() }
        }
        val downloadBtn = iconButton(Glyph.DOWNLOAD) { downloadArea() }
        val settingsBtn = iconButton(Glyph.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(importBtn, reverseBtn, recentreBtn, recordBtn, downloadBtn, settingsBtn).forEach {
                addView(it, LinearLayout.LayoutParams(dp(52), dp(52)).apply { topMargin = dp(9) })
            }
        }

        status = TextView(this).apply {
            setBackgroundColor(Color.argb(200, 30, 30, 30))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            setOnClickListener {
                if (osApiKey.isEmpty()) startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                else visibility = View.GONE
            }
        }

        // --- the ask bar: talk to the assistant without leaving the map ---
        askBox = EditText(this).apply {
            hint = "Ask… (toilets on the route? how far left?)"
            textSize = 15f
            maxLines = 2
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendAsk(); true } else false
            }
        }
        val micBtn = View(this).apply {
            background = IconDrawable(Glyph.MIC, d)
            bindHoldToTalk(this)
        }
        val sendBtn = View(this).apply {
            background = IconDrawable(Glyph.SEND, d)
            setOnClickListener { sendAsk() }
        }
        val askBar = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(235, 250, 250, 248))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
            addView(askBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
            addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
        }

        replyText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        replyPanel = ScrollView(this).apply {
            setBackgroundColor(Color.argb(225, 28, 32, 30))
            visibility = View.GONE
            addView(replyText)
            setOnClickListener { visibility = View.GONE }
        }

        // Reply above the ask bar, both in one stack pinned to the bottom, so
        // padding the stack lifts the whole thing clear of the system bars —
        // and of the keyboard.
        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(replyPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(210),
            ))
            addView(askBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(buttons, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { rightMargin = dp(10) })
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply { leftMargin = dp(10); rightMargin = dp(10) })
            addView(bottomStack, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
        setContentView(root)

        // From targetSdk 35 Android draws every app edge-to-edge, so anything
        // pinned to the bottom sits *under* the navigation bar unless it is
        // told otherwise — which is exactly where the ask box went. Pad the
        // stack by whichever is deeper, the system bars or the keyboard, and
        // the box rides up to sit on the keyboard when it opens.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            bottomStack.setPadding(0, 0, 0, bottom)
            (status.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(8)
            status.requestLayout()
            insets
        }

        voice = Voice(this).apply {
            onFinal = { heard ->
                askBox.setText(heard)
                sendAsk()
            }
            onChange = { state -> if (state.isNotBlank()) askBox.hint = state }
        }

        map.routeReversed = routeReversed
        map.setColours(routeColour, arrowColour, trailColour)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1,
            )
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let { importGpx(it) }
    }

    override fun onResume() {
        super.onResume()
        // Switched off in Settings, the ask bar is absent rather than idle —
        // the map gets the whole screen back, which is the point of it.
        bottomStack.visibility = if (assistantEnabled) View.VISIBLE else View.GONE
        // centredThisOpen is deliberately NOT reset here: onResume also runs
        // on the way back from Settings, and re-centring there yanked the map
        // away from wherever he was planning. One centre per opening of the
        // app; after that the map stays put until he presses the button.
        map.setRoute(if (routeHidden) null else RouteStore.load(this))
        map.setTrail(TrailStore.points(this))
        map.setPois(PoiStore.load(this))
        map.setColours(routeColour, arrowColour, trailColour)
        // A Start pressed on the watch while this app was closed waits here.
        if (wantRecording && !recording) TrackingService.start(this)
        paintRecordButton()
        val filter = IntentFilter(TrackingService.BROADCAST_TRAIL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trailWatcher, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(trailWatcher, filter)
        }
        if (osApiKey.isEmpty()) {
            say("No map without a key — tap here to enter your OS Maps API key")
        }
        bindOverlays()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locator = Locator(this, { en, stale ->
                lastFix = en
                if (!stale) lastFixAt = System.currentTimeMillis()
                map.setFix(en.e, en.n, stale)
                // Opening the app should answer "where am I" without a tap.
                // Once only: after that the map is his to move.
                if (!centredThisOpen) {
                    centredThisOpen = true
                    map.recentre()
                }
            }, { map.setHeading(it) }).also { it.start() }
        }
    }

    override fun onPause() {
        try { unregisterReceiver(trailWatcher) } catch (e: IllegalArgumentException) { }
        locator?.stop()
        locator = null
        super.onPause()
    }

    override fun onDestroy() {
        voice.dispose()
        scope.cancel()
        super.onDestroy()
    }

    private fun toggleRecording() {
        val turningOn = !recording
        if (turningOn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // Recording shows an ongoing notification; ask before promising.
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
            TrailStore.clear(this)
            map.setTrail(emptyList())
            recordingStartedAt = System.currentTimeMillis()
            TrackingService.start(this)
            say("Recording — leaving a trail behind you")
        } else {
            TrackingService.stop(this)
            say("Stopped. The trail stays on the map until you start again.")
            offerToSaveWalk()
        }
        recording = turningOn
        wantRecording = turningOn
        paintRecordButton()
        scope.launch {
            try {
                Sync.sendRecording(this@MainActivity, turningOn)
            } catch (e: Exception) {
                // Watch out of range: this phone still records itself, and the
                // watch picks the wish up when it next connects.
            }
        }
    }

    private fun paintRecordButton() {
        recordIcon.active = recording
    }

    private fun say(msg: String) {
        status.removeCallbacks(hideStatus)
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private val hideStatus = Runnable { status.visibility = View.GONE }

    /**
     * For the overlays' running commentary: useful while it is happening,
     * clutter across the map a minute later.
     */
    private fun sayBriefly(msg: String) {
        say(msg)
        status.postDelayed(hideStatus, 5_000)
    }

    // --- map overlays ---------------------------------------------------------

    /**
     * Wire whichever overlays are switched on to the map settling. Switched
     * off means gone and nothing fetched, not merely hidden — one settle
     * hook serves both, so neither can quietly disable the other.
     */
    private fun bindOverlays() {
        val wantTraces = tracesEnabled
        val wantProw = prowEnabled
        val wantRadar = radarEnabled
        if (!wantTraces) map.setTraces(emptyList())
        if (!wantProw) map.setProw(emptyList())
        if (!wantRadar) map.setRadar(emptyList())
        if (!wantTraces && !wantProw && !wantRadar) {
            map.onViewportSettled = null
            return
        }
        val fetch = {
            val bounds = map.viewportBounds()
            if (wantProw) {
                Prow.refresh(this, bounds, allPathsEnabled, { note -> sayBriefly(note) }) { lines ->
                    map.setProw(lines)
                }
            }
            if (wantTraces) {
                Traces.refresh(this, bounds, { note -> sayBriefly(note) }) { cells -> map.setTraces(cells) }
            }
            if (wantRadar) {
                Radar.refresh(bounds, { note -> sayBriefly(note) }) { tiles -> map.setRadar(tiles) }
            }
        }
        map.onViewportSettled = fetch
        // Switching a layer on in Settings and coming back left the map
        // sitting perfectly still, and the settle hook only fires on a pan,
        // a zoom or a moving fix — so nothing was ever requested and the
        // overlay looked broken. Ask once, here, as soon as there is a
        // viewport to ask about.
        if (map.width > 0) fetch() else map.post { if (map.width > 0) fetch() }
    }

    // --- offline area download -----------------------------------------------

    /**
     * Save everything on screen, at every zoom, for a day with no reception.
     * Counted and confirmed before a byte moves: OS bills per tile served,
     * so a whole-county tap must be a decision, not an accident. A second
     * tap while it runs cancels; what's already saved stays saved.
     */
    private fun downloadArea() {
        areaJob?.let {
            it.cancel()
            areaJob = null
            say("Download stopped. Tiles already saved are kept.")
            return
        }
        val all = Corridor.tilesForBounds(map.viewportBounds())
        val missing = all.filter { !map.tiles.fileFor(it.z, it.x, it.y).exists() }
        if (missing.isEmpty()) {
            say("Everything on screen is already saved for offline, at every zoom.")
            return
        }
        if (missing.size > 20_000) {
            say("That view is ~${missing.size} tiles — too big a bill for one tap. Zoom in and take it in pieces.")
            return
        }
        val mb = missing.size * 20 / 1024 // ~20 KB per tile, said as a rough figure
        AlertDialog.Builder(this)
            .setTitle("Save this area for offline?")
            .setMessage(
                "${missing.size} tiles at all ${TileGrid.MAX_Z + 1} zoom levels, roughly $mb MB. " +
                    "They stay on the phone and count against your OS Maps allowance. " +
                    "Tap the button again to stop mid-way.",
            )
            .setPositiveButton("Download") { _, _ ->
                areaJob = scope.launch {
                    var lastShown = 0
                    val failed = Corridor.prefetchTiles(map.tiles, missing) { done, total ->
                        if (done - lastShown >= 25 || done == total) {
                            lastShown = done
                            runOnUiThread { say("Saving offline tiles… $done / $total") }
                        }
                    }
                    areaJob = null
                    if (failed == 0) {
                        say("Saved ${missing.size} tiles — this area now works with no signal, at every zoom.")
                    } else {
                        val why = if (map.tiles.lastAuthError != 0) {
                            "the OS key was refused (HTTP ${map.tiles.lastAuthError}) — check it in ⚙"
                        } else "no signal or a server wobble — tap again to fetch the rest"
                        say("Saved ${missing.size - failed} tiles; $failed failed: $why.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- routes in: import, walks near me, the library -----------------------

    /**
     * The GPX button's small menu. Everything here works with the ask bar
     * switched off — none of it is the assistant's.
     */
    private fun routeMenu() {
        val hideLabel = if (routeHidden) "Show the route" else "Hide the route"
        val items = arrayOf(
            "Import a GPX file", "Walks near me", "Saved walks",
            hideLabel, "GPX library folder…",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> pickGpx()
                    1 -> walksNearMe()
                    2 -> savedWalksDialog()
                    3 -> {
                        routeHidden = !routeHidden
                        map.setRoute(if (routeHidden) null else RouteStore.load(this))
                        say(
                            if (routeHidden) "Route hidden — the map underneath is all yours. " +
                                "It is still stored, and still on the watch."
                            else "Route back on the map.",
                        )
                    }
                    4 -> libraryDialog()
                }
            }
            .show()
    }

    // --- saved walks ----------------------------------------------------------

    /** After ■: the walk just recorded is worth keeping, so offer, once. */
    private fun offerToSaveWalk() {
        val points = TrailStore.points(this)
        if (points.size < 2) return
        val startedAt = recordingStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val endedAt = System.currentTimeMillis()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val nameBox = EditText(this).apply {
            hint = "Name"
            setText(
                java.text.SimpleDateFormat("'Walk,' d MMM", java.util.Locale.UK)
                    .format(java.util.Date(startedAt)),
            )
        }
        val notesBox = EditText(this).apply {
            hint = "Notes — where, how it was…"
            minLines = 2
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameBox)
            addView(notesBox)
        }
        val km = Geom.length(points) / 1000
        AlertDialog.Builder(this)
            .setTitle("Save this walk? (%.1f km)".format(km))
            .setView(col)
            .setPositiveButton("Save") { _, _ ->
                saveWalk(nameBox.text.toString().trim(), notesBox.text.toString().trim(),
                    points, startedAt, endedAt)
            }
            .setNegativeButton("Not this one", null)
            .show()
    }

    private fun saveWalk(name: String, notes: String, points: List<En>, startedAt: Long, endedAt: Long) {
        scope.launch {
            val walk = withContext(Dispatchers.IO) {
                // Where it was: the grid reference always; a place name only
                // if Nominatim answers promptly. Saving never waits on signal.
                val grid = com.jollydoddger.waymark.shared.Bng.gridRef(points.first(), 3).orEmpty()
                val named = runCatching {
                    val (lat, lon) = com.jollydoddger.waymark.shared.Bng.toWgs84(points.first())
                    val json = Net.get(
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=14" +
                            "&lat=%.5f&lon=%.5f".format(lat, lon),
                        timeoutMs = 6_000,
                    )
                    org.json.JSONObject(json).optString("display_name")
                        .split(",").take(2).joinToString(",").trim()
                }.getOrNull().orEmpty()
                val walk = Walks.SavedWalk(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name.ifBlank { "Walk" },
                    notes = notes,
                    place = listOf(named, grid).filter { it.isNotBlank() }.joinToString(" · "),
                    startedAt = startedAt, endedAt = endedAt,
                    distanceM = Geom.length(points),
                    points = points,
                )
                Walks.save(this@MainActivity, walk)
                walk
            }
            say("Saved “${walk.name}” — ${fmtDist(walk.distanceM)} in ${Walks.duration(walk)}. " +
                "It lives under GPX → Saved walks.")
        }
    }

    private fun savedWalksDialog() {
        val walks = Walks.list(this)
        if (walks.isEmpty()) {
            say("No saved walks yet. Record one with ● and save it when you stop.")
            return
        }
        val rows = walks.map {
            "${it.name} — ${Walks.dateLine(it)} · ${fmtDist(it.distanceM)} · ${Walks.duration(it)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Saved walks")
            .setItems(rows.toTypedArray()) { _, i -> savedWalkActions(walks[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savedWalkActions(walk: Walks.SavedWalk) {
        val detail = buildString {
            append(Walks.dateLine(walk)).append(" · ").append(fmtDist(walk.distanceM))
                .append(" · ").append(Walks.duration(walk))
            if (walk.place.isNotBlank()) append("
").append(walk.place)
            if (walk.notes.isNotBlank()) append("

").append(walk.notes)
        }
        AlertDialog.Builder(this)
            .setTitle(walk.name)
            .setMessage(detail)
            .setPositiveButton("Load as route") { _, _ ->
                importJob?.cancel()
                importJob = scope.launch {
                    val route = withContext(Dispatchers.IO) {
                        Route(walk.name, walk.points).also { RouteStore.save(this@MainActivity, it) }
                    }
                    say("“${walk.name}” set as the route — fetching offline tiles…")
                    publishRoute(route)
                }
            }
            .setNeutralButton("Share as GPX") { _, _ ->
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { Walks.asGpxUri(this@MainActivity, walk) }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, walk.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share “${walk.name}”"))
                }
            }
            .setNegativeButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("Delete “${walk.name}” for good?")
                    .setPositiveButton("Delete") { _, _ ->
                        Walks.delete(this, walk.id)
                        say("“${walk.name}” deleted.")
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .show()
    }

    private fun fmtDist(m: Double) =
        if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000)

    private fun walksNearMe() {
        val here = lastFix ?: run {
            say("No GPS fix yet — the search is centred on where you are.")
            return
        }
        val options = listOf("500 m" to 500.0, "1 km" to 1_000.0, "2 km" to 2_000.0, "5 km" to 5_000.0)
        AlertDialog.Builder(this)
            .setTitle("Walks whose line comes within…")
            .setItems(options.map { it.first }.toTypedArray()) { _, i ->
                findWalks(here, options[i].second)
            }
            .show()
    }

    private fun findWalks(here: En, radiusM: Double) {
        say("Searching walking routes within ${fmtDist(radiusM)}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                RouteFinder.find(this@MainActivity, here, radiusM)
            }
            status.visibility = View.GONE
            result.note?.let { say(it) }
            if (result.walks.isEmpty()) {
                val libNote = Library.count(this@MainActivity).let { n ->
                    if (n > 0) " or your $n-route library" else
                        " (no library folder is indexed yet — GPX menu → GPX library folder)"
                }
                say("No walking route's line comes within ${fmtDist(radiusM)} — " +
                    "nothing in OpenStreetMap's route relations$libNote. Try a bigger radius.")
                return@launch
            }
            showWalkList(result.walks)
        }
    }

    private fun showWalkList(walks: List<RouteFinder.FoundWalk>) {
        val rows = walks.map {
            "${it.name} — line ${fmtDist(it.closestM)} away · ${fmtDist(it.lengthM)} · ${it.source}"
        }
        AlertDialog.Builder(this)
            .setTitle("Walks near you")
            .setItems(rows.toTypedArray()) { _, i -> previewWalk(walks, i) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewWalk(walks: List<RouteFinder.FoundWalk>, i: Int) {
        val walk = walks[i]
        map.setPreview(walk.lines)
        map.fitTo(walk.routePoints())
        val note = if (walk.source == "OSM") {
            "\n\nAn OSM route is stitched from its mapped sections, so the line can " +
                "have gaps or run out of order — the shape is right, the join-up isn't guaranteed."
        } else ""
        AlertDialog.Builder(this)
            .setTitle(walk.name)
            .setMessage(
                "Line ${fmtDist(walk.closestM)} from you · ${fmtDist(walk.lengthM)} of path · " +
                    "from ${if (walk.source == "OSM") "OpenStreetMap" else "your library"}$note",
            )
            .setPositiveButton("Use it") { _, _ -> adoptFound(walk) }
            .setNegativeButton("Back") { _, _ ->
                map.setPreview(emptyList())
                showWalkList(walks)
            }
            .setOnCancelListener { map.setPreview(emptyList()) }
            .show()
    }

    private fun adoptFound(walk: RouteFinder.FoundWalk) {
        map.setPreview(emptyList())
        importJob?.cancel()
        importJob = scope.launch {
            try {
                val route = withContext(Dispatchers.IO) {
                    // A library walk re-parses its GPX for the full line; the
                    // index only keeps a decimated one. The file can have gone
                    // since the scan, in which case the index line still works.
                    val full = walk.uri?.let { u ->
                        runCatching {
                            contentResolver.openInputStream(Uri.parse(u))!!.use { Gpx.parse(it) }
                        }.getOrNull()
                    }
                    (full?.copy(name = walk.name) ?: Route(walk.name, walk.routePoints()))
                        .also { RouteStore.save(this@MainActivity, it) } // banks the old route
                }
                say("“${walk.name}” set — fetching offline tiles…")
                publishRoute(route)
            } catch (e: Exception) {
                say("Couldn't set that walk: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun libraryDialog() {
        val b = AlertDialog.Builder(this)
            .setTitle("GPX library")
            .setMessage(
                if (libraryFolder.isEmpty()) {
                    "Point Waymark at a folder of GPX files — your own exports from " +
                        "komoot, AllTrails, OS Maps and the rest — and Walks near me " +
                        "searches them by how close each line comes to you."
                } else {
                    "${Library.count(this)} routes indexed. Rescan after adding files, " +
                        "or choose a different folder."
                },
            )
            .setPositiveButton("Choose folder") { _, _ ->
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 5)
            }
            .setNegativeButton("Cancel", null)
        if (libraryFolder.isNotEmpty()) b.setNeutralButton("Rescan") { _, _ -> rescanLibrary() }
        b.show()
    }

    private fun rescanLibrary() {
        say("Reading the library folder…")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { Library.rescan(this@MainActivity) }
            say(outcome)
        }
    }

    private fun pickGpx() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // GPX rarely gets a proper MIME type from file managers, so open
            // wide and let the parser be the judge.
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) data?.data?.let { importGpx(it) }
        if (requestCode == 5 && resultCode == RESULT_OK) data?.data?.let { tree ->
            // Keep the grant across reboots, or every rescan would need re-picking.
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryFolder = tree.toString()
            rescanLibrary()
        }
    }

    private fun importGpx(uri: Uri) {
        importJob?.cancel()
        importJob = scope.launch {
            try {
                val route = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { Gpx.parse(it) }
                        .also { RouteStore.save(this@MainActivity, it) }
                }
                say("Imported “${route.name}” — fetching offline tiles…")
                publishRoute(route)
            } catch (e: Exception) {
                say("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * A route became current — imported or planned by the assistant. Draw it,
     * fetch its offline corridor, and put route + tiles on the watch.
     */
    private suspend fun publishRoute(route: Route) {
        routeHidden = false // a route you just chose is a route you can see
        map.setRoute(route)
        var lastShown = 0
        val failed = Corridor.prefetch(map.tiles, route) { done, total ->
            if (done - lastShown >= 25 || done == total) {
                lastShown = done
                runOnUiThread { say("Fetching offline tiles… $done / $total") }
            }
        }
        if (failed > 0) {
            val why = if (map.tiles.lastAuthError != 0) {
                "the OS key was refused (HTTP ${map.tiles.lastAuthError}) — check it in ⚙"
            } else "no signal or a server wobble; they'll fill in as you browse"
            say("Offline tiles: $failed of the route's tiles missing — $why. Sending to watch…")
        } else {
            say("Offline tiles saved. Sending to watch…")
        }
        try {
            Sync.sendRoute(this@MainActivity, route, map.tiles)
            say("“${route.name}” is on the watch (or will be the moment it connects)")
        } catch (e: Exception) {
            say("“${route.name}” is set here; the watch will get it when it reconnects")
        }
    }

    // --- the assistant -------------------------------------------------------

    private fun sendAsk() {
        val question = askBox.text.toString().trim()
        if (question.isEmpty() || askBusy) return
        askBusy = true
        askBox.setText("")
        replyText.text = "…"
        replyPanel.visibility = View.VISIBLE

        // Remember what the tools might change, so changes can be published.
        val routeBefore = RouteStore.load(this)?.let { it.name to it.points.size }

        scope.launch {
            val reply = withContext(Dispatchers.IO) { assistant.ask(question) }

            val sb = StringBuilder(reply.text)
            if (reply.actions.isNotEmpty()) {
                sb.append("\n")
                reply.actions.forEach { sb.append("\n✓ ").append(it.summary) }
            }
            replyText.text = sb.toString()
            replyPanel.visibility = View.VISIBLE

            // Show what the tools did: markers, and possibly a new route.
            map.setPois(PoiStore.load(this@MainActivity))
            launch {
                try { Sync.sendPois(this@MainActivity, PoiStore.load(this@MainActivity)) } catch (e: Exception) { }
            }
            val routeNow = RouteStore.load(this@MainActivity)
            if (routeNow != null && (routeNow.name to routeNow.points.size) != routeBefore) {
                publishRoute(routeNow)
            } else {
                map.setRoute(if (routeHidden) null else routeNow)
            }
            askBusy = false
        }
    }

    /** Hold to talk, release to send — the finger coming off IS the send. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldToTalk(button: View) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4)
                    } else {
                        voice.start()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    voice.stop()
                    true
                }
                else -> false
            }
        }
    }
}
