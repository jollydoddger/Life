package com.jollydoddger.waymark

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Glyph
import com.jollydoddger.waymark.shared.IconDrawable
import com.jollydoddger.waymark.shared.Prefs.anthropicKey
import android.Manifest
import android.content.pm.PackageManager

/**
 * The whole conversation, on its own screen.
 *
 * The map's reply panel shows the last answer and nothing else, which is the
 * right amount over a map — but it left no way to read back what was asked
 * an hour ago, and it sat there taking half the screen until it was noticed
 * and tapped away. So the panel becomes a short glance with a way in here,
 * and this is where the talking actually lives.
 */
class ChatActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * The map's last fix, handed over when this screen opened, and how old it
     * already was. There is no locator here — a chat window is not a reason
     * to start a second GPS session — so the fix ages honestly while the
     * screen is open and `where_am_i` can still say how stale it is.
     */
    private var fix: En? = null
    private var fixAgeAtOpen = Long.MAX_VALUE
    private var openedAt = 0L

    private val assistant by lazy {
        Assistant(
            this,
            GeoTools(
                this,
                { fix },
                {
                    if (fixAgeAtOpen == Long.MAX_VALUE) Long.MAX_VALUE
                    else fixAgeAtOpen + (System.currentTimeMillis() - openedAt)
                },
                // Planning a walk is minutes of several calls; the working
                // line under the conversation carries what it is doing,
                // beside a clock that moves.
                { msg -> runOnUiThread { doingNote = msg } },
                { cancelled },
            ),
        ).also { a ->
            a.onActivity = { n -> runOnUiThread { doingNote = n } }
        }
    }
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var box: EditText
    private var busy = false
    @Volatile private var cancelled = false
    private var startedAt = 0L
    private var doingNote = ""
    private var working: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            val w = working ?: return
            if (!busy) return
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            w.text = "Working %d:%02d · %s — tap to stop".format(secs / 60, secs % 60, doingNote)
            w.postDelayed(this, 1_000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // The same hold-to-talk the map has. This screen being the one place
    // in the app that could not listen was an accident of history, not a
    // decision.
    private val voice by lazy {
        Voice(this).apply {
            onFinal = { heard ->
                runOnUiThread {
                    box.setText(heard)
                    send()
                }
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this).apply { addView(column) }

        box = EditText(this).apply {
            hint = "Ask…"
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        }
        box.setTextColor(Palette.ink)
        box.setHintTextColor(Palette.inkFaint)
        val mic = View(this).apply {
            background = IconDrawable(Glyph.MIC, resources.displayMetrics.density)
            Ui.bindHoldToTalk(
                this,
                start = {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4)
                    } else {
                        voice.start()
                    }
                },
                stop = { voice.stop() },
            )
        }
        val send = View(this).apply {
            background = IconDrawable(Glyph.SEND, resources.displayMetrics.density)
            setOnClickListener { send() }
        }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Palette.sheet)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(6), dp(4))
            addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mic, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
            addView(send, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            // The list takes what is left after the ask bar, so the bar is
            // always reachable and the history is what scrolls.
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        setContentView(root)

        // Edge-to-edge from targetSdk 35: without this the ask bar sits under
        // the navigation bar, which is the bug the map's own bar already had.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION") top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION") bottom = insets.systemWindowInsetBottom
            }
            root.setPadding(0, top, 0, bottom)
            insets
        }

        openedAt = System.currentTimeMillis()
        if (intent?.hasExtra("e") == true) {
            fix = En(intent.getDoubleExtra("e", 0.0), intent.getDoubleExtra("n", 0.0))
            fixAgeAtOpen = intent.getLongExtra("fixAge", Long.MAX_VALUE)
        }

        redraw()

        // A question typed into the map's bar arrives here already asked.
        intent?.getStringExtra("ask")?.takeIf { it.isNotBlank() }?.let {
            box.setText(it)
            send()
        }
    }

    override fun onDestroy() {
        voice.dispose()
        scope.cancel()
        super.onDestroy()
    }

    private fun redraw() {
        column.removeAllViews()
        val all = Talk.load(this)
        if (all.isEmpty()) {
            column.addView(note("Nothing asked yet. Try “how far is left on this route?”"))
        }
        for (said in all) column.addView(bubble(said))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = Ui.LABEL
        setTextColor(Palette.inkMut)
        setPadding(dp(6), dp(20), dp(6), dp(6))
    }

    private fun bubble(said: Said): View {
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
            .format(java.util.Date(said.atMs))
        val body = StringBuilder(said.text)
        // Receipts stay visibly separate from the words. A reply saying it
        // set something and a tool that actually set it are different
        // claims, and the difference has to survive into the transcript.
        for (a in said.actions) body.append("\n✓ ").append(a)
        return TextView(this).apply {
            text = body.toString()
            textSize = Ui.BODY
            setTextColor(if (said.fromHim) Color.rgb(24, 30, 26) else Palette.ink)
            background = Ui.card(
                this@ChatActivity,
                if (said.fromHim) Color.rgb(214, 226, 214) else Palette.raised,
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = clock
            // A URL in a reply is usually the assistant handing over a walk
            // page it couldn't download from; it has to be one tap, not a
            // string to retype into a browser.
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setLinkTextColor(
                if (said.fromHim) Color.rgb(20, 90, 60) else Color.rgb(140, 200, 255),
            )
        }.also { view ->
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(6)
            lp.gravity = if (said.fromHim) Gravity.END else Gravity.START
            lp.leftMargin = if (said.fromHim) dp(40) else 0
            lp.rightMargin = if (said.fromHim) 0 else dp(40)
            view.layoutParams = lp
        }
    }

    private fun send() {
        val question = box.text.toString().trim()
        if (question.isEmpty() || busy) return
        if (anthropicKey.isEmpty()) {
            Talk.add(this, Said(false, "No Anthropic key yet — enter one in Settings."))
            redraw()
            return
        }
        busy = true
        cancelled = false
        startedAt = System.currentTimeMillis()
        doingNote = "thinking…"
        box.setText("")
        Talk.add(this, Said(true, question))
        redraw()
        working = note("…").also { w ->
            // The working line is also the stop button: cooperative, so a
            // call in flight finishes and the loop stands down having
            // changed nothing further.
            w.setOnClickListener { cancelled = true; doingNote = "stopping after the current call…" }
            column.addView(w)
            tick.run()
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { assistant.ask(question) { cancelled } }
                Talk.add(
                    this@ChatActivity,
                    Said(false, reply.text, reply.actions.map { it.summary }),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // His own interruption, not a fault — see MainActivity.
                throw e
            } catch (e: Exception) {
                // Anything escaping here used to leave the busy flag stuck
                // and every later send silently ignored.
                Talk.add(
                    this@ChatActivity,
                    Said(false, Assistant.explain(e) + " — send again to retry."),
                )
            } finally {
                busy = false
                working?.removeCallbacks(tick)
                working = null
                redraw()
            }
        }
    }
}
