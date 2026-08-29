package com.jollydoddger.waymark

import android.Manifest
import android.app.Activity
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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.Corridor
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.PoiStore
import com.jollydoddger.waymark.shared.Route
import com.jollydoddger.waymark.shared.Gpx
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole phone app: the map, four small buttons, one status line.
 * Open → you are an arrow on an OS map; import a GPX → a line to follow,
 * offline tiles fetched, the lot pushed to the watch.
 */
class MainActivity : Activity() {

    private lateinit var map: BngMapView
    private lateinit var status: TextView
    private lateinit var recentreBtn: Button
    private lateinit var recordBtn: Button
    private var locator: Locator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var importJob: Job? = null

    private var lastFix: En? = null
    private var lastFixAt = 0L
    private lateinit var voice: Voice
    private lateinit var askBox: EditText
    private lateinit var replyText: TextView
    private lateinit var replyPanel: ScrollView
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

        fun roundButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            setOnClickListener { onClick() }
        }

        val importBtn = roundButton("GPX") { pickGpx() }
        val reverseBtn = roundButton("⇄") {
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
        recentreBtn = roundButton("◉") { map.recentre() }
        recordBtn = roundButton("●") { toggleRecording() }
        val settingsBtn = roundButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(importBtn, reverseBtn, recentreBtn, recordBtn, settingsBtn).forEach {
                addView(it, LinearLayout.LayoutParams(dp(64), dp(56)).apply { topMargin = dp(6) })
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
        val micBtn = Button(this).apply {
            text = "🎤"
            textSize = 18f
            bindHoldToTalk(this)
        }
        val sendBtn = Button(this).apply {
            text = "➤"
            textSize = 18f
            setOnClickListener { sendAsk() }
        }
        val askBar = LinearLayout(this).apply {
            setBackgroundColor(Color.argb(235, 250, 250, 248))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
            addView(askBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(micBtn, LinearLayout.LayoutParams(dp(52), dp(44)))
            addView(sendBtn, LinearLayout.LayoutParams(dp(52), dp(44)))
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

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(buttons, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { rightMargin = dp(10) })
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ).apply { topMargin = dp(40); leftMargin = dp(10); rightMargin = dp(10) })
            addView(replyPanel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(220), Gravity.BOTTOM,
            ).apply { bottomMargin = dp(50) })
            addView(askBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
        setContentView(root)

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
        map.setRoute(RouteStore.load(this))
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locator = Locator(this, { en, stale ->
                lastFix = en
                if (!stale) lastFixAt = System.currentTimeMillis()
                map.setFix(en.e, en.n, stale)
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
            TrackingService.start(this)
            say("Recording — leaving a trail behind you")
        } else {
            TrackingService.stop(this)
            say("Stopped. The trail stays on the map until you start again.")
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
        recordBtn.text = if (recording) "■" else "●"
    }

    private fun say(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
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
                map.setRoute(routeNow)
            }
            askBusy = false
        }
    }

    /** Hold to talk, release to send — the finger coming off IS the send. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldToTalk(button: Button) {
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
