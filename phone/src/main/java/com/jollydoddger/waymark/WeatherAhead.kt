package com.jollydoddger.waymark

import kotlin.math.roundToInt

/**
 * "Is it going to rain, and when will it clear?" — answered from an hourly
 * forecast, in a sentence, with no model in the loop.
 *
 * He asked for "a way for the assistant or something to tell me if it's
 * going to rain, or when it will be clear again, or when it will be sunny
 * throughout the walk". The *or something* is this: the question is the
 * same every time and the answer is a handful of comparisons over a list
 * of hours, so it is arithmetic, not a paid call and a wait. The assistant
 * gets the same sentence from the same code, so the two never disagree.
 *
 * Deliberately pure — hours in, words out — because the thing that can go
 * wrong here is the words being wrong about the numbers, and that is what
 * a unit test catches and a wet afternoon does not.
 */
object WeatherAhead {

    /** One forecast hour at one place, covering [timeMs, timeMs + 1 h).
     *  NaN or -1 where the source has no figure: the grid over the map
     *  carries no rain probability or gusts, the point forecast does. */
    class Hour(
        val timeMs: Long,
        val rainMm: Double,
        val rainProb: Int = -1,
        val cloudPct: Double = Double.NaN,
        val tempC: Double = Double.NaN,
        val windMph: Double = Double.NaN,
        val gustMph: Double = Double.NaN,
    )

    /** Rain you would notice in an hour. Under this is spitting. */
    const val WET_MM = 0.2

    /** A probability that, with a little rain in the figure, counts as wet. */
    const val LIKELY_PROB = 60

    /** Cloud cover under which an hour counts as sunny. */
    const val SUNNY_CLOUD = 40.0

    /** Gusts worth a word on a ridge. */
    const val GUSTY_MPH = 35.0

    const val HOUR_MS = 3_600_000L

    fun isWet(h: Hour): Boolean =
        h.rainMm >= WET_MM || (h.rainProb >= LIKELY_PROB && h.rainMm >= 0.1)

    fun isSunny(h: Hour): Boolean =
        !isWet(h) && !h.cloudPct.isNaN() && h.cloudPct < SUNNY_CLOUD

    /**
     * The hours from the one now belongs to, out to the horizon. Sorted,
     * and never starting in the past by more than an hour: an old hour's
     * rain is not rain ahead.
     */
    fun ahead(hours: List<Hour>, nowMs: Long, horizonH: Int): List<Hour> {
        val sorted = hours.filter { !it.rainMm.isNaN() }.sortedBy { it.timeMs }
        val end = nowMs + horizonH * HOUR_MS
        return sorted.filter { it.timeMs + HOUR_MS > nowMs && it.timeMs < end }
    }

    enum class Kind { RAIN_SOON, RAINING_CLEARS, RAINING_ON, DRY, SUN_SOON, CLOUDING_OVER }

    /**
     * One thing worth interrupting a walk for. [key] names the moment it is
     * about, so the same onset is said once however many times the forecast
     * is re-read — and a *different* onset, or the same one an hour later
     * after the model moved it, is news again.
     */
    data class Headline(val kind: Kind, val key: String, val title: String, val text: String)

    /** Clock text for a moment. Injected so the tests can pin a zone. */
    var clock: (Long) -> String = { ms ->
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(ms))
    }

    private fun span(h: Int): String = if (h == 1) "about an hour" else "about $h h"

    /** Runs of consecutive wet or dry hours, in order. */
    private class Run(val wet: Boolean, val hours: List<Hour>) {
        val start get() = hours.first().timeMs
        val end get() = hours.last().timeMs + HOUR_MS
    }

    private fun runs(hours: List<Hour>): List<Run> {
        val out = ArrayList<Run>()
        var cur = ArrayList<Hour>()
        var wet = false
        for (h in hours) {
            val w = isWet(h)
            if (cur.isNotEmpty() && w != wet) {
                out.add(Run(wet, cur))
                cur = ArrayList()
            }
            wet = w
            cur.add(h)
        }
        if (cur.isNotEmpty()) out.add(Run(wet, cur))
        return out
    }

    private fun heaviest(run: Run): Hour = run.hours.maxByOrNull { it.rainMm }!!

    private fun mm(v: Double): String =
        if (v >= 1.0) "${v.roundToInt()} mm/h" else "%.1f mm/h".format(java.util.Locale.UK, v)

    /**
     * The sentence: what it is doing now, the next change, the sun, the
     * wind if it matters, the temperature range. Nothing it cannot know is
     * said — a grid with no cloud figure gets no sun clause.
     */
    fun describe(hours: List<Hour>, nowMs: Long, horizonH: Int = 8): String {
        val a = ahead(hours, nowMs, horizonH)
        if (a.isEmpty()) return "No forecast for the hours ahead."
        val rs = runs(a)
        val parts = ArrayList<String>()
        val first = rs[0]
        if (first.wet) {
            val heavy = heaviest(first)
            val next = rs.getOrNull(1)
            parts.add(
                if (next != null) {
                    "Raining now (${mm(heavy.rainMm)}), clearing by ${clock(next.start)}" +
                        (rs.getOrNull(2)?.let { ", more rain from ${clock(it.start)}" } ?: "") + "."
                } else {
                    "Raining now (${mm(heavy.rainMm)}) and set in for the next " +
                        "${span(first.hours.size)} at least."
                },
            )
        } else {
            val next = rs.getOrNull(1)
            if (next == null) {
                parts.add("Dry for the next ${span(first.hours.size)}.")
            } else {
                val heavy = heaviest(next)
                val clears = rs.getOrNull(2)
                parts.add(
                    "Dry until ${clock(next.start)}, then rain for ${span(next.hours.size)}" +
                        " (heaviest ${mm(heavy.rainMm)} around ${clock(heavy.timeMs)})" +
                        (if (clears != null) ", clearing by ${clock(clears.start)}." else " and beyond."),
                )
            }
        }
        // The sun, only where cloud is known.
        if (a.any { !it.cloudPct.isNaN() }) {
            val sunnyNow = isSunny(a[0])
            val firstSun = a.firstOrNull { isSunny(it) }
            when {
                sunnyNow -> {
                    val ends = a.firstOrNull { it.timeMs > a[0].timeMs && !isSunny(it) }
                    parts.add(
                        if (ends != null) "Sunny now, clouding over around ${clock(ends.timeMs)}."
                        else "Sunny throughout.",
                    )
                }
                firstSun != null -> {
                    val ends = a.firstOrNull { it.timeMs > firstSun.timeMs && !isSunny(it) }
                    parts.add(
                        "Sun from ${clock(firstSun.timeMs)}" +
                            (if (ends != null) " until about ${clock(ends.timeMs)}." else " onwards."),
                    )
                }
                else -> parts.add("No sun expected in the next ${span(a.size)}.")
            }
        }
        val gust = a.filter { !it.gustMph.isNaN() }.maxByOrNull { it.gustMph }
        if (gust != null && gust.gustMph >= GUSTY_MPH) {
            parts.add("Gusts to ${gust.gustMph.roundToInt()} mph around ${clock(gust.timeMs)}.")
        }
        val temps = a.map { it.tempC }.filter { !it.isNaN() }
        if (temps.isNotEmpty()) {
            val lo = temps.min().roundToInt()
            val hi = temps.max().roundToInt()
            parts.add(if (lo == hi) "$lo°C." else "$lo–$hi°C.")
        }
        return parts.joinToString(" ")
    }

    /**
     * What is worth a buzz on the wrist, most pressing first: the rain
     * state always, the sun when it changes. Each carries a key naming the
     * moment it is about, so a re-read of the same forecast says nothing
     * new. Empty when there is nothing to say.
     */
    fun headlines(hours: List<Hour>, nowMs: Long, horizonH: Int = 8): List<Headline> {
        val a = ahead(hours, nowMs, horizonH)
        if (a.isEmpty()) return emptyList()
        val rs = runs(a)
        val out = ArrayList<Headline>()
        val first = rs[0]
        val next = rs.getOrNull(1)
        if (first.wet) {
            if (next != null) {
                out.add(
                    Headline(
                        Kind.RAINING_CLEARS, "clear@${next.start}",
                        "Clearing by ${clock(next.start)}",
                        "The rain should stop around ${clock(next.start)}" +
                            (rs.getOrNull(2)?.let { "; more from ${clock(it.start)}" } ?: "") + ".",
                    ),
                )
            } else {
                out.add(
                    Headline(
                        Kind.RAINING_ON, "on@${first.start}",
                        "Rain set in",
                        "No break in the rain for the next ${span(first.hours.size)}.",
                    ),
                )
            }
        } else if (next != null) {
            val heavy = heaviest(next)
            val clears = rs.getOrNull(2)
            out.add(
                Headline(
                    Kind.RAIN_SOON, "rain@${next.start}",
                    "Rain from ${clock(next.start)}",
                    "Dry until then, then ${span(next.hours.size)} of it, heaviest " +
                        "${mm(heavy.rainMm)} around ${clock(heavy.timeMs)}" +
                        (if (clears != null) ", clearing by ${clock(clears.start)}." else "."),
                ),
            )
        } else {
            out.add(
                Headline(
                    Kind.DRY, "dry",
                    "Dry for the next ${span(first.hours.size)}",
                    "No rain in the forecast for the hours ahead.",
                ),
            )
        }
        if (a.any { !it.cloudPct.isNaN() }) {
            val sunnyNow = isSunny(a[0])
            if (sunnyNow) {
                val ends = a.firstOrNull { it.timeMs > a[0].timeMs && !isSunny(it) }
                if (ends != null) {
                    out.add(
                        Headline(
                            Kind.CLOUDING_OVER, "cloud@${ends.timeMs}",
                            "Clouding over around ${clock(ends.timeMs)}",
                            "Sunny until then.",
                        ),
                    )
                }
            } else {
                val sun = a.firstOrNull { isSunny(it) }
                if (sun != null) {
                    out.add(
                        Headline(
                            Kind.SUN_SOON, "sun@${sun.timeMs}",
                            "Sun from ${clock(sun.timeMs)}",
                            "Clearer skies from about ${clock(sun.timeMs)}.",
                        ),
                    )
                }
            }
        }
        return out
    }
}
