package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.jollydoddger.waymark.shared.Bng
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
import com.jollydoddger.waymark.shared.Prefs.allPathsShown
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.assistantEnabled
import com.jollydoddger.waymark.shared.Prefs.cloudEnabled
import com.jollydoddger.waymark.shared.Prefs.cloudShown
import com.jollydoddger.waymark.shared.Prefs.libraryFolder
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.prowEnabled
import com.jollydoddger.waymark.shared.Prefs.prowShown
import com.jollydoddger.waymark.shared.Prefs.radarEnabled
import com.jollydoddger.waymark.shared.Prefs.radarScheme
import com.jollydoddger.waymark.shared.Prefs.radarShown
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.recordingStartedAt
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.routeHidden
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.Prefs.tempEnabled
import com.jollydoddger.waymark.shared.Prefs.tempShown
import com.jollydoddger.waymark.shared.Prefs.tracesEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesShown
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.Prefs.weatherOpacity
import com.jollydoddger.waymark.shared.Prefs.windEnabled
import com.jollydoddger.waymark.shared.Prefs.windShown
import com.jollydoddger.waymark.shared.Prefs.windStyle
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Sun
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
    private lateinit var askBar: LinearLayout

    // The weather scrubber: five hours back, five forward, one moment shown.
    private lateinit var wxBar: LinearLayout
    private lateinit var wxLabel: TextView
    private lateinit var wxSeek: SeekBar
    private lateinit var wxFade: SeekBar
    private lateinit var wxLegend: LinearLayout
    private var legendKey = ""

    /** The row of layer toggles across the top of the map, and the reading
     *  chips that sit beside them. */
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var tempChip: TextView

    /**
     * Whether this screen is still the one on the phone. Radar and Weather
     * are process-wide objects holding a main-thread handler, and every
     * callback closes over this activity — so a rotation mid-fetch leaves
     * their callbacks holding a dead activity and its whole view tree for up
     * to half a minute, doing work nobody will see.
     */
    private var alive = true
    private var wxFrames: List<WxFrame> = emptyList()
    private var wxIndex = 0
    private var radarFrames: List<WxFrame> = emptyList()
    private var wxField: Weather.Field? = null
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
        val sunBtn = iconButton(Glyph.SUN) {
            val here = lastFix
            if (here == null) {
                say("No GPS fix yet — the sun's track needs to know where you are.")
            } else {
                startActivity(
                    Intent(this, SunActivity::class.java)
                        .putExtra("e", here.e).putExtra("n", here.n),
                )
            }
        }
        val settingsBtn = iconButton(Glyph.SETTINGS) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(importBtn, reverseBtn, recentreBtn, recordBtn, downloadBtn, sunBtn, settingsBtn).forEach {
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
        val chatBtn = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(60, 70, 62))
            setOnClickListener { openChat() }
        }
        askBar = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(235, 250, 250, 248))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
            addView(askBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
            addView(sendBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
            addView(chatBtn, LinearLayout.LayoutParams(dp(34), dp(42)))
        }

        // The last answer, and no more than that. It used to be a 210dp panel
        // that stayed up until it was noticed and tapped — half the map, gone
        // to something already read. Four lines, a cross that closes it, and
        // the whole conversation one tap away in its own screen.
        replyText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(6), dp(10))
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { openChat() }
        }
        val replyOpen = TextView(this).apply {
            text = "⌃"
            textSize = 17f
            setTextColor(Color.argb(230, 220, 226, 220))
            setPadding(dp(10), dp(10), dp(6), dp(10))
            setOnClickListener { openChat() }
        }
        val replyClose = TextView(this).apply {
            text = "✕"
            textSize = 17f
            setTextColor(Color.argb(230, 220, 226, 220))
            setPadding(dp(8), dp(10), dp(12), dp(10))
            setOnClickListener { replyPanel.visibility = View.GONE }
        }
        val replyRow = LinearLayout(this).apply {
            gravity = Gravity.TOP
            addView(replyText, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ))
            addView(replyOpen, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(replyClose, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        replyPanel = ScrollView(this).apply {
            setBackgroundColor(Color.argb(225, 28, 32, 30))
            visibility = View.GONE
            addView(replyRow)
        }

        // --- the weather scrubber: drag time, watch the rain move ---
        wxLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(6), dp(14), 0)
            // One line, always. "14:20 · in 40 min · radar nowcast · 14°C ·
            // SW 18 mph · 62% cloud" wraps to two and drops back to one as
            // the readings come and go — and since the bar is wrap-height at
            // the bottom of the stack, that moves the scrubber vertically
            // under the finger dragging it.
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        wxSeek = SeekBar(this).apply {
            setPadding(dp(14), dp(2), dp(14), dp(6))
            // At the far left the thumb sits about 25dp in, low down the
            // screen — squarely in the back-swipe strip on a phone with
            // gesture navigation. Grabbing the oldest frame should not throw
            // him out of the map.
            if (Build.VERSION.SDK_INT >= 29) {
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) showWxFrame(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }
        // How heavily the weather is painted, right where he can see what it
        // is doing to the map — judging it from a settings screen with no map
        // on it is guesswork.
        wxFade = SeekBar(this).apply {
            // Floored at the same 10 the stored setting is floored at. It
            // used to run to zero, so dragging the weather away entirely and
            // coming back tomorrow found it faintly there again, with nothing
            // on screen to explain the difference.
            min = 10
            max = 100
            progress = weatherOpacity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    // The map follows the finger; the *setting* is written
                    // once, when the finger comes off. A sweep is up to
                    // ninety steps, and every one of them was a full
                    // preferences rewrite whose backlog is drained
                    // synchronously on the way out of the screen — so the
                    // stall landed when he left the map, nowhere near the
                    // drag that caused it.
                    if (fromUser) map.setWeatherOpacity(value)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    weatherOpacity = bar?.progress ?: return
                }
            })
        }
        val wxTopRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(wxLabel, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ))
            addView(wxFade, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        wxLegend = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(14), 0, dp(14), dp(6))
        }
        wxBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 22, 26, 24))
            visibility = View.GONE
            addView(wxTopRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(wxSeek, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(wxLegend, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // Scrubber, then reply, then the ask bar, all in one stack pinned to
        // the bottom, so padding the stack lifts the whole thing clear of the
        // system bars — and of the keyboard. The stack itself is always
        // present now: the weather timeline has to work with the assistant
        // switched off, which is how the app ships.
        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(wxBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            // The weight is a shrink-only lever here: the stack wraps its
            // height, so there is never spare room to grow into, only a
            // shortfall to take off somebody. Without it the shortfall came
            // off the last child — the ask bar, the thing he is typing into.
            // With the scrubber, a reply and a keyboard all up at once on a
            // short screen, the reply gives way instead.
            addView(replyPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ))
            addView(askBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // --- the layer toggles, on the map where they get used ---
        //
        // Settings decides which of these exist; this row decides which are
        // on. A switch buried two screens away is not something anyone
        // operates halfway up a hill, and the full list is long enough that
        // putting all of it here would be its own clutter.
        tempChip = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 30, 34, 32))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
        }
        chipRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(chipRow)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tempChip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
            addView(chipScroll, LinearLayout.LayoutParams(
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
            addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply { leftMargin = dp(10); rightMargin = dp(56); topMargin = dp(44) })
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
        // the map gets the whole screen back, which is the point of it. The
        // scrubber above it comes and goes with the weather layers instead.
        askBar.visibility = if (assistantEnabled) View.VISIBLE else View.GONE
        if (!assistantEnabled) replyPanel.visibility = View.GONE
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
        buildChips()
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
        alive = false
        voice.dispose()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Two dozen decoded radar frames is real memory. The sky will still
        // be there; they cost one re-fetch each.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Radar.trim()
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
     * One overlay: what Settings calls it, whether Settings allows it, and
     * whether its toggle on the map is currently on.
     */
    private class Layer(
        val label: String,
        val allowed: Boolean,
        val on: Boolean,
        val set: (Boolean) -> Unit,
    )

    private fun layers(): List<Layer> = listOf(
        Layer("Rain", radarEnabled, radarShown) { radarShown = it },
        Layer("Wind", windEnabled, windShown) { windShown = it },
        Layer("Cloud", cloudEnabled, cloudShown) { cloudShown = it },
        Layer("Temp", tempEnabled, tempShown) { tempShown = it },
        Layer("Paths used", tracesEnabled, tracesShown) { tracesShown = it },
        Layer("Rights of way", prowEnabled, prowShown) { prowShown = it },
        Layer("All paths", allPathsEnabled, allPathsShown) { allPathsShown = it },
    )

    /**
     * The toggle row. Only layers allowed in Settings appear, so the row is
     * as short as he has chosen to make it — and an empty row takes no space
     * at all rather than sitting there as an empty grey strip.
     */
    private fun buildChips() {
        chipRow.removeAllViews()
        val shown = layers().filter { it.allowed }
        chipScroll.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        for (layer in shown) {
            chipRow.addView(
                TextView(this).apply {
                    text = layer.label
                    textSize = 13f
                    // On and off have to be tellable apart at a glance in
                    // daylight, so it is not a subtle tint: on is the app's
                    // green with white text, off is dark and greyed.
                    if (layer.on) {
                        setBackgroundColor(Color.argb(235, 34, 96, 58))
                        setTextColor(Color.WHITE)
                    } else {
                        setBackgroundColor(Color.argb(190, 34, 38, 36))
                        setTextColor(Color.argb(255, 168, 172, 170))
                    }
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        layer.set(!layer.on)
                        buildChips()
                        bindOverlays()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(6) },
            )
        }
    }

    // Whether a layer actually draws: allowed in Settings *and* toggled on
    // here. Read through these rather than the raw preferences, or a chip
    // switched off would go on quietly fetching.
    private val wantRadar: Boolean get() = radarEnabled && radarShown
    private val wantWind: Boolean get() = windEnabled && windShown
    private val wantCloud: Boolean get() = cloudEnabled && cloudShown
    private val wantTemp: Boolean get() = tempEnabled && tempShown

    /**
     * Wire whichever overlays are switched on to the map settling. Switched
     * off means gone and nothing fetched, not merely hidden — one settle
     * hook serves both, so neither can quietly disable the other.
     */
    private fun bindOverlays() {
        val wantTraces = tracesEnabled && tracesShown
        val wantProw = prowEnabled && prowShown
        val wantAllPaths = allPathsEnabled && allPathsShown
        // Wind, temperature and cloud all come off one grid, so any of them
        // pays for all three — and the radar wants it too, for the forecast
        // hours either side of what the radar itself can see.
        val wantGrid = wantWind || wantTemp || wantCloud
        val wantWeather = wantRadar || wantGrid
        if (!wantTraces) map.setTraces(emptyList())
        if (!wantProw && !wantAllPaths) map.setProw(emptyList())
        if (!wantRadar) map.setRadar(emptyList())
        if (!wantWind) { map.setWind(emptyList()); map.setWind(null, windStyle == 1) }
        if (!wantGrid && !wantRadar) map.setField(null, 255)
        if (!wantTemp) tempChip.visibility = View.GONE
        if (!wantWeather) {
            wxFrames = emptyList()
            radarFrames = emptyList()
            wxField = null
            wxBar.visibility = View.GONE
        }
        if (!wantTraces && !wantProw && !wantAllPaths && !wantWeather) {
            map.onViewportSettled = null
            return
        }
        map.setWeatherOpacity(weatherOpacity)
        Radar.scheme = radarScheme
        // The frame catalogue is a property of the sky, not of the viewport,
        // so it is asked for once here rather than on every pan.
        if (wantRadar) {
            Radar.catalogue({ note -> if (alive) sayBriefly(note) }) { frames ->
                if (!alive) return@catalogue
                radarFrames = frames
                rebuildWxFrames()
            }
        } else {
            radarFrames = emptyList()
        }
        val fetch = {
            val bounds = map.viewportBounds()
            if (wantProw || wantAllPaths) {
                Prow.refresh(this, bounds, wantAllPaths, { note -> sayBriefly(note) }) { lines ->
                    // Rights of way switched off but every path switched on
                    // is a real combination: the physical network without the
                    // legal one drawn over it.
                    map.setProw(if (wantProw) lines else lines.filter { it.kind == Prow.ALL_PATH })
                }
            }
            if (wantTraces) {
                Traces.refresh(this, bounds, { note -> sayBriefly(note) }) { cells -> map.setTraces(cells) }
            }
            if (wantWeather) {
                Weather.refresh(bounds, { note -> if (alive) sayBriefly(note) }) { field ->
                    if (!alive) return@refresh
                    wxField = field
                    rebuildWxFrames()
                }
            }
            // A pan or a zoom needs the moment already on the scrubber, not
            // the newest one: dragging back an hour and then moving the map
            // must not silently jump the clock forward again.
            if (wantWeather && wxFrames.isNotEmpty()) showWxFrame(wxIndex)
        }
        map.onViewportSettled = fetch
        // Switching a layer on in Settings and coming back left the map
        // sitting perfectly still, and the settle hook only fires on a pan,
        // a zoom or a moving fix — so nothing was ever requested and the
        // overlay looked broken. Ask once, here, as soon as there is a
        // viewport to ask about.
        if (map.width > 0) fetch() else map.post { if (map.width > 0) fetch() }
    }

    // --- the weather timeline ------------------------------------------------

    /** Rain rates worth naming, in mm per hour, with the words for them. */
    private val RAIN_KEY = listOf(
        0.15 to "drizzle", 0.6 to "light", 3.0 to "steady", 10.0 to "heavy", 28.0 to "torrential",
    )

    /** RainViewer's own scale names, for the key and for Settings. */
    private val RADAR_SCALES = mapOf(
        1 to "Radar · Original scale", 2 to "Radar · Universal Blue",
        3 to "Radar · TITAN", 4 to "Radar · Weather Channel",
        5 to "Radar · Meteored", 6 to "Radar · NEXRAD",
        7 to "Radar · Rainbow", 8 to "Radar · Dark Sky",
    )

    /**
     * Rebuild the timeline from whatever has arrived. The radar catalogue and
     * the forecast grid land independently, so this runs on each and keeps
     * the moment he had chosen rather than snapping back to now under him.
     */
    private fun rebuildWxFrames() {
        val keep = wxFrames.getOrNull(wxIndex)?.timeMs
        val hours = if (wantRadar || wantWind || wantTemp || wantCloud) {
            wxField?.hours() ?: emptyList()
        } else {
            emptyList()
        }
        setWxFrames(Timeline.merge(radarFrames, hours, System.currentTimeMillis()), keep)
    }

    /** Hand the scrubber a new set of moments, keeping [keep] if it can. */
    private fun setWxFrames(frames: List<WxFrame>, keep: Long? = null) {
        wxFrames = frames
        if (frames.isEmpty()) {
            wxBar.visibility = View.GONE
            return
        }
        wxBar.visibility = View.VISIBLE
        wxSeek.max = frames.size - 1
        val i = Timeline.indexOfNow(frames, keep ?: System.currentTimeMillis())
        wxSeek.progress = i
        showWxFrame(i)
    }

    /**
     * Draw one moment. Tiles already decoded arrive immediately, so a drag
     * across the bar animates instead of blinking, and the frames just ahead
     * are warmed in the background for the same reason.
     */
    private fun showWxFrame(i: Int) {
        if (wxFrames.isEmpty()) return
        wxIndex = i.coerceIn(0, wxFrames.size - 1)
        val frame = wxFrames[wxIndex]
        // The catalogue can land before the map has been measured, and a
        // viewport of nothing asks for the wrong tiles. The label is still
        // worth setting, and the drawing is booked for after the layout pass
        // — nothing else was going to come back for it, so a cold start whose
        // radar arrived first showed a timeline over an empty map until he
        // happened to pan.
        if (map.width == 0 || map.height == 0) {
            wxLabel.text = frameLabel(frame)
            map.post { if (alive && map.width > 0 && map.height > 0) showWxFrame(wxIndex) }
            return
        }
        val bounds = map.viewportBounds()
        if (wantRadar) {
            Radar.tiles(bounds, frame) { tiles ->
                if (!alive) return@tiles
                map.setRadar(tiles)
                // A refusal only becomes known once a fetch has been tried,
                // so the key is asked again on the way back: this is the one
                // path that changes the effective scale without anything
                // else on screen changing.
                showLegend(frame, wxField != null && (wxField?.hourIndex(frame.timeMs) ?: -1) >= 0)
            }
            val ahead = ArrayList<WxFrame>()
            for (d in intArrayOf(1, 2, -1, 3)) {
                val j = wxIndex + d
                if (j >= 0 && j < wxFrames.size) ahead.add(wxFrames[j])
            }
            Radar.prefetch(bounds, ahead)
        }
        val field = wxField
        val hour = field?.hourIndex(frame.timeMs) ?: -1
        drawWeatherField(field, hour, frame)
        drawWind(field, hour)
        showTemperature(field, hour)
        wxLabel.text = frameLabel(frame) + readings(field, hour)
        showLegend(frame, field != null && hour >= 0)
    }

    /**
     * What the colours mean. Colour-coded rain says nothing until the code is
     * written down somewhere, and the somewhere has to be on the map — a key
     * in a settings screen is a key nobody reads in the rain.
     *
     * Only the washes this app paints itself get swatches, because only those
     * colours are ours to state. The radar's are RainViewer's scale, so that
     * one is named rather than mimicked: a hand-drawn key that drifted from
     * the real palette would be worse than none.
     */
    private fun showLegend(frame: WxFrame, haveField: Boolean) {
        val wash = if (haveField) washKind(frame) else ""
        // Radar and a wash can be on the map together — rain from RainViewer
        // over a temperature field — so the key names both. It used to name
        // only the radar, which left a blue-to-red field across his map with
        // nothing anywhere saying what it meant.
        val radar = if (wantRadar && frame.radarPath != null) Radar.schemeNow() else 0
        // The scale named is the one actually being fetched. If the server
        // refused his choice, the tiles are the fallback's colours and a key
        // still naming his preference is a key that describes nothing on the
        // screen.
        val key = "$radar/$wash"
        if (key == legendKey) return
        legendKey = key
        wxLegend.removeAllViews()
        wxLegend.visibility = if (radar == 0 && wash.isEmpty()) View.GONE else View.VISIBLE
        if (radar != 0) wxLegend.addView(legendNote(RADAR_SCALES[radar] ?: "RainViewer"))
        when (wash) {
            "rain" -> {
                wxLegend.addView(legendNote("Forecast rain"))
                for ((mm, name) in RAIN_KEY) wxLegend.addView(legendChip(name, Ramp.rain(mm)))
            }
            "cloud" -> {
                // No swatch for clear sky, because clear sky has no swatch:
                // the map is simply left alone, and saying so in words is
                // the honest key for it.
                wxLegend.addView(legendNote("Cloud — clear map is clear sky"))
                wxLegend.addView(legendChip("half", Ramp.cloud(55.0)))
                wxLegend.addView(legendChip("dull", Ramp.cloud(80.0)))
                wxLegend.addView(legendChip("full", Ramp.cloud(100.0)))
            }
        }
    }

    /**
     * Which single wash this frame gets, decided once. Both the painting and
     * the key read this, because the two disagreeing is how a colour ends up
     * on the map with no words for it.
     */
    private fun washKind(frame: WxFrame): String = when {
        wantRadar && frame.radarPath == null -> "rain"
        wantCloud -> "cloud"
        else -> ""
    }

    private fun legendNote(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(Color.argb(200, 235, 235, 235))
        setPadding(0, 0, (6 * resources.displayMetrics.density).toInt(), 0)
    }

    /** A swatch with its meaning written on it, legible on any of the ramps. */
    private fun legendChip(label: String, colour: Int): TextView {
        val d = resources.displayMetrics.density
        // The cloud ramp is deliberately see-through, so a swatch of it over
        // a dark bar would read as almost nothing: lay it on white first, the
        // way it will actually sit on the map.
        val over = blendOnWhite(colour)
        val lum = 0.299 * Color.red(over) + 0.587 * Color.green(over) + 0.114 * Color.blue(over)
        return TextView(this).apply {
            text = label
            textSize = 10f
            setTextColor(if (lum > 140) Color.BLACK else Color.WHITE)
            setBackgroundColor(over)
            setPadding((7 * d).toInt(), (2 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
        }
    }

    private fun blendOnWhite(c: Int): Int {
        val a = Color.alpha(c) / 255f
        fun ch(v: Int) = (v * a + 255 * (1 - a)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    /**
     * One colour wash at a time: two of them over each other say nothing
     * legible, and this is a map first. Forecast rain takes the map wherever
     * the radar cannot see, because rain is the thing that changes a plan;
     * otherwise temperature, then cloud.
     */
    private fun drawWeatherField(field: Weather.Field?, hour: Int, frame: WxFrame) {
        if (field == null || hour < 0) { map.setField(null, 255); return }
        fun tile(values: DoubleArray, ramp: (Double) -> Int) = BngMapView.MeshTile(
            Weather.render(values, ramp),
            field.south, field.west, field.north, field.east,
        )
        when (washKind(frame)) {
            "rain" -> map.setField(tile(field.rain[hour]) { Ramp.rain(it) }, 255)
            "cloud" -> map.setField(tile(field.cloud[hour]) { Ramp.cloud(it) }, 255)
            else -> map.setField(null, 255)
        }
    }

    /**
     * The wind, drawn either as lines drifting the way the air is going or
     * as one arrow per reading.
     *
     * Streamlines came out of him saying the arrows were hard to understand,
     * and they are: twenty-five separate arrows are twenty-five things to
     * look at and join up in your head, where flow is one picture. The
     * numbers do not go away — the reading in the bar still gives the real
     * speed and the direction a forecast would state it in, because a moving
     * picture is for "which way and roughly how hard", never for "18 mph".
     */
    private fun drawWind(field: Weather.Field?, hour: Int) {
        if (!wantWind || field == null || hour < 0) {
            map.setWind(emptyList())
            map.setWind(null, windStyle == 1)
            return
        }
        if (windStyle == 1) {
            map.setWind(emptyList())
            map.setWind(windGrid(field, hour), true)
        } else {
            map.setWind(null, false)
            val arrows = ArrayList<BngMapView.WindArrow>()
            for (i in field.lat.indices) {
                val speed = field.windSpeed[hour][i]
                val from = field.windDir[hour][i]
                if (speed.isNaN() || from.isNaN()) continue
                val en = Bng.fromWgs84(field.lat[i], field.lon[i])
                arrows.add(BngMapView.WindArrow(en.e, en.n, speed, from))
            }
            map.setWind(arrows)
        }
    }

    /**
     * The forecast's wind readings as eastward and northward components on a
     * National Grid box, which is what the animation can interpolate between.
     *
     * Components rather than speed and bearing, because averaging two
     * bearings either side of north gives south. The grid is a lat/lon
     * rectangle being treated as a grid-aligned one — a rotation of a degree
     * or two in Wales, far under the few kilometres between readings.
     */
    private fun windGrid(field: Weather.Field, hour: Int): BngMapView.WindGrid? {
        val n = Weather.GRID
        if (field.lat.size < n * n) return null
        var west = Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        var south = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        val u = DoubleArray(n * n) { Double.NaN }
        val v = DoubleArray(n * n) { Double.NaN }
        for (i in 0 until n * n) {
            val en = Bng.fromWgs84(field.lat[i], field.lon[i])
            if (en.e < west) west = en.e
            if (en.e > east) east = en.e
            if (en.n < south) south = en.n
            if (en.n > north) north = en.n
            val speed = field.windSpeed[hour][i]
            val from = field.windDir[hour][i]
            if (speed.isNaN() || from.isNaN()) continue
            // Metres per second, going where it is going rather than coming
            // from where it came.
            val ms = speed * 0.44704
            val rad = Math.toRadians(from)
            u[i] = -ms * kotlin.math.sin(rad)
            v[i] = -ms * kotlin.math.cos(rad)
        }
        if (east <= west || north <= south) return null
        return BngMapView.WindGrid(west, south, east, north, n, u, v)
    }

    /** Temperature as a figure. A wash of colour across the whole map said
     *  less than two characters do, and buried the contours saying it. */
    private fun showTemperature(field: Weather.Field?, hour: Int) {
        if (!wantTemp || field == null || hour < 0) { tempChip.visibility = View.GONE; return }
        val centre = (Weather.GRID / 2) * Weather.GRID + Weather.GRID / 2
        val t = if (centre < field.lat.size) field.temp[hour][centre] else Double.NaN
        if (t.isNaN()) { tempChip.visibility = View.GONE; return }
        tempChip.text = "${t.roundToInt()}°C"
        tempChip.visibility = View.VISIBLE
    }

    /**
     * The middle of the map in numbers, for the layers a colour cannot state
     * precisely. Wind is given the way a forecast gives it — the direction it
     * blows *from* — while the arrows on the map fly the way it is going.
     */
    private fun readings(field: Weather.Field?, hour: Int): String {
        if (field == null || hour < 0) return ""
        val centre = (Weather.GRID / 2) * Weather.GRID + Weather.GRID / 2
        if (centre >= field.lat.size) return ""
        val sb = StringBuilder()
        // Temperature has its own figure on the map now, so it is not
        // repeated here. Each reading appears only if its layer is on: a
        // label describing layers he switched off is a label he stops
        // reading.
        if (wantWind) {
            val speed = field.windSpeed[hour][centre]
            val from = field.windDir[hour][centre]
            if (!speed.isNaN() && !from.isNaN()) {
                sb.append(" · ${Sun.compass(from)} ${speed.roundToInt()} mph")
            }
        }
        if (wantCloud) {
            val cloud = field.cloud[hour][centre]
            if (!cloud.isNaN()) {
                sb.append(if (cloud < 25) " · clear" else " · ${cloud.roundToInt()}% cloud")
            }
        }
        return sb.toString()
    }

    /** "14:20 · in 40 min · forecast" — the clock, the offset, and the source. */
    private fun frameLabel(frame: WxFrame): String {
        fun span(m: Int): String = if (m >= 90) "${m / 60} h ${m % 60} min" else "$m min"
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
            .format(java.util.Date(frame.timeMs))
        val mins = ((frame.timeMs - System.currentTimeMillis()) / 60_000L).toInt()
        val rel = when {
            kotlin.math.abs(mins) <= 7 -> "now"
            mins < 0 -> span(-mins) + " ago"
            else -> "in " + span(mins)
        }
        return "$clock · $rel · ${frame.kind}"
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
            if (walk.place.isNotBlank()) append("\n").append(walk.place)
            if (walk.notes.isNotBlank()) append("\n\n").append(walk.notes)
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

    /** The whole conversation, on its own screen, with the map's fix in hand. */
    private fun openChat(prefill: String? = null) {
        val i = Intent(this, ChatActivity::class.java)
        lastFix?.let {
            i.putExtra("e", it.e)
            i.putExtra("n", it.n)
            i.putExtra(
                "fixAge",
                if (lastFixAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFixAt,
            )
        }
        if (!prefill.isNullOrBlank()) i.putExtra("ask", prefill)
        startActivity(i)
    }

    private fun sendAsk() {
        val question = askBox.text.toString().trim()
        if (question.isEmpty() || askBusy) return
        askBusy = true
        askBox.setText("")
        Talk.add(this, Said(true, question))
        replyText.text = "…"
        replyPanel.visibility = View.VISIBLE

        // Remember what the tools might change, so changes can be published.
        val routeBefore = RouteStore.load(this)?.let { it.name to it.points.size }

        scope.launch {
            val reply = withContext(Dispatchers.IO) { assistant.ask(question) }

            Talk.add(
                this@MainActivity,
                Said(false, reply.text, reply.actions.map { it.summary }),
            )
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
