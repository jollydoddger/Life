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
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
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
import com.jollydoddger.waymark.shared.Prefs.libraryFolder
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.prowEnabled
import com.jollydoddger.waymark.shared.Prefs.prowShown
import com.jollydoddger.waymark.shared.Prefs.radarEnabled
import com.jollydoddger.waymark.shared.Prefs.radarScheme
import com.jollydoddger.waymark.shared.Prefs.recording
import com.jollydoddger.waymark.shared.Prefs.recordingStartedAt
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.routeHidden
import com.jollydoddger.waymark.shared.Prefs.routeReversed
import com.jollydoddger.waymark.shared.Prefs.tempEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesShown
import com.jollydoddger.waymark.shared.Prefs.weatherShown
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.walkSpec
import com.jollydoddger.waymark.shared.Prefs.wantRecording
import com.jollydoddger.waymark.shared.Prefs.weatherOpacity
import com.jollydoddger.waymark.shared.Prefs.windEnabled
import com.jollydoddger.waymark.shared.Prefs.windStyle
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Sun
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TileGrid
import com.jollydoddger.waymark.shared.TrackingService
import com.jollydoddger.waymark.shared.Mark
import com.jollydoddger.waymark.shared.Marks
import com.jollydoddger.waymark.shared.Prefs.warmUntil
import com.jollydoddger.waymark.shared.TrailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    private lateinit var wxPlay: TextView
    private var wxPlaying = false
    private var legendKey = ""

    /** The row of layer toggles across the top of the map, and the reading
     *  chips that sit beside them. */
    private lateinit var chipRow: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var tempChip: TextView
    private lateinit var timerChip: TextView
    private lateinit var markChip: TextView

    // Caches behind the live mark readout: recomputing cumulative distance
    // over a ten-thousand-point route on every one-second fix would be the
    // battery paying for arithmetic that never changes between routes.
    private var cumCache: Pair<String, DoubleArray>? = null
    private var heightsCache: Pair<String, Heights?>? = null
    private var paceCache: Triple<Double, String, Long>? = null
    private var markReadoutAt = 0L

    // The walk picker: candidates the assistant queued, cycled over the map.
    private lateinit var pickerBar: LinearLayout
    private lateinit var pickerTitle: TextView
    private lateinit var pickerCount: TextView
    private var picks: List<RouteFinder.FoundWalk> = emptyList()
    private var pickIndex = 0
    private var pickerShowing = false

    /**
     * Which day the picker's walks were specified for, and whether they came
     * from the specifier at all. Both exist because a brief is about a walk
     * *on a day* — "best time to set off" means nothing without one — and
     * only the form knows which day he picked. A picker filled any other
     * way briefs for today, which is the honest default.
     */
    private val REQ_WALKS = 4271
    private var pickDayOffset = 0
    private var picksFromSpec = false
    private var specJob: kotlinx.coroutines.Job? = null

    // --- drawing a walk by tapping ------------------------------------------
    private lateinit var editBar: LinearLayout
    private lateinit var editStat: TextView
    private lateinit var editSnapBtn: TextView
    private var editing: RouteEdit? = null
    private var editGraph: Router.Graph? = null
    private var editGraphCentre: En? = null
    private var editGraphRadius = 0.0
    private var editPathsNote = ""
    private var editJob: kotlinx.coroutines.Job? = null

    /**
     * Whether this screen is still the one on the phone. Radar and Weather
     * are process-wide objects holding a main-thread handler, and every
     * callback closes over this activity — so a rotation mid-fetch leaves
     * their callbacks holding a dead activity and its whole view tree for up
     * to half a minute, doing work nobody will see.
     */
    private var alive = true

    /** Density pixels. onCreate has its own local copy of this for the
     *  layout it builds; anything outside that needs a member. */
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var wxFrames: List<WxFrame> = emptyList()
    private var wxIndex = 0
    private var radarFrames: List<WxFrame> = emptyList()
    private var wxField: Weather.Field? = null
    private var askBusy = false
    private var askJob: kotlinx.coroutines.Job? = null
    @Volatile private var askCancelled = false
    private var askStartedAt = 0L
    private var askNote = ""
    private val assistant by lazy {
        Assistant(
            this,
            GeoTools(
                this,
                { lastFix },
                { if (lastFixAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFixAt },
                // Planning is several calls to free servers. Progress used to
                // park in the status line, where it outlived the run and told
                // him nothing about whether anything was still happening; it
                // feeds the working strip now, beside a clock that moves.
                { note -> runOnUiThread { askActivity(note) } },
                { askCancelled },
            ),
        ).also { a -> a.onActivity = { note -> runOnUiThread { askActivity(note) } } }
    }

    private val trailWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            map.setTrail(TrailStore.points(this@MainActivity))
            paintRecordButton()
            // The service clears a mark when its buzz fires; the flag on the
            // map has to go with it.
            refreshMarks()
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
            setOnClickListener {
                if (askBusy) stopAsk() else replyPanel.visibility = View.GONE
            }
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
            // handled below: a finger on the bar stops the player — two
            // drivers on one scrubber fight, and the finger wins.
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
                override fun onStartTrackingTouch(bar: SeekBar?) { stopWxPlay() }
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
        // Play runs the ten hours as a loop — the whole point of a radar is
        // watching which way the shower travels, and a thumb on a scrubber
        // is a poor animator.
        wxPlay = TextView(this).apply {
            text = "▶"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { if (wxPlaying) stopWxPlay() else startWxPlay() }
        }
        val wxTopRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(wxPlay, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
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
        // --- the walk picker: what the assistant found, one at a time ---
        pickerTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(14), dp(8), dp(6), dp(2))
        }
        pickerCount = TextView(this).apply {
            setTextColor(Color.argb(220, 200, 206, 200))
            textSize = 13f
            setPadding(dp(4), dp(8), dp(6), dp(2))
        }
        fun pickerButton(label: String, wide: Boolean = false, onTap: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = if (wide) 14f else 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(255, 44, 52, 48))
                setPadding(dp(if (wide) 14 else 16), dp(8), dp(if (wide) 14 else 16), dp(8))
                setOnClickListener { onTap() }
            }
        val pickerClose = TextView(this).apply {
            text = "✕"
            textSize = 17f
            setTextColor(Color.argb(230, 220, 226, 220))
            setPadding(dp(10), dp(8), dp(14), dp(2))
            setOnClickListener { dismissPicker() }
        }
        val pickerTop = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pickerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pickerCount)
            addView(pickerClose)
        }
        val pickerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            fun space() = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
            addView(pickerButton("‹") { showPick(pickIndex - 1) }, space())
            addView(pickerButton("›") { showPick(pickIndex + 1) }, space())
            addView(pickerButton("Brief", wide = true) { briefPick() }, space())
            addView(pickerButton("Use", wide = true) { usePick() }, space())
            addView(pickerButton("Start walk", wide = true) { startWalkFromPick() }, space())
            addView(pickerButton("Parking", wide = true) { openParking() })
        }
        // Five buttons is one more than a phone's width holds, and a squashed
        // "Start walk" reading "Start w…" is the button he needs most. The
        // row scrolls instead: nothing is ever cut off, only out of sight,
        // and the two that move off the end are the two used least.
        val pickerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(10), dp(6), dp(10), dp(10))
            addView(pickerRow)
        }
        editStat = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(10), dp(2))
        }
        fun editButton(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(255, 44, 52, 48))
            setPadding(dp(13), dp(9), dp(13), dp(9))
            setOnClickListener { onTap() }
        }
        editSnapBtn = editButton("Paths") { cycleSnap() }
        val editRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            fun gap() = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(7) }
            addView(editSnapBtn, gap())
            addView(editButton("Undo") { undoEdit() }, gap())
            addView(editButton("Close loop") { closeEditLoop() }, gap())
            addView(editButton("Save") { saveEdit() }, gap())
            addView(editButton("✕") { stopEditing(save = false) })
        }
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 22, 26, 24))
            visibility = View.GONE
            addView(editStat, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    setPadding(dp(10), dp(4), dp(10), dp(10))
                    addView(editRow)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        pickerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 22, 26, 24))
            visibility = View.GONE
            addView(pickerTop, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(pickerScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        bottomStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(pickerBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
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
        // One big number: he asked for "a nice big number somewhere" over
        // the wash of colour it used to be — two characters say more than a
        // film over the whole map did, and bury nothing saying it.
        tempChip = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 30, 34, 32))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
            // The number on the map is the obvious thing to press when you
            // want more than a number.
            setOnClickListener { openWeather() }
        }
        timerChip = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 30, 34, 32))
            setPadding(dp(12), dp(4), dp(12), dp(4))
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
        val readouts = LinearLayout(this).apply {
            addView(tempChip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(timerChip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) })
        }
        // The next armed mark, counting itself down as fixes arrive. Its own
        // line: beside the temperature and the walk clock it would crowd a
        // narrow screen off the edge.
        markChip = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 30, 34, 32))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
            addView(readouts, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) })
            addView(markChip, LinearLayout.LayoutParams(
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
            // One column: the status line, then the readouts, then the
            // chips. They used to be two separately-positioned children with
            // a guessed margin between them, and a note like "Mark 3 set"
            // rendered squarely behind the chip row. Stacked, nothing can
            // cover anything, and the chips just shift down while a note is
            // showing.
            addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply { leftMargin = dp(10); rightMargin = dp(10) })
            addView(bottomStack, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
        setContentView(root)

        // If the last run died, its stack is the one fact worth having:
        // shown whole, then cleared, so "it won't open" can become a line
        // number in a screenshot.
        java.io.File(filesDir, WaymarkApp.CRASH_FILE).takeIf { it.exists() }?.let { f ->
            val trace = runCatching { f.readText() }.getOrDefault("(unreadable)")
            f.delete()
            AlertDialog.Builder(this)
                .setTitle("Waymark crashed last time")
                .setMessage(trace.take(4000))
                .setPositiveButton("Close", null)
                .show()
        }

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
            // The whole top column clears the system status bar. This margin
            // used to be set on the status line directly, from when it was
            // the frame's own child — after it moved into the column, the
            // cast to FrameLayout params was the crash that stopped the app
            // opening at all, on the very first layout pass.
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(8)
            topBar.requestLayout()
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
        // The timestamp too: this path never set one, so a watch-started
        // walk showed hours-old elapsed time and saved with a wrong duration.
        if (wantRecording && !recording) {
            recordingStartedAt = System.currentTimeMillis()
            TrackingService.start(this)
        }
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
        // A run that outlived a trip to another screen gets its clock back.
        if (askBusy) { replyText.removeCallbacks(askTick); askTick.run() }
        buildChips()
        bindOverlays()
        map.onRoutePointPicked = { en, alongM -> pointPicked(en, alongM) }
        refreshMarks()
        // The phone never holds GPS outside a recording any more; a hold
        // left running by the earlier design dies here rather than at its
        // old deadline.
        if (!recording && !wantRecording && warmUntil > 0) {
            warmUntil = 0
            TrackingService.stop(this)
        }
        // Walks the assistant queued from the chat screen are waiting here
        // when he comes back to the map.
        maybeShowPicker()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locator = Locator(this, { en, stale ->
                lastFix = en
                if (!stale) lastFixAt = System.currentTimeMillis()
                map.setFix(en.e, en.n, stale)
                // With the map open and no recording running, these fixes
                // are the only ones anybody has — the buzz watches them.
                if (!stale && !recording) {
                    runCatching {
                        Marks.arrivedAt(this, en)?.let {
                            Marks.buzz(this, it)
                            refreshMarks()
                        }
                    }
                }
                if (!stale) runCatching { updateMarkReadout(en) }
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
        replyText.removeCallbacks(askTick)
        stopWxPlay()
        timerChip.removeCallbacks(timerTick)
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
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Radar.trim()
            // The cached path network is the biggest thing this app holds.
            Router.trim()
            Library.trim()
        }
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
        updateTimer()
    }

    /**
     * The walk clock, ticking beside the temperature while recording. The
     * tick stops itself the moment recording does — paintRecordButton runs
     * on toggle, on resume and on every trail broadcast, so the pill dies
     * within a second of the service stopping, from either wrist or phone.
     */
    private val timerTick = object : Runnable {
        override fun run() {
            if (!alive || !recording || recordingStartedAt == 0L) {
                timerChip.visibility = View.GONE
                return
            }
            val s = (System.currentTimeMillis() - recordingStartedAt) / 1000
            timerChip.text =
                if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                else "%d:%02d".format(s / 60, s % 60)
            timerChip.visibility = View.VISIBLE
            timerChip.postDelayed(this, 1_000)
        }
    }

    private fun updateTimer() {
        timerChip.removeCallbacks(timerTick)
        timerTick.run()
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
        // One chip for the whole sky — rain, wind, cloud and the
        // temperature figure come on and off together, his call: "it's all
        // kinda relevant isn't it". Settings still decides which parts the
        // chip includes.
        Layer(
            "Weather",
            radarEnabled || windEnabled || tempEnabled || cloudEnabled,
            weatherShown,
        ) { weatherShown = it },
        // A mode, not an overlay, but the chip row is exactly the right
        // switchboard: visible, one tap, learnable. Only offered when there
        // is a route to mark.
        Layer("Mark points", RouteStore.load(this) != null, map.pickMode) {
            map.pickMode = it
            if (it) {
                sayBriefly("Tap a point on the route — a turn, a peak — to see how far and get a buzz there.")
            }
        },
        // Not a layer — a way through to the full forecast, sitting in the
        // one row he already uses as a switchboard. The temperature chip
        // opens it too, but that is only on screen when the overlay is.
        Layer("Forecast ›", true, false) { openWeather() },
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
    /**
     * The full forecast for the middle of the map — not for his GPS fix.
     * His words were "wherever my map is", and that is the useful reading:
     * the walk he is weighing up is often an hour's drive away.
     */
    private fun openWeather() {
        val b = map.viewportBounds()
        startActivity(
            Intent(this, WeatherActivity::class.java)
                .putExtra(WeatherActivity.EXTRA_E, (b[0] + b[2]) / 2)
                .putExtra(WeatherActivity.EXTRA_N, (b[1] + b[3]) / 2),
        )
    }

    private val wantRadar: Boolean get() = radarEnabled && weatherShown
    private val wantWind: Boolean get() = windEnabled && weatherShown
    private val wantCloud: Boolean get() = cloudEnabled && weatherShown
    private val wantTemp: Boolean get() = tempEnabled && weatherShown

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
        if (!wantCloud) map.setSky(null)
        if (!wantTemp) tempChip.visibility = View.GONE
        if (!wantWeather) {
            stopWxPlay()
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
            stopWxPlay()
            wxBar.visibility = View.GONE
            return
        }
        if (!pickerShowing) wxBar.visibility = View.VISIBLE
        wxSeek.max = frames.size - 1
        val i = Timeline.indexOfNow(frames, keep ?: System.currentTimeMillis())
        wxSeek.progress = i
        showWxFrame(i)
    }

    /**
     * The scrubber, driven: about a frame and a half a second around the
     * whole ten hours, looping, until his finger or the ⏸ takes over. The
     * prefetch showWxFrame already does keeps the loop a beat ahead of the
     * downloads after the first lap.
     */
    private val wxPlayTick = object : Runnable {
        override fun run() {
            if (!wxPlaying || wxFrames.isEmpty() || !alive) return
            val next = (wxIndex + 1) % wxFrames.size
            wxSeek.progress = next
            showWxFrame(next)
            wxPlay.postDelayed(this, 700L)
        }
    }

    private fun startWxPlay() {
        if (wxFrames.isEmpty()) return
        wxPlaying = true
        wxPlay.text = "⏸"
        wxPlay.removeCallbacks(wxPlayTick)
        wxPlay.post(wxPlayTick)
    }

    private fun stopWxPlay() {
        wxPlaying = false
        wxPlay.text = "▶"
        wxPlay.removeCallbacks(wxPlayTick)
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
        wxLabel.text = frameLabel(frame) + rainWords(field, hour, frame) + readings(field, hour)
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
        // Every part painted right now gets its line in the key. The scale
        // named for the radar is the one actually being fetched: if the
        // server refused his choice, the tiles are the fallback's colours,
        // and a key naming his preference would describe nothing on screen.
        val radar = if (wantRadar && frame.radarPath != null) Radar.schemeNow() else 0
        val sky = wantCloud && haveField
        val key = "$radar/$sky"
        if (key == legendKey) return
        legendKey = key
        wxLegend.removeAllViews()
        wxLegend.visibility = if (radar == 0 && !sky) View.GONE else View.VISIBLE
        // The forecast-rain swatches went with the forecast-rain layer. They
        // were honest while that layer existed and would describe nothing
        // now — a key for a picture that is no longer painted is exactly the
        // sort of thing that made this overlay hard to read.
        if (radar != 0) wxLegend.addView(legendNote(RADAR_SCALES[radar] ?: "RainViewer"))
        if (sky) {
            // No swatch for clear sky, because clear sky has no swatch: the
            // map is simply left alone, and saying so in words is the honest
            // key for it. High cloud barely registers by design; fog is its
            // own colour because it is its own problem.
            wxLegend.addView(legendNote("Sky — clear map is clear sky"))
            wxLegend.addView(legendChip("high", Ramp.sky(0.0, 0.0, 100.0, 10_000.0)))
            wxLegend.addView(legendChip("low", Ramp.sky(100.0, 0.0, 0.0, 10_000.0)))
            wxLegend.addView(legendChip("fog", Ramp.sky(0.0, 0.0, 0.0, 500.0)))
        }
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
     * The weather as one picture: the sky wash (cloud and fog) under the
     * rain, wind over both, the temperature as a figure. The old
     * one-wash-at-a-time rule dated from when temperature was an opaque
     * full-bleed ramp; cloud is thin grey and rain saturated colour, and
     * they layer legibly.
     */
    private fun drawWeatherField(field: Weather.Field?, hour: Int, frame: WxFrame) {
        if (field == null || hour < 0) {
            map.setSky(null)
            map.setField(null, 255)
            return
        }
        map.setSky(if (wantCloud) BngMapView.MeshTile(
            Weather.renderSky(field, hour),
            field.south, field.west, field.north, field.east,
        ) else null)
        // Rain is drawn one way only: the radar. It used to fill the gap
        // beyond the radar's reach with the forecast grid, and that is what
        // he meant by "it flicks between different types of rain overlay" —
        // scrubbing past the nowcast swapped a sharp measured sweep in
        // RainViewer's palette for a blurry 5x5 model grid in ours, with no
        // warning and no way to read the two against each other.
        //
        // Two pictures of one quantity is worse than one picture and a
        // sentence. Beyond the radar the rain is said in words on the label
        // instead, so nothing is lost and nothing is disguised — and what
        // is left on the map is a single ten-minute radar animation, which
        // is the only genuinely sub-hourly rain there is.
        map.setField(null, 255)
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
            val vis = field.visibility[hour][centre]
            val low = field.cloudLow[hour][centre]
            val cloud = field.cloud[hour][centre]
            when {
                !vis.isNaN() && vis < Ramp.FOG_VIS_M -> sb.append(" · fog")
                !low.isNaN() && low >= 60 -> sb.append(" · low cloud")
                !cloud.isNaN() ->
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

    /**
     * What the rain is doing on a frame the radar does not reach.
     *
     * Load-bearing rather than decorative: with the forecast mesh gone, a
     * frame past the nowcast paints no rain at all, and an empty map reads
     * as a dry hour. It is not dry, it is unmeasured — and the model has a
     * number for it, which belongs here in words rather than as a second
     * kind of picture.
     */
    private fun rainWords(field: Weather.Field?, hour: Int, frame: WxFrame): String {
        if (!wantRadar || frame.radarPath != null) return ""
        if (field == null || hour < 0) return " · no radar this far out"
        val centre = (Weather.GRID / 2) * Weather.GRID + Weather.GRID / 2
        if (centre >= field.lat.size) return " · no radar this far out"
        val mm = field.rain[hour][centre]
        if (mm.isNaN()) return " · no radar this far out"
        val what = when {
            mm >= 2.0 -> "%.1f mm — wet".format(mm)
            mm >= 0.2 -> "%.1f mm".format(mm)
            mm > 0.02 -> "spitting"
            else -> "dry"
        }
        return " · no radar, forecast says $what"
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
            "Find walks…", "All walks…", "Draw a walk on the map",
            "Edit the loaded route", "Walk picker ‹ ›", "Import a GPX file",
            "Saved walks", "Drive to the start", hideLabel, "GPX library folder…",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> walkSpecifier()
                    1 -> openWalks()
                    2 -> startEditing()
                    3 -> {
                        val loaded = RouteStore.load(this)?.points
                        if (loaded == null || loaded.size < 2) {
                            say("No route loaded to edit — draw one, or import one first.")
                        } else {
                            startEditing(loaded)
                        }
                    }
                    4 -> reopenPicker()
                    5 -> pickGpx()
                    6 -> savedWalksDialog()
                    7 -> {
                        // Present even with no route, saying so — a menu item
                        // that comes and goes is a menu you can't learn.
                        val start = RouteStore.load(this)?.points?.firstOrNull()
                        if (start == null) say("No route loaded — import or find one first.")
                        else openParking(start)
                    }
                    8 -> {
                        routeHidden = !routeHidden
                        map.setRoute(if (routeHidden) null else RouteStore.load(this))
                        say(
                            if (routeHidden) "Route hidden — the map underneath is all yours. " +
                                "It is still stored, and still on the watch."
                            else "Route back on the map.",
                        )
                    }
                    9 -> libraryDialog()
                }
            }
            .show()
    }

    // --- saved walks ----------------------------------------------------------



    // --- the walk specifier: the form instead of the sentence --------------

    /**
     * "A little walk specifier so I don't have to type it every time."
     *
     * Four questions, remembered between openings: what shape, how long,
     * whether that length is hours or kilometres, and which day. It then
     * does the finding — real walks first, an invented one alongside — and
     * fills the same ‹ › picker everything else fills, so choosing is the
     * gesture he already knows.
     *
     * The reason this is a form and not a sentence to the assistant: the
     * assistant can already do all of it, and doing it that way costs a paid
     * call, a wait, and a sentence composed on a phone in the wind, every
     * single time. A question asked the same way every week is a form.
     */
    private fun walkSpecifier() {
        // No fix needed to *open* it any more. Two of the three starting
        // points — a tapped place, the map as framed — need no GPS at all,
        // and planning tomorrow's walk at a kitchen table is exactly when
        // there isn't one.
        val spec = WalkSpec.fromJson(walkSpec)

        fun heading(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(Color.argb(210, 150, 160, 152))
            setPadding(0, dp(14), 0, dp(4))
        }

        val shapes = arrayOf("Circular", "There and back", "Don\u2019t mind")
        val shapeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, shapes,
            )
            setSelection(
                when (spec.shape) {
                    Shape.CIRCULAR -> 0
                    Shape.OUT_AND_BACK -> 1
                    Shape.ANY -> 2
                },
            )
        }

        val origins = arrayOf(
            "Where I am (within 500 m)",
            "A point I\u2019ll tap (within 500 m)",
            "Anywhere on this map",
        )
        val originSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, origins,
            )
            setSelection(
                when (spec.origin) {
                    Origin.HERE -> 0
                    Origin.TAP -> 1
                    Origin.SCREEN -> 2
                },
            )
        }

        val days = (0..WalkSpec.MAX_DAY_OFFSET).map { WalkSpec.dayName(it) }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, days.toTypedArray(),
            )
            setSelection(spec.dayOffset.coerceIn(0, WalkSpec.MAX_DAY_OFFSET))
        }

        var inMiles = spec.miles
        val units = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.argb(255, 150, 210, 170))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        fun showUnits(byTimeNow: Boolean) {
            units.text = when {
                byTimeNow -> "hours"
                inMiles -> "miles ⇄"
                else -> "km ⇄"
            }
        }
        fun number(v: Double) = EditText(this).apply {
            setText(WalkSpec.trim(v))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 16f
            setSingleLine()
        }
        val fromBox = number(spec.from)
        val toBox = number(spec.to)


        val byTime = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(RadioButton(this@MainActivity).apply { id = 1; text = "Distance"; textSize = 14f })
            addView(RadioButton(this@MainActivity).apply { id = 2; text = "Time"; textSize = 14f })
            addView(RadioButton(this@MainActivity).apply { id = 3; text = "Any length"; textSize = 14f })
            check(if (spec.anyLength) 3 else if (spec.byTime) 2 else 1)
            setOnCheckedChangeListener { _, id ->
                showUnits(id == 2)
                // "Any length" is the old Walks-near-me: show me what is
                // here, never mind how long. The boxes have nothing to say.
                fromBox.isEnabled = id != 3
                toBox.isEnabled = id != 3
            }
        }
        showUnits(spec.byTime)
        // Tapping the unit swaps it. He thinks in both and asked for both;
        // everything downstream is metres either way.
        units.setOnClickListener {
            if (byTime.checkedRadioButtonId == 2) return@setOnClickListener
            inMiles = !inMiles
            showUnits(false)
        }

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(fromBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(this@MainActivity).apply {
                    text = "to"
                    textSize = 14f
                    setPadding(dp(10), 0, dp(10), 0)
                },
            )
            addView(toBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(units)
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(4))
            addView(heading("STARTING FROM"))
            addView(originSpinner)
            addView(heading("SHAPE"))
            addView(shapeSpinner)
            addView(heading("HOW LONG"))
            addView(byTime)
            addView(row)
            addView(heading("WHICH DAY"))
            addView(daySpinner)
            addView(
                TextView(this@MainActivity).apply {
                    text = "The day decides the weather in the brief, not which walks are " +
                        "found \u2014 a path is there whatever the day."
                    textSize = 12f
                    setTextColor(Color.argb(170, 150, 160, 152))
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Plan a walk")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Find walks") { _, _ ->
                val chosen = WalkSpec(
                    shape = when (shapeSpinner.selectedItemPosition) {
                        0 -> Shape.CIRCULAR
                        1 -> Shape.OUT_AND_BACK
                        else -> Shape.ANY
                    },
                    byTime = byTime.checkedRadioButtonId == 2,
                    anyLength = byTime.checkedRadioButtonId == 3,
                    miles = inMiles,
                    from = fromBox.text.toString().toDoubleOrNull() ?: spec.from,
                    to = toBox.text.toString().toDoubleOrNull() ?: spec.to,
                    dayOffset = daySpinner.selectedItemPosition.coerceIn(0, WalkSpec.MAX_DAY_OFFSET),
                    origin = when (originSpinner.selectedItemPosition) {
                        0 -> Origin.HERE
                        1 -> Origin.TAP
                        else -> Origin.SCREEN
                    },
                )
                // Saved before the search, not after: the point of the form is
                // that tomorrow it opens on today's answers, and a search he
                // cancels or that finds nothing was still an answer he gave.
                walkSpec = chosen.toJson()
                startSpec(chosen)
            }
            .show()
    }

    /** How long the invented loop may search before it answers with its
     *  best. His number, and it is spent in the background while the real
     *  walks are already on the picker. */
    private val specBudgetMs = 90_000L

    /** Where "published walks near the start" looks when the start's own
     *  licence turns up nothing: driving distance, said out loud as such. */
    private val widerSearchM = 12_000.0

    /** The widest "anywhere on this map" may honestly mean, as a radius.
     *  A 50 km view is a region; the whole country is not. */
    private val mapOriginMaxM = 25_000.0

    /**
     * Resolve where the walk may start, then search.
     *
     * Three answers, and only one of them needs a satellite. "A point I'll
     * tap" hands the map over for one tap; "anywhere on this map" takes the
     * screen as framed, which is the same question "Walks on this map"
     * already answers and the same one he asked here.
     */
    private fun startSpec(spec: WalkSpec) {
        forgetSpec()
        when (spec.origin) {
            Origin.HERE -> {
                val here = lastFix ?: run {
                    say(
                        "No GPS fix yet, so \u201Cwhere I am\u201D has nothing to work from. " +
                            "Pick a point on the map instead, or use the map as framed.",
                    )
                    return
                }
                runSpec(spec, here, Router.START_SLACK_M, null)
            }
            Origin.TAP -> awaitPlaceTap(spec)
            Origin.SCREEN -> {
                val b = map.viewportBounds()
                val centre = En((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
                // Half the diagonal is the honest radius of what he can see.
                val reach = kotlin.math.hypot(b[2] - b[0], b[3] - b[1]) / 2
                // Beyond this the words stop being true. The cap used to
                // clamp silently, so a map framed on the whole of Britain
                // became a 12 km circle round the middle of the screen —
                // which was somewhere near Shrewsbury while he was asking
                // about Snowdonia, with nothing anywhere saying so.
                if (reach > mapOriginMaxM) {
                    say(
                        "\u201CAnywhere on this map\u201D is currently about " +
                            "${Brief.fmtKm(reach * 2)} across \u2014 most of the country. " +
                            "Zoom in to the area you actually mean and ask again.",
                    )
                    return
                }
                runSpec(spec, centre, reach.coerceAtLeast(500.0), b)
            }
        }
    }

    /**
     * Hand the map over for one tap. The status line is the way out as well
     * as the instruction: a mode with no visible exit that eats every tap on
     * the map is how an app stops zooming and nobody can say why.
     */
    private fun awaitPlaceTap(spec: WalkSpec) {
        map.placeMode = true
        say("Tap the map where the walk should start \u2014 tap this line to cancel.")
        fun leave() {
            map.placeMode = false
            map.onPlacePicked = null
            status.isClickable = false
            status.setOnClickListener(null)
        }
        status.setOnClickListener {
            leave()
            sayBriefly("Cancelled \u2014 the map is back to normal.")
        }
        map.onPlacePicked = { en ->
            leave()
            runSpec(spec, en, Router.START_SLACK_M, null)
        }
    }

    /**
     * The specifier's search. Real walks go up first and the invented one
     * joins them when it lands — because the network fetch and the loop
     * search together take up to a minute and a half, and a picker he can
     * already flick through is worth more than a tidy single delivery.
     */
    private fun runSpec(spec: WalkSpec, here: En, slackM: Double, bounds: DoubleArray?) {
        val (pace, paceLabel) = currentPace()
        val (minM, maxM) = spec.rangeMetres(pace)
        pickDayOffset = spec.dayOffset
        picksFromSpec = true
        say(
            "Looking for ${spec.label()}" +
                (if (spec.byTime) " \u2014 ${Brief.fmtKm(minM)} to ${Brief.fmtKm(maxM)} at $paceLabel" else "") +
                "\u2026",
        )
        specJob?.cancel()
        specJob = scope.launch {
            // A walk he can start within the licence he gave, and no wider.
            // This used to search a flat 12 km, which quietly answered a
            // different question — "somewhere in the county" — and put walks
            // on the picker he would have had to drive to.
            val searchM = slackM.coerceAtLeast(Router.START_SLACK_M)
            // The close search and the wide one, started together.
            //
            // These used to run one after another — the close one, then an
            // 8 km one for stitching material, then a 12 km one if the
            // close one came back empty. Three queries to the same servers
            // asking the same question at three radii, each of which can
            // legitimately take most of a minute, and the answer to the
            // close one is a subset of the answer to the wide one anyway.
            // Up to three minutes of a status line that never changed:
            // that is what "it looks for ages and gets nothing" was.
            val wideM = maxOf(searchM, widerSearchM)
            val nearAsync = async(Dispatchers.IO) {
                runCatching { RouteFinder.find(this@MainActivity, here, searchM) }.getOrNull()
            }
            val wideAsync = if (wideM > searchM) {
                async(Dispatchers.IO) {
                    runCatching { RouteFinder.find(this@MainActivity, here, wideM) }.getOrNull()
                }
            } else {
                null
            }
            val found = nearAsync.await()
            val wide = wideAsync?.await() ?: found
            // OpenStreetMap *failing* and OpenStreetMap being *empty* are
            // different answers, and telling them apart is the whole
            // difference between "your signal is bad" and "Snowdonia has no
            // walks in it". He was shown the second when it was the first.
            val osmFailed = found == null || found.note != null
            fun narrow(r: RouteFinder.Result?) = Specifier.shortlist(
                r?.walks?.filter { w ->
                    bounds == null || w.lines.any { line ->
                        line.any { it.e in bounds[0]..bounds[2] && it.n in bounds[1]..bounds[3] }
                    }
                } ?: emptyList(),
                spec, minM, maxM,
            )
            var real = narrow(found)
            // Every named route near him, whatever its shape or length —
            // kept even when it is far too long to offer, because a
            // forty-kilometre trail he cannot walk today is still ground the
            // planner should route along where it goes his way.
            var trails = found?.walks.orEmpty()
            // Everything within reach, gathered once for stitching. The
            // list above is narrowed to his start licence, which is right
            // for what to *offer* and wrong for what to *build from*: a
            // coast path a mile off is material for the loop even though it
            // does not start where he does.
            val stitchable = if (!osmFailed && wide != null && wide.note == null) {
                wide.walks.filter { it.closestM <= 8_000.0 }
            } else {
                emptyList()
            }
            // A 500 m licence around a house in Anglesey contains no
            // published walking route at all, most days. Rather than an
            // empty picker, look once more at driving distance and say
            // outright that these are further out than he asked — the
            // widening is offered, never slipped in. Pointless when the
            // servers are down: the same servers answer the wider question.
            var widened = 0.0
            if (real.isEmpty() && !osmFailed && bounds == null && searchM < widerSearchM &&
                wide != null && wide.note == null
            ) {
                // Already in hand — the wide search ran alongside the close
                // one rather than after it failed.
                widened = widerSearchM
                real = narrow(wide)
                trails = wide.walks
            }

            // Nothing was fetched, so nothing can be planned either — the
            // paths and the routes come from the same servers. Say that once,
            // plainly, and don't spend ninety seconds proving it again.
            if (osmFailed) {
                if (real.isNotEmpty()) {
                    WalkPicks.replace(this@MainActivity, real)
                    showSpecPicks()
                } else {
                    pickDayOffset = 0
                }
                picksFromSpec = real.isNotEmpty()
                say(
                    (found?.note ?: "No route data could be fetched.") +
                        " Nothing can be planned either \u2014 the paths come from the same " +
                        "place. That is a connection problem, not an empty area.",
                )
                return@launch
            }

            // Walks were found, but none of the right shape or length. An
            // empty picker reads as "there is nothing here", which around
            // Snowdonia is a lie: a national trail is real walking ground
            // and is only unmatched because it is forty kilometres and
            // straight. Offer them with what they actually are stated.
            var nearMiss = false
            if (real.isEmpty() && trails.isNotEmpty()) {
                real = Specifier.nearMisses(trails)
                nearMiss = real.isNotEmpty()
            }

            if (real.isNotEmpty()) {
                WalkPicks.replace(this@MainActivity, real)
                showSpecPicks()
                say(
                    when {
                        nearMiss ->
                            "Nothing of that shape and length. ${real.size} near there, each " +
                                "labelled with what it actually is \u2014 working out one of " +
                                "our own alongside\u2026"
                        widened > 0 ->
                            "Nothing published starts within ${Brief.fmtKm(searchM)} of there. " +
                                "${real.size} within ${Brief.fmtKm(widened)} \u2014 a drive, " +
                                "not a walk from the door. Working out one of our own " +
                                "alongside\u2026"
                        else ->
                            "${real.size} real walk${if (real.size == 1) "" else "s"} matching " +
                                "\u2014 working out one of our own alongside\u2026"
                    },
                )
            } else {
                say("No established walk near there at all. Working one out\u2026")
            }

            // Nothing to aim at, so nothing to invent — "any length" is a
            // question about what is already here.
            if (spec.anyLength) {
                if (real.isEmpty()) {
                    say("Nothing found round there at all.")
                } else {
                    say(
                        "${real.size} walk${if (real.size == 1) "" else "s"} round there \u2014 " +
                            "\u2039 \u203a to flick through, or All walks for the list.",
                    )
                }
                return@launch
            }

            // The invented one. Its shape follows the ask: nobody wants a
            // circuit when they asked to go out to something and come back.
            val target = (minM + maxM) / 2
            val askedGraphM = target / (2 * Math.PI) * 1.9 + 900 + slackM
            if (askedGraphM > Router.MAX_GRAPH_RADIUS_M) {
                // Router.buildCached clamps this silently — it has to, a
                // free Overpass mirror crashes on a query this wide — but a
                // silent clamp here would mean "anywhere on this map" over a
                // wide region quietly stopped covering the far side of it,
                // with nothing anywhere saying so.
                sayBriefly(
                    "The paths can only be read within " +
                        "${Brief.fmtKm(Router.MAX_GRAPH_RADIUS_M)} of here — the start " +
                        "region you gave reaches further than that.",
                )
            }
            // A separate wait, and a long one on a path-dense area: the bar
            // said "working one out" through both the fetch and the search,
            // so a slow network looked like a slow planner.
            sayBriefly("Reading the path network round here\u2026")
            val plannedAll: List<Router.Planned> = withContext(Dispatchers.IO) {
                runCatching {
                    // The network has to cover the start region as well as
                    // the walk: a loop beginning at the far edge of the map
                    // needs the paths out there to be in the graph.
                    val graph = Router.buildCached(here, askedGraphM)
                    if (graph.nodes.size < 20) return@runCatching emptyList<Router.Planned>()
                    // His idea: use the confirmed path and work out the rest.
                    // The named routes already fetched above are laid onto
                    // the graph, and the ways under them become cheap to
                    // walk — so a loop runs along waymarked ground where
                    // that goes his way, and finds its own way home where it
                    // doesn't. No extra request: this is the same geometry
                    // the real-walk search just downloaded.
                    val lay = (trails + stitchable).distinctBy { it.name to it.lengthM }
                    graph.markTrails(lay.flatMap { it.lines })
                    val deadline = System.currentTimeMillis() + specBudgetMs
                    if (spec.shape == Shape.OUT_AND_BACK) {
                        listOfNotNull(
                            Router.outAndBack(
                                graph, here, target, deadline, startSlackM = slackM,
                            ) { note -> runOnUiThread { if (alive) sayBriefly(note) } },
                        )
                    } else {
                        // Several genuinely different circuits, not one
                        // nearly-right one — "go to an area and have loads
                        // of walks" is answered by the search keeping what
                        // it closes instead of discarding all but the best.
                        Router.loops(
                            graph, here, target, deadline, startSlackM = slackM,
                            wanted = 3,
                        ) { note -> runOnUiThread { if (alive) sayBriefly(note) } }
                    }
                }.getOrDefault(emptyList())
            }
            // Offered if anywhere near the asked range; the best one is
            // kept whatever its length, because "6.1 km against 10 asked,
            // said plainly" beats an empty answer.
            // "i == 0 ||" used to let the best-scored loop through at any
            // length at all, which is how a 5–15 km ask came back with 50 km.
            // In range, or short of it and honest about that; never a walk
            // several times what he asked for.
            val inRange = plannedAll.filter { it.metres in (minM * 0.7)..(maxM * 1.3) }
            val offered = if (inRange.isNotEmpty()) {
                inRange
            } else {
                plannedAll.filter { it.metres < minM }.take(1)
            }
            if (offered.isEmpty()) {
                if (real.isEmpty()) {
                    // Nothing of his on the picker, so nothing on it was
                    // specified: a later search from anywhere else must not
                    // inherit this day and start briefing for Saturday.
                    picksFromSpec = false
                    pickDayOffset = 0
                    say(
                        "Nothing found and nothing plannable: no established walk of that " +
                            "length starts within ${Brief.fmtKm(searchM)} of there, and the " +
                            "paths round it wouldn\u2019t close one either. A different length, " +
                            "a wider start, or somewhere a mile or two away usually does it.",
                    )
                } else {
                    say("${real.size} to choose from \u2014 \u2039 \u203a to flick through, " +
                        "Brief for the day\u2019s plan. (Couldn\u2019t work out one of our own.)")
                }
                return@launch
            }
            val shapeWord = if (spec.shape == Shape.OUT_AND_BACK) "there and back" else "circular"
            for (planned in offered) {
                // Named for the ground it actually used, and only when it
                // used enough of it to be worth saying. One trail near here
                // can be named; several and it would be a guess which one it
                // ran along, so it says what it can stand behind.
                val onTrail = if (planned.trailM >= 400) {
                    if (trails.size == 1) " via ${trails.first().name}" else " on waymarked paths"
                } else {
                    ""
                }
                WalkPicks.append(
                    this@MainActivity,
                    RouteFinder.FoundWalk(
                        name = "Planned ${Brief.fmtKm(planned.metres)} $shapeWord$onTrail",
                        source = "Planned",
                        lines = listOf(planned.points),
                        closestM = 0.0,
                        lengthM = planned.metres,
                    ),
                )
            }
            // Re-stated rather than assumed: a picker dismissed while this
            // was searching cleared the day, and the loops landing are a
            // fresh picker full of walks specified for it.
            picksFromSpec = true
            pickDayOffset = spec.dayOffset
            showSpecPicks()
            val best = offered.first()
            val shortOf = if (best.metres < minM * 0.95) {
                " \u2014 shorter than the ${Brief.fmtKm(minM)} you asked for, but it is a " +
                    "real circuit and the paths round there would not make a longer one"
            } else {
                ""
            }
            val roads = best.roadSummary()
            val trailNote = if (best.trailM >= 400) {
                ", ${Brief.fmtKm(best.trailM)} of it on waymarked routes"
            } else {
                ""
            }
            say(
                (if (offered.size > 1) {
                    "Planned ${offered.size} different $shapeWord walks from here \u2014 " +
                        "best is ${Brief.fmtKm(best.metres)}$trailNote"
                } else {
                    "Planned a ${Brief.fmtKm(best.metres)} $shapeWord from here" + trailNote
                }) + shortOf +
                    (roads?.let { ", including $it" } ?: ", off the roads") +
                    ". ${picks.size} to choose from \u2014 \u2039 \u203a to flick through, " +
                    "Brief for the day\u2019s plan.",
            )
        }
    }

    // --- drawing a walk by tapping the map ---------------------------------

    /**
     * Start drawing. The path network is fetched once in the background so
     * the first tap-to-tap leg snaps without a wait; until it lands, legs
     * fall back to straight lines and say so rather than blocking him.
     */
    private fun startEditing(from: List<En>? = null) {
        forgetSpec()
        dismissPicker()
        val ed = RouteEdit { a, b, snap ->
            val g = editGraph ?: return@RouteEdit null
            Router.between(g, a, b, avoidRoads = snap == RouteEdit.Snap.PATHS)?.points
        }
        from?.takeIf { it.size >= 2 }?.let { ed.load(it) }
        editing = ed
        editBar.visibility = View.VISIBLE
        wxBar.visibility = View.GONE
        map.placeMode = true
        map.onPlacePicked = { en -> editTapped(en) }
        refreshEdit()
        say(
            "Tap the map to lay the walk out. Each leg follows real paths between " +
                "your taps; tap a point again to remove it.",
        )
        // Centred on what he is looking at and sized to cover it, not on a
        // fixed 6 km around his fix: he draws across the map in front of
        // him, and a leg whose ends fall outside the graph has no path to
        // find. That is why every leg came back straight.
        val b = map.viewportBounds()
        val centre = En((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)
        val reach = kotlin.math.hypot(b[2] - b[0], b[3] - b[1]) / 2 + 2_000.0
        loadEditGraph(centre, reach)
    }

    /**
     * Fetch the walking network for the area being drawn in, and re-snap
     * what is already there. Failure is stated on the bar rather than in a
     * line that scrolls away — with every leg drawn straight, the one thing
     * he needs to know is whether that is the ground or the fetch.
     */
    private fun loadEditGraph(centre: En, radiusM: Double) {
        editJob?.cancel()
        editStat.text = "Reading the paths round here…"
        editJob = scope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching { Router.buildCached(centre, radiusM) }
            }
            if (editing == null) return@launch
            val graph = g.getOrNull()
            if (graph == null || graph.nodes.size < 20) {
                editGraph = null
                editPathsNote = if (graph == null) {
                    "no paths loaded (" + (g.exceptionOrNull()?.message?.take(60) ?: "failed") + ")"
                } else {
                    "no paths mapped here"
                }
                refreshEdit()
                say(
                    "Couldn't read the path network, so legs are straight lines. " +
                        (g.exceptionOrNull()?.let { Assistant.explain(it) } ?: "") +
                        " Tap Paths to try again.",
                )
                return@launch
            }
            editGraph = graph
            editGraphCentre = centre
            editGraphRadius = radiusM
            editPathsNote = ""
            withContext(Dispatchers.IO) { editing?.resnapAll() }
            refreshEdit()
            sayBriefly("${graph.nodes.size} path junctions loaded — legs snap to them now.")
        }
    }

    /** A tap while drawing: on a point removes it, anywhere else adds one. */
    private fun editTapped(en: En) {
        val ed = editing ?: return
        // A thumb's width at this zoom, so the hit target is the drawn
        // handle rather than a mathematical point.
        val withinM = 22.0 * resources.displayMetrics.density * map.metresPerPixel()
        val hit = ed.anchorNear(en, withinM)
        // Land the handle on the path he aimed at, not where his thumb
        // actually came down. On OS Maps a point dropped near a footpath
        // sits on it, and the difference is not cosmetic: a handle a few
        // metres off the line leaves a stub of straight leg at both ends of
        // every segment, which is most of what "it doesn't stick to
        // footpaths" looked like. Never in Straight mode — there the whole
        // point is that no path is being claimed.
        val g = editGraph
        val place = if (hit < 0 && g != null && ed.snap != RouteEdit.Snap.STRAIGHT) {
            g.onWay(en, maxOf(Router.TAP_SNAP_M, withinM))?.at ?: en
        } else {
            en
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                if (hit >= 0) ed.removeAt(hit) else ed.add(place)
            }
            refreshEdit()
            // Drawn past the edge of what was fetched: widen and re-snap,
            // rather than quietly drawing the rest of the walk straight.
            val c = editGraphCentre
            if (hit < 0 && c != null) {
                val out = kotlin.math.hypot(en.e - c.e, en.n - c.n)
                if (out > editGraphRadius - 500.0) {
                    loadEditGraph(c, (out + 2_500.0).coerceAtMost(Router.MAX_GRAPH_RADIUS_M))
                }
            }
        }
    }

    private fun refreshEdit() {
        val ed = editing ?: return
        map.setPreview(if (ed.count() > 0) listOf(ed.line()) else emptyList())
        map.setEditHandles(ed.anchorPoints())
        val warn = when {
            editPathsNote.isNotBlank() -> " · $editPathsNote"
            ed.hasUnsnapped() -> " · some legs straight (no path between those points)"
            else -> ""
        }
        editStat.text = when (ed.count()) {
            0 -> "Tap to place the first point"
            1 -> "1 point · tap the next"
            else -> "${ed.count()} points · ${Brief.fmtKm(ed.metres())}$warn"
        }
    }

    private fun cycleSnap() {
        val ed = editing ?: return
        if (editGraph == null && ed.snap != RouteEdit.Snap.STRAIGHT) {
            // Nothing to snap to yet: the useful thing this button can do is
            // fetch again, not cycle a preference that cannot be honoured.
            val b = map.viewportBounds()
            loadEditGraph(
                En((b[0] + b[2]) / 2, (b[1] + b[3]) / 2),
                kotlin.math.hypot(b[2] - b[0], b[3] - b[1]) / 2 + 2_000.0,
            )
            return
        }
        ed.snap = when (ed.snap) {
            RouteEdit.Snap.PATHS -> RouteEdit.Snap.ANY
            RouteEdit.Snap.ANY -> RouteEdit.Snap.STRAIGHT
            RouteEdit.Snap.STRAIGHT -> RouteEdit.Snap.PATHS
        }
        editSnapBtn.text = when (ed.snap) {
            RouteEdit.Snap.PATHS -> "Paths"
            RouteEdit.Snap.ANY -> "Any way"
            RouteEdit.Snap.STRAIGHT -> "Straight"
        }
        sayBriefly(
            when (ed.snap) {
                RouteEdit.Snap.PATHS -> "Sticking to paths, tracks and bridleways where it can."
                RouteEdit.Snap.ANY -> "Any walkable way now, lanes and roads included."
                RouteEdit.Snap.STRAIGHT -> "Straight lines — for open ground and beaches."
            },
        )
        scope.launch {
            withContext(Dispatchers.IO) { ed.resnapAll() }
            refreshEdit()
        }
    }

    private fun undoEdit() {
        val ed = editing ?: return
        if (!ed.undo()) { sayBriefly("Nothing left to undo."); return }
        refreshEdit()
    }

    private fun closeEditLoop() {
        val ed = editing ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ed.closeLoop() }
            if (!ok) { sayBriefly("Three points at least before it can come home."); return@launch }
            refreshEdit()
        }
    }

    private fun saveEdit() {
        val ed = editing ?: return
        val line = ed.line()
        if (line.size < 2) { sayBriefly("Nothing drawn yet."); return }
        val box = EditText(this).apply {
            setText("My walk")
            setSingleLine()
            setPadding(dp(22), dp(14), dp(22), dp(6))
        }
        AlertDialog.Builder(this)
            .setTitle("Save this walk")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val route = Route(box.text.toString().ifBlank { "My walk" }, line)
                RouteStore.save(this, route)
                stopEditing(save = true)
                say("“${route.name}” saved — ${Brief.fmtKm(Geom.length(line))}. Fetching tiles…")
                // publishRoute suspends (it prefetches a tile corridor), so
                // it belongs in the scope, not in a dialog click.
                importJob?.cancel()
                importJob = scope.launch { publishRoute(route) }
            }
            .show()
    }

    private fun stopEditing(save: Boolean) {
        editJob?.cancel()
        editJob = null
        editing = null
        editGraph = null
        editGraphCentre = null
        editPathsNote = ""
        editBar.visibility = View.GONE
        map.placeMode = false
        map.onPlacePicked = null
        map.setEditHandles(emptyList())
        map.setPreview(emptyList())
        if (wxFrames.isNotEmpty()) wxBar.visibility = View.VISIBLE
        if (!save) {
            map.setRoute(if (routeHidden) null else RouteStore.load(this))
            sayBriefly("Drawing cancelled — nothing saved.")
        }
    }

    /** These walks were not specified, so no day is attached to them and
     *  the brief button briefs for today. */
    private fun forgetSpec() {
        specJob?.cancel()
        specJob = null
        // A half-armed "tap where it starts" left on would eat every tap on
        // the map, and the map would simply stop zooming with nothing on
        // screen to say why.
        map.placeMode = false
        map.onPlacePicked = null
        status.isClickable = false
        status.setOnClickListener(null)
        picksFromSpec = false
        pickDayOffset = 0
    }

    /** Show or refresh the picker on the specifier's candidates, keeping the
     *  one he is looking at rather than snapping back to the first. */
    private fun showSpecPicks() {
        val pending = WalkPicks.pending(this)
        if (pending.isEmpty()) return
        if (pickerShowing) {
            val at = pickIndex
            picks = pending
            showPick(at.coerceAtMost(picks.size - 1))
        } else {
            maybeShowPicker()
        }
    }

    private fun fmtDist(m: Double) =
        if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000)

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

    // --- marked points: tap a turn, get a buzz there -------------------------

    /**
     * The next armed mark, counting itself down live — distance along the
     * route, minutes at his pace, climb — refreshed as fixes arrive. The
     * card a tap opens is the full answer at a moment; this is the walking
     * version of it, and it is honest about staleness by simply vanishing
     * when there are no marks or no route.
     */
    private fun updateMarkReadout(fix: En) {
        val now = System.currentTimeMillis()
        if (now - markReadoutAt < 2_000) return
        markReadoutAt = now
        val route = RouteStore.load(this) ?: run { markChip.visibility = View.GONE; return }
        val fingerprint = RouteHeights.fingerprint(route)
        val marks = Marks.load(this, fingerprint)
        if (marks.isEmpty()) { markChip.visibility = View.GONE; return }

        val cum = cumCache?.takeIf { it.first == fingerprint }?.second
            ?: Eta.cumulative(route.points).also { cumCache = fingerprint to it }
        val hereAlong = cum[Eta.nearestIndex(route.points, fix)]

        fun ahead(m: Mark) = if (routeReversed) hereAlong - m.alongM else m.alongM - hereAlong
        // The next one in the walking direction; with none ahead, the
        // nearest behind, said as behind.
        val next = marks.filter { ahead(it) > -Marks.ARRIVE_M }.minByOrNull { ahead(it) }
            ?: marks.minByOrNull { -ahead(it) } ?: return
        val aheadM = ahead(next)

        val heights = heightsCache?.takeIf { it.first == fingerprint }?.second
            ?: RouteHeights.cached(this, route).also { heightsCache = fingerprint to it }
        val up = heights?.let {
            Eta.climbBetween(it.alongs, it.heights, hereAlong, next.alongM).first
        }

        val pace = paceCache?.takeIf { now - it.third < 5 * 60_000L }
            ?: currentPace().let { (p, src) -> Triple(p, src, now).also { t -> paceCache = t } }
        val mins = Eta.minutes(kotlin.math.abs(aheadM), up ?: 0.0, pace.first)

        val sb = StringBuilder("➤${next.number}  ")
        sb.append(fmtDist(kotlin.math.abs(aheadM)))
        if (aheadM < -Marks.ARRIVE_M) sb.append(" back")
        sb.append(" · ").append(fmtMins(mins))
        up?.let { if (it >= 5) sb.append(" · ↑${it.roundToInt()} m") }
        markChip.text = sb.toString()
        markChip.visibility = View.VISIBLE
        markChip.setOnClickListener { pointPicked(next.en(), next.alongM) }
    }

    private fun refreshMarks() {
        markReadoutAt = 0L
        lastFix?.let { runCatching { updateMarkReadout(it) } }
        val route = RouteStore.load(this) ?: run {
            map.setMarks(emptyList())
            markChip.visibility = View.GONE
            return
        }
        map.setMarks(
            Marks.load(this, RouteHeights.fingerprint(route)).map { it.en() to it.number },
        )
    }

    /**
     * A tapped route point becomes an answer: how far along the route, how
     * long at *his* pace with the climb priced in, how much up and down —
     * and an offer to buzz when he gets there.
     */
    private fun pointPicked(en: En, alongViewM: Double) {
        val route = RouteStore.load(this) ?: return
        val fingerprint = RouteHeights.fingerprint(route)

        // Tapping an existing flag answers the same questions as the tap
        // that set it — recomputed from where he is now, since "how far to
        // mark 2" changes with every step — with Remove as the other button.
        val existing = Marks.load(this, fingerprint)
            .firstOrNull { kotlin.math.hypot(it.e - en.e, it.n - en.n) <= Marks.ARRIVE_M }

        scope.launch {
            // Everything measured against the full route line, not the
            // decimated drawing copy the tap snapped to — and for a flag,
            // against the flag's own stored point, not the vertex nearest
            // the finger.
            val cum = Eta.cumulative(route.points)
            val targetAlong = existing?.alongM ?: cum[Eta.nearestIndex(route.points, en)]
            val here = lastFix
            val hereAlong = here?.let { cum[Eta.nearestIndex(route.points, it)] }

            // Positive means ahead in the direction he is walking the route.
            val aheadM = when {
                hereAlong == null -> targetAlong
                routeReversed -> hereAlong - targetAlong
                else -> targetAlong - hereAlong
            }

            val heights = withContext(Dispatchers.IO) {
                RouteHeights.cached(this@MainActivity, route)
                    ?: runCatching { RouteHeights.fetch(this@MainActivity, route) }.getOrNull()
            }
            val climb = heights?.let {
                Eta.climbBetween(it.alongs, it.heights, hereAlong ?: 0.0, targetAlong)
            }

            val (pace, paceSource) = currentPace()
            val mins = Eta.minutes(kotlin.math.abs(aheadM), climb?.first ?: 0.0, pace)
            val arrive = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
                .format(java.util.Date(System.currentTimeMillis() + (mins * 60_000).toLong()))

            val sb = StringBuilder()
            sb.append(
                when {
                    hereAlong == null -> "${fmtDist(kotlin.math.abs(aheadM))} from the route start"
                    aheadM < -Marks.ARRIVE_M -> "${fmtDist(-aheadM)} behind you on the route"
                    else -> "${fmtDist(aheadM)} ahead on the route"
                },
            )
            sb.append("\nAbout ${fmtMins(mins)} ($paceSource) — there ~$arrive")
            sb.append(
                climb?.let { "\n${it.first.roundToInt()} m up, ${it.second.roundToInt()} m down" }
                    ?: "\nNo height data yet — time is pace only",
            )

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(existing?.let { "Mark ${it.number}" } ?: "This point on the route")
                .setMessage(sb.toString())
            if (existing == null) {
                dialog.setPositiveButton("Buzz me there") { _, _ -> armMark(en, targetAlong, mins) }
                    .setNegativeButton("Close", null)
            } else {
                dialog.setPositiveButton("Close", null)
                    .setNegativeButton("Remove") { _, _ ->
                        Marks.remove(this@MainActivity, fingerprint, existing.number)
                        refreshMarks()
                    }
            }
            dialog.show()
        }
    }

    /**
     * His pace, most personal source first: this walk while recording, the
     * median of his saved walks, Naismith's book number last — and the card
     * says which it used, because a time is only trustable when its basis is.
     */
    private fun currentPace(): Pair<Double, String> {
        if (recording && recordingStartedAt > 0) {
            val dist = Geom.length(TrailStore.points(this))
            val mins = (System.currentTimeMillis() - recordingStartedAt) / 60_000.0
            if (dist > 500 && mins > 10) {
                val p = mins / (dist / 1000.0)
                if (p in 6.0..40.0) return p to "your pace today"
            }
        }
        Eta.paceFromWalks(
            Walks.list(this).map { it.distanceM to (it.endedAt - it.startedAt) },
        )?.let { return it to "your usual pace" }
        return Eta.DEFAULT_PACE_MIN_PER_KM to "a book pace"
    }

    private fun armMark(en: En, alongM: Double, etaMins: Double) {
        val route = RouteStore.load(this) ?: return
        val mark = Marks.add(this, RouteHeights.fingerprint(route), en.e, en.n, alongM)
        if (mark == null) {
            say("Five marks is the lot — tap one to remove it first.")
            return
        }
        refreshMarks()
        // No standalone GPS hold on the phone — he saw the "Holding GPS
        // ready" notification and called it: the hold belongs to tracking.
        // The buzz rides the recording service when one runs, and the open
        // map's own fixes otherwise, and the message says exactly that
        // rather than promising a pocket-buzz nothing is listening for.
        say(
            if (recording) "Mark ${mark.number} set — you'll get a buzz there."
            else "Mark ${mark.number} set — the buzz fires while recording, " +
                "or with the map open.",
        )
    }

    private fun fmtMins(mins: Double): String {
        val m = mins.roundToInt()
        return if (m >= 90) "${m / 60} h ${m % 60} min" else "$m min"
    }

    // --- the walk picker ----------------------------------------------------

    /**
     * Open the picker if the assistant has queued walks. Called on resume —
     * candidates found in the chat screen are waiting when he comes back to
     * the map — and after the map's own ask bar answers, which triggers no
     * resume. The store's TTL means a stale batch never haunts the map.
     */
    /** The full-screen list of walks — the readable half of the picker. */
    private fun openWalks() {
        val b = map.viewportBounds()
        startActivityForResult(
            Intent(this, WalksActivity::class.java)
                .putExtra("e", (b[0] + b[2]) / 2)
                .putExtra("n", (b[1] + b[3]) / 2),
            REQ_WALKS,
        )
    }

    /** What the walks screen sent back: edit the loaded route, or take one. */
    private fun walksResult(data: Intent) {
        data.getStringExtra(WalksActivity.RESULT_EDIT)?.let {
            RouteStore.load(this)?.points?.takeIf { p -> p.size >= 2 }?.let { p -> startEditing(p) }
            return
        }
        val name = data.getStringExtra(WalksActivity.RESULT_TAKE) ?: return
        // Matched by name against the batch the screen was showing: the
        // walks themselves are far too big to hand through an Intent.
        WalkPicks.pending(this).firstOrNull { it.name == name }?.let { adoptFound(it) }
            ?: runCatching { Walks.list(this) }.getOrDefault(emptyList())
                .firstOrNull { it.name == name }
                ?.let { walk ->
                    val route = Route(walk.name, walk.points)
                    RouteStore.save(this, route)
                    say("“${walk.name}” back on the map — fetching tiles…")
                    importJob?.cancel()
                    importJob = scope.launch { publishRoute(route) }
                }
    }

    /**
     * The way back to a picker he closed. Reads the batch dismissal and all
     * — closing the picker keeps it for six hours — and says plainly when
     * there is nothing waiting rather than doing nothing.
     */
    private fun reopenPicker() {
        val pending = WalkPicks.pending(this)
        if (pending.isEmpty()) {
            say(
                "No walks waiting on the picker. \u201CWalks on this map\u201D fills it " +
                    "from everything crossing the map in view; \u201CPlan a walk\u201D " +
                    "fills it for a length and a day.",
            )
            return
        }
        picks = pending
        pickIndex = 0
        pickerShowing = true
        pickerBar.visibility = View.VISIBLE
        wxBar.visibility = View.GONE
        showPick(0)
    }

    private fun maybeShowPicker() {
        if (pickerShowing) return
        val pending = WalkPicks.freshPending(this)
        if (pending.isEmpty()) return
        picks = pending
        pickIndex = 0
        pickerShowing = true
        pickerBar.visibility = View.VISIBLE
        // One bar at a time down there: the scrubber comes back when the
        // picking is done.
        wxBar.visibility = View.GONE
        showPick(0)
    }

    private fun showPick(i: Int) {
        if (picks.isEmpty()) return
        pickIndex = ((i % picks.size) + picks.size) % picks.size
        val walk = picks[pickIndex]
        val here = lastFix
        val towards = here?.let {
            " · " + fmtDist(walk.closestM) + " " +
                Sun.compass(WalkFilter.bearingDeg(it, WalkFilter.nearestPoint(it, walk.lines)))
        }.orEmpty()
        pickerTitle.text = "${walk.name} — ${fmtDist(walk.lengthM)}$towards · ${walk.source}"
        pickerCount.text = "${pickIndex + 1}/${picks.size}"
        map.setPreview(walk.lines)
        map.fitTo(walk.routePoints())
    }

    /** The one way out: every exit path — Use, Start, ✕ — comes through
     *  here, so the dashed preview can never be left leaked on the map. */
    private fun dismissPicker() {
        pickerShowing = false
        pickerBar.visibility = View.GONE
        map.setPreview(emptyList())
        // Kept, not deleted: the GPX menu's picker entry brings it back.
        WalkPicks.dismiss(this)
        picks = emptyList()
        // A loop still being worked out has nowhere to land once the picker
        // is gone, and letting it finish would re-create the file this line
        // just deleted — the picker would reappear on the next resume with
        // one lonely candidate in it.
        specJob?.cancel()
        specJob = null
        picksFromSpec = false
        pickDayOffset = 0
        if (wxFrames.isNotEmpty()) wxBar.visibility = View.VISIBLE
    }

    private fun usePick() {
        val walk = picks.getOrNull(pickIndex) ?: return
        // Read before dismissing: dismissPicker forgets which day these were
        // specified for, and the brief is about that day.
        val day = pickDayOffset
        val brief = picksFromSpec
        dismissPicker()
        adoptFound(walk)
        // "I select one. I then get a whole brief for the walk" — his words,
        // and only for a walk he specified: a route imported from a file has
        // no day attached and should not be answering questions about one.
        if (brief) showBrief(walk, day)
    }

    /** The brief on demand, for whatever is in front of him on the picker. */
    private fun briefPick() {
        val walk = picks.getOrNull(pickIndex) ?: return
        showBrief(walk, pickDayOffset)
    }

    /**
     * The whole brief, in a dialog: distance, climb, how long at his pace,
     * when to set off, and what is worth knowing before he does.
     *
     * Not the assistant's — [Brief] computes it — so it costs nothing, needs
     * no key, and the daylight half of it works with no signal.
     */
    private fun showBrief(walk: RouteFinder.FoundWalk, dayOffset: Int) {
        val body = TextView(this).apply {
            text = "Working the brief out…"
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Brief · ${WalkSpec.dayName(dayOffset)}")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .create()
        dialog.show()
        val (pace, paceLabel) = currentPace()
        val here = lastFix
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { Brief.compose(walk, dayOffset, pace, paceLabel, here) }
                    .getOrElse { "Couldn't work the brief out: ${it.message ?: it.javaClass.simpleName}" }
            }
            if (dialog.isShowing) body.text = text
        }
    }


    /**
     * Take the route and start the walk in one tap: adopt, record, timer.
     * Recording starts before the tile prefetch on purpose — publishRoute
     * takes a minute over a corridor, and the timer must clock the walk,
     * not the download.
     */
    private fun startWalkFromPick() {
        val walk = picks.getOrNull(pickIndex) ?: return
        dismissPicker()
        importJob?.cancel()
        importJob = scope.launch {
            try {
                val route = withContext(Dispatchers.IO) {
                    val full = walk.uri?.let { u ->
                        runCatching {
                            contentResolver.openInputStream(Uri.parse(u))!!.use { Gpx.parse(it) }
                        }.getOrNull()
                    }
                    (full?.copy(name = walk.name) ?: Route(walk.name, walk.routePoints()))
                        .also { RouteStore.save(this@MainActivity, it) } // banks the old route
                }
                if (!recording) toggleRecording()
                say("“${walk.name}” set and recording — fetching offline tiles…")
                publishRoute(route)
            } catch (e: Exception) {
                say("Couldn't start that walk: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Navigation to where the walk starts — the nearest mapped car park
     * within 500 m of the route start if OSM knows one, else the start
     * itself, said plainly. Fetched on the tap, never while he is flicking
     * through candidates: Overpass is slow and gated.
     */
    private fun openParking() {
        val walk = picks.getOrNull(pickIndex) ?: return
        walk.routePoints().firstOrNull()?.let { openParking(it) }
    }

    /**
     * Navigation to where a walk starts — the picker's Parking button and
     * the GPX menu's "Drive to the start" both land here.
     */
    private fun openParking(start: En) {
        sayBriefly("Finding parking near the start…")
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching { nearestParking(start) }.getOrNull()
            }
            val en = target ?: start
            if (target == null) {
                say("No mapped car park within 500 m — navigating to the route start.")
            }
            val (lat, lon) = Bng.toWgs84(en)
            val q = "%.6f,%.6f".format(java.util.Locale.UK, lat, lon)
            // Driving directions by default: he is driving to the car park.
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))
            try {
                startActivity(nav)
            } catch (e: android.content.ActivityNotFoundException) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$q?q=$q(Walk%20parking)")))
                } catch (e2: android.content.ActivityNotFoundException) {
                    say("Nothing on this phone opens map navigation.")
                }
            }
        }
    }

    /** The nearest OSM-mapped car park to a point, or null. */
    private fun nearestParking(start: En): En? {
        val (lat, lon) = Bng.toWgs84(start)
        val at = "%.6f,%.6f".format(java.util.Locale.UK, lat, lon)
        // nwr + out center covers car parks mapped as nodes, ways and
        // relations in one query shape.
        val json = Net.overpass("[out:json][timeout:15];nwr[\"amenity\"=\"parking\"](around:500,$at);out center 8;")
        val elements = org.json.JSONObject(json).getJSONArray("elements")
        var best: En? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val centre = el.optJSONObject("center")
            val la = centre?.optDouble("lat") ?: el.optDouble("lat", Double.NaN)
            val lo = centre?.optDouble("lon") ?: el.optDouble("lon", Double.NaN)
            if (la.isNaN() || lo.isNaN()) continue
            val en = Bng.fromWgs84(la, lo)
            val d = kotlin.math.hypot(en.e - start.e, en.n - start.n)
            if (d < bestD) { bestD = d; best = en }
        }
        return best
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
        if (requestCode == REQ_WALKS && resultCode == RESULT_OK && data != null) walksResult(data)
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

    /** The working strip's ticking line: a clock that moves is the whole
     *  difference between a five-minute plan and a dead call. */
    private val askTick = object : Runnable {
        override fun run() {
            if (!askBusy || !alive) return
            val secs = (System.currentTimeMillis() - askStartedAt) / 1000
            replyText.text = "Working %d:%02d · %s".format(secs / 60, secs % 60, askNote)
            replyText.postDelayed(this, 1_000)
        }
    }

    private fun askActivity(note: String) {
        if (!askBusy) return
        askNote = note
    }

    /** The strip's ✕ while a run is going: stop it. Cooperative — a call
     *  already in flight finishes, then the loop stands down having changed
     *  nothing further, and says so. */
    private fun stopAsk() {
        askCancelled = true
        askNote = "stopping after the current call…"
    }

    private fun sendAsk() {
        val question = askBox.text.toString().trim()
        if (question.isEmpty() || askBusy) return
        askBusy = true
        askCancelled = false
        askStartedAt = System.currentTimeMillis()
        askNote = "thinking…"
        askBox.setText("")
        Talk.add(this, Said(true, question))
        replyPanel.visibility = View.VISIBLE
        replyText.removeCallbacks(askTick)
        askTick.run()

        // Remember what the tools might change, so changes can be published.
        val routeBefore = RouteStore.load(this)?.let { it.name to it.points.size }

        askJob = scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    assistant.ask(question) { askCancelled }
                }

                Talk.add(
                    this@MainActivity,
                    Said(false, reply.text, reply.actions.map { it.summary }),
                )
                askBusy = false
                replyText.removeCallbacks(askTick)
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
                refreshMarks()
                maybeShowPicker()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Not a failure. He sent another question, or left the
                // screen — "That went wrong: Job was cancelled" is the app
                // reporting his own interruption back to him as a fault.
                // Rethrown rather than swallowed: the finally below still
                // clears askBusy, and cancellation must keep propagating or
                // structured concurrency stops meaning anything.
                throw e
            } catch (e: Exception) {
                // The stuck-busy bug in person: anything escaping here used
                // to leave askBusy true for ever, and every later send was
                // silently ignored — "hangs and doesn't do it", exactly.
                //
                // The wording goes through Assistant.explain, which reads the
                // whole cause chain: the SDK's own message for a dropped
                // connection is the bare phrase "Request failed", which is
                // what he was shown and told him nothing.
                val said = Assistant.explain(e)
                Talk.add(this@MainActivity, Said(false, said))
                replyText.text = "$said — send again to retry."
            } finally {
                askBusy = false
                replyText.removeCallbacks(askTick)
            }
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
