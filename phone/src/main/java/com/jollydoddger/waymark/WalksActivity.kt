package com.jollydoddger.waymark

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.RouteStore

/**
 * Every walk in play, on a screen big enough to read.
 *
 * The picker bar it replaces was his complaint word for word: "clanky and
 * small and not easy to see", and a strip two lines tall at the bottom of a
 * map is exactly that — a carousel you flick through blind, showing one
 * candidate at a time with its name truncated.
 *
 * A list is the right shape for choosing. Everything is on it at once, each
 * with its length and how far off it starts, and picking one is a tap
 * rather than a hunt. The map's ‹ › bar stays for previewing on the map
 * itself, which is the one thing a list genuinely cannot do.
 */
class WalksActivity : Activity() {

    companion object {
        /** Set when the chosen walk should be loaded onto the map. */
        const val RESULT_TAKE = "take"
        const val RESULT_EDIT = "edit"
    }

    private lateinit var body: LinearLayout

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(28))
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.rgb(18, 21, 19))
                addView(body)
                setOnApplyWindowInsetsListener { v, insets ->
                    v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                    insets
                }
            },
        )
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(text: String, big: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (big) 21f else 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setBackgroundColor(if (big) Color.TRANSPARENT else Color.argb(255, 28, 54, 40))
        setPadding(dp(18), dp(if (big) 18 else 10), dp(18), dp(if (big) 4 else 10))
    }

    private fun quiet(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.argb(205, 168, 180, 170))
        setPadding(dp(18), dp(2), dp(18), dp(12))
    }

    /**
     * One walk. Big enough to hit without looking, with the two numbers
     * that decide it — how long, and how far away it starts — on the row
     * rather than hidden behind a tap.
     */
    private fun walkRow(
        name: String,
        detail: String,
        source: String,
        onTap: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(13), dp(18), dp(13))
        isClickable = true
        setBackgroundColor(Color.argb(255, 26, 30, 27))
        addView(
            TextView(this@WalksActivity).apply {
                text = name
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        addView(
            TextView(this@WalksActivity).apply {
                text = if (source.isBlank()) detail else "$detail · $source"
                textSize = 13f
                setTextColor(Color.argb(220, 175, 190, 178))
                setPadding(0, dp(3), 0, 0)
            },
        )
        setOnClickListener { onTap() }
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.argb(255, 18, 21, 19))
    }

    private fun build() {
        body.removeAllViews()
        addHeader()

        val current = RouteStore.load(this)
        addView(heading("ON THE MAP"))
        if (current == null || current.points.size < 2) {
            addView(quiet("No route loaded."))
        } else {
            addView(
                walkRow(
                    current.name.ifBlank { "Unnamed route" },
                    Brief.fmtKm(Geom.length(current.points)),
                    "loaded",
                ) { finishWith(RESULT_EDIT, current.name) },
            )
            addView(quiet("Tap it to edit its shape on the map."))
        }

        // The picker's batch, read whole — including one he has closed,
        // because this screen IS the way back to it.
        val pending = WalkPicks.pending(this)
        addView(heading("WAITING TO BE CHOSEN"))
        if (pending.isEmpty()) {
            addView(
                quiet(
                    "Nothing waiting. “Plan a walk” finds some for a length and a " +
                        "day; “Walks on this map” fills this from everything " +
                        "crossing the map in view.",
                ),
            )
        } else {
            for (w in pending) {
                val here = intent.getDoubleExtra("e", Double.NaN)
                val n = intent.getDoubleExtra("n", Double.NaN)
                val away = if (!here.isNaN() && !n.isNaN() && w.closestM > 0) {
                    " · starts ${Brief.fmtKm(w.closestM)} away"
                } else {
                    ""
                }
                addView(
                    walkRow(
                        w.name,
                        Brief.fmtKm(w.lengthM) + " · " + Specifier.describe(Specifier.formOf(w)) + away,
                        w.source,
                    ) { finishWith(RESULT_TAKE, w.name) },
                )
                addView(spacer())
            }
            addView(quiet("Tap one to put it on the map."))
        }

        val saved = runCatching { Walks.list(this) }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) {
            addView(heading("WALKS YOU HAVE DONE"))
            for (w in saved.take(20)) {
                addView(
                    walkRow(
                        w.name,
                        Brief.fmtKm(w.distanceM) + " · " + Walks.duration(w),
                        w.place,
                    ) { finishWith(RESULT_TAKE, w.name) },
                )
                addView(spacer())
            }
        }
    }

    private fun addHeader() {
        addView(heading("Walks", big = true))
        addView(
            quiet(
                "Everything in play: what is on the map, what is waiting to be chosen, " +
                    "and what you have already walked.",
            ),
        )
    }

    private fun addView(v: View) = body.addView(v)

    private fun finishWith(what: String, name: String) {
        setResult(RESULT_OK, Intent().putExtra(what, name))
        finish()
    }
}
