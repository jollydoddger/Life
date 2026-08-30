package com.jollydoddger.waymark

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.Colours
import com.jollydoddger.waymark.shared.Prefs
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.jollydoddger.waymark.shared.Prefs.allPathsEnabled
import com.jollydoddger.waymark.shared.Prefs.allPathsShown
import com.jollydoddger.waymark.shared.Prefs.anthropicKey
import com.jollydoddger.waymark.shared.Prefs.arrowColour
import com.jollydoddger.waymark.shared.Prefs.assistantEnabled
import com.jollydoddger.waymark.shared.Prefs.cloudEnabled
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import com.jollydoddger.waymark.shared.Prefs.prowEnabled
import com.jollydoddger.waymark.shared.Prefs.prowShown
import com.jollydoddger.waymark.shared.Prefs.radarEnabled
import com.jollydoddger.waymark.shared.Prefs.radarScheme
import com.jollydoddger.waymark.shared.Prefs.routeColour
import com.jollydoddger.waymark.shared.Prefs.screenTimeoutSec
import com.jollydoddger.waymark.shared.Prefs.tempEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesEnabled
import com.jollydoddger.waymark.shared.Prefs.tracesShown
import com.jollydoddger.waymark.shared.Prefs.weatherShown
import com.jollydoddger.waymark.shared.Prefs.trailColour
import com.jollydoddger.waymark.shared.Prefs.watchGpsWarm
import com.jollydoddger.waymark.shared.Prefs.windEnabled
import com.jollydoddger.waymark.shared.Prefs.windStyle
import com.jollydoddger.waymark.shared.Sync
import com.jollydoddger.waymark.shared.TileGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * One setting: the OS Data Hub API key. The test fetches a real Explorer-zoom
 * tile, because a key on the wrong plan works fine at every zoom except the
 * ones this app exists for — a 403 there must come back as words about the
 * plan, not look like a bug on a hillside.
 */
class SettingsActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val intro = TextView(this).apply {
            textSize = 15f
            text = "Waymark draws Ordnance Survey Leisure maps (the Explorer / Landranger " +
                "paper styles). It needs your own OS Data Hub API key from osdatahub.os.uk — " +
                "a Maps API project key, on the Premium (pay-as-you-go) plan. The free plan " +
                "stops at the 1:50k-ish zooms; Explorer 1:25k detail is Premium only.\n\n" +
                "The key lives on this phone (and is passed to your watch), nowhere else."
        }

        val keyBox = EditText(this).apply {
            hint = "OS Maps API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(osApiKey)
        }

        val result = TextView(this).apply { textSize = 15f }

        val save = Button(this).apply {
            text = "Save"
            setOnClickListener {
                osApiKey = keyBox.text.toString()
                result.text = "Saved. Sending to watch…"
                scope.launch {
                    try {
                        Sync.sendKey(this@SettingsActivity, osApiKey)
                        result.text = "Saved, and on its way to the watch."
                    } catch (e: Exception) {
                        result.text = "Saved on the phone. Watch not reachable just now — " +
                            "it will get the key with the next route import."
                    }
                }
            }
        }

        val test = Button(this).apply {
            text = "Test key"
            setOnClickListener {
                val key = keyBox.text.toString().trim()
                result.text = "Fetching an Explorer-zoom tile…"
                scope.launch { result.text = testKey(key) }
            }
        }

        val row = LinearLayout(this).apply {
            addView(save, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(test, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, dp(20), 0, dp(6))
        }

        // A fixed palette rather than a colour wheel: six choices are one
        // glanceable row, hittable with a cold thumb, and every one of them
        // reads against pale OS map paper — which free choice cannot promise.
        fun swatches(current: () -> Int, choose: (Int) -> Unit): LinearLayout {
            val row = LinearLayout(this)
            val cells = mutableListOf<Pair<View, Int>>()
            fun repaint() {
                cells.forEach { (view, colour) ->
                    view.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colour)
                        // The chosen one wears a ring, so it is obvious which is on.
                        setStroke(dp(if (colour == current()) 4 else 1), Color.DKGRAY)
                    }
                }
            }
            Colours.PALETTE.forEach { (name, colour) ->
                val cell = View(this).apply {
                    contentDescription = name
                    setOnClickListener {
                        choose(colour)
                        repaint()
                        pushStyle(result)
                    }
                }
                cells.add(cell to colour)
                row.addView(cell, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
            }
            repaint()
            return row
        }

        // Labelled chips, for the settings that aren't colours.
        fun choices(
            options: List<Pair<String, Int>>,
            current: () -> Int,
            sync: Boolean = true,
            choose: (Int) -> Unit,
        ): LinearLayout {
            val strip = LinearLayout(this)
            val cells = mutableListOf<Pair<TextView, Int>>()
            fun repaint() {
                cells.forEach { (view, value) ->
                    val on = value == current()
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(if (on) Color.rgb(29, 91, 79) else Color.rgb(232, 232, 232))
                        setStroke(dp(1), Color.DKGRAY)
                    }
                    view.setTextColor(if (on) Color.WHITE else Color.DKGRAY)
                }
            }
            options.forEach { (label, value) ->
                val cell = TextView(this).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setOnClickListener {
                        choose(value)
                        repaint()
                        // Only the settings the watch shares are worth a sync;
                        // a phone-only overlay colour is not.
                        if (sync) pushStyle(result)
                    }
                }
                cells.add(cell to value)
                strip.addView(cell, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(6) })
            }
            repaint()
            return strip
        }

        val claudeSwitch = Switch(this).apply {
            text = "  Ask bar (Claude)"
            textSize = 16f
            isChecked = assistantEnabled
        }
        val claudeNote = TextView(this).apply {
            textSize = 13f
            text = "Off by default. Waymark is a map, an arrow and a line; this adds a " +
                "question box at the bottom that can measure the route, find toilets and " +
                "cafés, and plan walks. It needs your own Anthropic API key and costs a " +
                "few pence a question. Still rough — switch it on when you fancy it."
        }
        val claudeBox = EditText(this).apply {
            hint = "Anthropic API key (sk-ant-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(anthropicKey)
        }
        val claudeSave = Button(this).apply {
            text = "Save"
            setOnClickListener {
                anthropicKey = claudeBox.text.toString()
                result.text = "Assistant key saved."
            }
        }
        val claudeTest = Button(this).apply {
            text = "Test key"
            setOnClickListener {
                anthropicKey = claudeBox.text.toString()
                result.text = "Asking Claude for one word…"
                scope.launch { result.text = testAnthropicKey(anthropicKey) }
            }
        }
        val claudeRow = LinearLayout(this).apply {
            addView(claudeSave, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(claudeTest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // The key only matters when the thing that uses it is switched on.
        val claudeKeyBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (assistantEnabled) View.VISIBLE else View.GONE
            addView(claudeBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
            addView(claudeRow)
        }
        claudeSwitch.setOnCheckedChangeListener { _, on ->
            assistantEnabled = on
            claudeKeyBlock.visibility = if (on) View.VISIBLE else View.GONE
            result.text = if (on) {
                "Ask bar on — it appears at the bottom of the map."
            } else {
                "Ask bar off. The map has the screen to itself again."
            }
        }

        val radarSwitch = Switch(this).apply {
            text = "  Rainfall radar"
            textSize = 16f
            isChecked = radarEnabled
            setOnCheckedChangeListener { _, on ->
                radarEnabled = on
                if (on) weatherShown = true
                result.text = if (on) "Rain radar on — part of the map's Weather chip."
                else "Rain radar off."
            }
        }
        val radarScales = listOf(
            "Original" to 1, "Universal Blue" to 2, "TITAN" to 3,
            "Weather Channel" to 4, "Meteored" to 5, "NEXRAD" to 6,
            "Rainbow" to 7, "Dark Sky" to 8,
        )
        val radarScaleNote = TextView(this).apply {
            textSize = 13f
            text = "Rain is coloured by how hard it is falling, on whichever of RainViewer's " +
                "scales you prefer. Original by default: Universal Blue fades to near-white " +
                "at its light end, and over pale Explorer paper that is no colour at all. " +
                "The key appears under the timeline on the map, and how heavily the whole " +
                "lot is painted is a slider there too — beside the map you are judging it " +
                "against, rather than in here."
        }

        val radarNote = TextView(this).apply {
            textSize = 13f
            text = "Rainfall painted boldly over the map, from real weather radars, with " +
                "a timeline along the bottom: drag it back through the last couple of " +
                "hours to see which way a shower is travelling, or forward through the " +
                "nowcast. Each frame says whether it is measured radar or a forecast.\n\n" +
                "Never cached: radar is now by definition, so with no signal there is " +
                "no radar rather than a stale lie about the sky. Phone only.\n\n" +
                "Weather data by RainViewer (rainviewer.com)."
        }

        val windSwitch = Switch(this).apply {
            text = "  Wind"
            textSize = 16f
            isChecked = windEnabled
            setOnCheckedChangeListener { _, on ->
                windEnabled = on
                if (on) weatherShown = true
                result.text = if (on) "Wind on — part of the map's Weather chip."
                else "Wind off."
            }
        }
        val windStyles = listOf("Drifting lines" to 1, "Arrows" to 0)
        val windNote = TextView(this).apply {
            textSize = 13f
            text = "Lines drifting the way the air is going, coloured by speed: grey a " +
                "breath, green a breeze, amber when it starts pushing you about, red " +
                "when an exposed ridge stops being a good idea. Arrows are the other " +
                "option — one per reading, precise but twenty-five separate things to " +
                "read and join up.\n\n" +
                "The picture is for which way and roughly how hard; the reading beside " +
                "the timeline gives the actual speed and the direction the wind comes " +
                "from, which is how a forecast states it. The lines are drawn faster " +
                "than the real wind on purpose — at map scale a gale would take three " +
                "minutes to cross the screen — and they only animate while the map is " +
                "in front of you."
        }

        val tempSwitch = Switch(this).apply {
            text = "  Temperature"
            textSize = 16f
            isChecked = tempEnabled
            setOnCheckedChangeListener { _, on ->
                tempEnabled = on
                if (on) weatherShown = true
                result.text = if (on) "Temperature on — a figure at the top of the map, with the Weather chip."
                else "Temperature off."
            }
        }

        val cloudSwitch = Switch(this).apply {
            text = "  Cloud, sunshine and fog"
            textSize = 16f
            isChecked = cloudEnabled
            setOnCheckedChangeListener { _, on ->
                cloudEnabled = on
                if (on) weatherShown = true
                result.text = if (on) "Cloud on — grey where it is dull, its own colour for fog, " +
                    "and a clear map where the sky is clear."
                else "Cloud off."
            }
        }
        val washNote = TextView(this).apply {
            textSize = 13f
            text = "The weather draws as one picture: cloud under rain, wind over both, " +
                "temperature as a figure at the top of the map. Cloud leaves the map " +
                "alone below a quarter cover — a clean map means a clear sky — greys " +
                "in as it thickens, weighs low cloud far heavier than high (cirrus " +
                "barely dims a day; a lid of stratus wrecks one), and paints fog in " +
                "its own denser colour whatever the sky above it says, because fog is " +
                "the one condition that turns walking by sight into a compass leg.\n\n" +
                "Everything here comes from one forecast request, so switching a " +
                "second part on costs nothing.\n\n" +
                "These are a model, not a measurement — the forecast's opinion about the " +
                "sky, a few kilometres between readings. Only the radar frames are " +
                "observations, and the timeline label says which you are looking at.\n\n" +
                "Forecast by Open-Meteo (open-meteo.com)."
        }

        val tracesSwitch = Switch(this).apply {
            text = "  Where people have walked"
            textSize = 16f
            isChecked = tracesEnabled
            setOnCheckedChangeListener { _, on ->
                tracesEnabled = on
                if (on) tracesShown = true
                result.text = if (on) {
                    "Traces on — flashing red dots appear as you browse zoomed in."
                } else {
                    "Traces off."
                }
            }
        }
        val tracesNote = TextView(this).apply {
            textSize = 13f
            text = "Flashing red dots on the map wherever anyone has publicly recorded a GPS " +
                "track (OpenStreetMap's public traces; phone only, fetched as you " +
                "browse and cached). Dots mean the path really gets walked. One " +
                "honest caveat: they are cumulative, not recent — a dotted path was " +
                "walked at some point, not necessarily lately. No public source " +
                "answers recency; Strava's heatmap isn't licensable at any price."
        }

        val prowSwitch = Switch(this).apply {
            text = "  Rights of way"
            textSize = 16f
            isChecked = prowEnabled
            setOnCheckedChangeListener { _, on ->
                prowEnabled = on
                if (on) prowShown = true
                result.text = if (on) {
                    "Rights of way on — they draw in as you browse."
                } else {
                    "Rights of way off."
                }
            }
        }
        val prowNote = TextView(this).apply {
            textSize = 13f
            text = "The paths you are legally entitled to walk, drawn bold: green " +
                "footpaths, amber bridleways, purple restricted byways, brown byways " +
                "open to all traffic. Phone only, cached as you browse.\n\n" +
                "This comes from OpenStreetMap's record of what each council's " +
                "definitive map says — one source covering every council in England " +
                "and Wales, rather than a different feed per authority. It is a copy " +
                "of the legal record, not the record itself, and the OS map " +
                "underneath draws the same paths in green dashes: where the two " +
                "disagree, believe the printed map.\n\n" +
                "Where OpenStreetMap's volunteers haven't tagged your area, it draws " +
                "nothing — and that is what the button below fixes. It pulls what a " +
                "council actually released from its own definitive map (via rowmaps), " +
                "once, for the whole authority: better data than OSM where it exists, " +
                "and it then works with no signal anywhere in that county."
        }

        val allPathsSwitch = Switch(this).apply {
            text = "  Every mapped path and track"
            textSize = 15f
            isChecked = allPathsEnabled
            setOnCheckedChangeListener { _, on ->
                allPathsEnabled = on
                if (on) allPathsShown = true
                result.text = if (on) "All mapped paths on — thin grey lines, drawn under the coloured rights."
                else "All mapped paths off."
            }
        }
        val allPathsNote = TextView(this).apply {
            textSize = 13f
            text = "The physical network OpenStreetMap knows about — paths, tracks and " +
                "steps with no recorded legal status — in thin grey under the coloured " +
                "rights of way. It answers \"is there a path\", not \"may I walk it\"."
        }

        val prowOfficial = Button(this).apply {
            text = "Official council data…"
            setOnClickListener {
                result.text = "Asking rowmaps which councils have released data…"
                scope.launch {
                    val councils = withContext(Dispatchers.IO) {
                        runCatching { Prow.councils() }
                    }
                    val list = councils.getOrNull()
                    if (list.isNullOrEmpty()) {
                        result.text = "Couldn't read the council list: " +
                            (councils.exceptionOrNull()?.message ?: "nothing on the page") +
                            ". The OpenStreetMap version of the layer still works."
                        return@launch
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Whose definitive map?")
                        .setItems(list.map { "${it.name} (${it.code})" }.toTypedArray()) { _, i ->
                            val chosen = list[i]
                            result.text = "Downloading ${chosen.name}…"
                            scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Prow.downloadCouncil(this@SettingsActivity, chosen.code) { note ->
                                            runOnUiThread { result.text = note }
                                        }
                                    }.getOrElse {
                                        "Download failed: ${it.message ?: it.javaClass.simpleName}"
                                    }
                                }
                                result.text = outcome
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            addView(intro)
            addView(keyBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) })
            addView(row)
            addView(result, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) })

            addView(heading("Assistant"))
            addView(claudeSwitch)
            addView(claudeNote)
            addView(claudeKeyBlock, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            addView(heading("Map overlays"))
            addView(
                TextView(this@SettingsActivity).apply {
                    textSize = 13f
                    text = "Everything switched on here gets a toggle across the top of " +
                        "the map, and that toggle is what turns the layer on and off " +
                        "while you are out — one tap, without coming in here. The " +
                        "weather shares a single Weather chip (rain, wind, cloud and " +
                        "the temperature figure together — it is all one sky); the " +
                        "path layers keep a chip each. Switching something on here " +
                        "lights its chip too; off here takes it away entirely."
                    setPadding(0, dp(2), 0, dp(10))
                },
            )
            addView(prowSwitch)
            addView(prowNote)
            addView(allPathsSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
            addView(allPathsNote)
            addView(prowOfficial)
            addView(tracesSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) })
            addView(tracesNote)
            addView(radarSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) })
            addView(radarNote)
            addView(radarScaleNote)
            addView(HorizontalScrollView(this@SettingsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(choices(radarScales, { radarScheme }, sync = false) { radarScheme = it })
            })
            addView(windSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) })
            addView(windNote)
            addView(HorizontalScrollView(this@SettingsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(choices(windStyles, { windStyle }, sync = false) { windStyle = it })
            })
            addView(tempSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) })
            addView(cloudSwitch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
            addView(washNote)

            addView(heading("Route line"))
            addView(swatches({ routeColour }) { routeColour = it })
            addView(heading("Your arrow"))
            addView(swatches({ arrowColour }) { arrowColour = it })
            addView(heading("Recorded trail"))
            addView(swatches({ trailColour }) { trailColour = it })

            addView(heading("Watch"))
            addView(Switch(this@SettingsActivity).apply {
                text = "  Hold GPS on the watch between looks"
                textSize = 16f
                isChecked = watchGpsWarm
                setOnCheckedChangeListener { _, on ->
                    watchGpsWarm = on
                    // pushStyle narrates the send into the same result line,
                    // so the watch actually learning of the change is what
                    // gets reported, not just the phone remembering it.
                    pushStyle(result)
                }
            })
            addView(TextView(this@SettingsActivity).apply {
                textSize = 13f
                text = "Off, and worth leaving off. Recording a walk holds GPS anyway, so " +
                    "glances during a walk are already instant — this is only about the " +
                    "first look after the watch has slept, which otherwise costs about " +
                    "twenty seconds of grey arrow.\n\n" +
                    "With it on, a background hold keeps the fix for 90 minutes after you " +
                    "last look, and every glance and every touch pushes that out again — " +
                    "so in ordinary use it never expires and the watch holds GPS all day. " +
                    "That is the battery cost, stated plainly, and it is why this is not " +
                    "the default."
            })

            addView(heading("Waymark holds the watch screen on for"))
            addView(HorizontalScrollView(this@SettingsActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(choices(Prefs.SCREEN_TIMEOUTS, { screenTimeoutSec }) { screenTimeoutSec = it })
            })
            addView(TextView(this@SettingsActivity).apply {
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
                text = "An app can't switch a watch display off, only stop it sleeping. " +
                    "After this long, your watch's own screen timeout takes over — so the " +
                    "short settings mean \"let the watch behave normally\", which is also " +
                    "the kindest to its battery. For darker sooner, lower the watch's own " +
                    "Settings \u2192 Display \u2192 Screen timeout.\n\n" +
                    "Everything on this screen applies to the watch too — it has no settings " +
                    "of its own. A dark watch screen does not stop a recording; the trail " +
                    "keeps going either way."
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /**
     * One app on two devices, so the wrist gets the same settings — and says
     * whether it worked. Swallowing the failure silently under a line reading
     * "applies to the watch as well" is how a broken sync stays invisible.
     */
    private fun pushStyle(result: TextView) {
        result.text = "Sending to the watch…"
        scope.launch {
            try {
                Sync.sendStyle(this@SettingsActivity)
                result.text = "Sent to the watch."
            } catch (e: Exception) {
                result.text = "Saved on the phone — the watch will pick it up next time " +
                    "you open Waymark on it. (${e.javaClass.simpleName})"
            }
        }
    }

    /** One tiny real request — the only honest test of a paid key. */
    private suspend fun testAnthropicKey(key: String): String = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext "No Anthropic key entered."
        try {
            val client = AnthropicOkHttpClient.builder().apiKey(key).build()
            val response = client.messages().create(
                MessageCreateParams.builder()
                    .model(Model.of(Assistant.MODEL))
                    .maxTokens(16L)
                    .addUserMessage("Say OK and nothing else.")
                    .build(),
            )
            val text = response.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("").trim()
            if (text.isNotEmpty()) "Claude answered (\"$text\") — the assistant is live. ✓"
            else "The key works but the reply was empty — odd; try asking something on the map."
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            when {
                "401" in m || "authentication" in m.lowercase() -> "Key rejected — check it was copied whole."
                "credit" in m.lowercase() -> "Key is real but the account is out of credit."
                else -> "Couldn't reach Anthropic: $m"
            }
        }
    }

    private suspend fun testKey(key: String): String = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext "No key entered."
        // A z9 (1:25k Explorer) tile over Snowdonia — the zoom that separates
        // the plans.
        val en = Bng.fromWgs84(53.068, -4.076)
        val url = TileGrid.url(TileGrid.MAX_Z, TileGrid.tileX(en.e, TileGrid.MAX_Z), TileGrid.tileY(en.n, TileGrid.MAX_Z), key)
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                when (val code = conn.responseCode) {
                    200 -> "Key works — Explorer 1:25k detail is available. ✓"
                    401 -> "The key was rejected outright (HTTP 401) — check it was copied whole."
                    403 -> "The key is real but refused at Explorer detail (HTTP 403) — " +
                        "your OS Data Hub project is probably on the free plan. " +
                        "Switch it to Premium (pay-as-you-go) at osdatahub.os.uk."
                    429 -> "The key works but is being rate-limited (HTTP 429) — try again shortly."
                    else -> "Unexpected answer from OS (HTTP $code)."
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            "Couldn't reach the OS servers: ${e.message ?: "no connection"}"
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
