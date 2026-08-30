package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Sun
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The whole brief for one walk on one day: how far, how much climb, how
 * long at his pace, when to set off, what the weather does to that, and
 * whether he is home before dark.
 *
 * Deliberately **not** the assistant's. The assistant already has
 * `walk_brief`, and it is the wrong tool for the job the moment the answer
 * is wanted every time: it costs a paid call, it needs a key, and it needs
 * signal of a kind a hillside does not reliably have. Everything here is
 * either arithmetic or one small Open-Meteo request, and the light — the
 * part that decides whether a walk is safe — is computed on the phone by
 * [Sun] and works with no signal at all.
 *
 * The scoring is separated from the fetching and the wording so it can be
 * tested at a desk. A brief that recommends setting off into the one wet
 * hour of the day is worse than no brief, and it is not a failure anybody
 * would notice until they were out in it.
 */
object Brief {

    /** One hour of forecast, in the units a walker thinks in. */
    data class Hour(
        val timeMs: Long,
        val tempC: Double,
        val rainMm: Double,
        val rainProb: Int,
        val windMph: Double,
        val gustMph: Double,
        val windDeg: Double,
    )

    /** What setting off at a given moment would actually be like. */
    data class Window(
        val departMs: Long,
        val minutes: Double,
        val rainMm: Double,
        val rainProb: Int,
        val tempLo: Double,
        val tempHi: Double,
        val windMph: Double,
        val gustMph: Double,
        val windDeg: Double,
        val score: Double,
    ) {
        val finishMs: Long get() = departMs + (minutes * 60_000).toLong()
    }

    // --- what makes one hour better to walk in than another ----------------
    //
    // The weights are stated here rather than buried, because they are a
    // judgement and he is entitled to disagree with them. Rain that actually
    // falls dominates: a millimetre across a walk is worth more than any
    // amount of forecast *chance*, because a 60% chance of nothing is a dry
    // walk and 1 mm is a wet one. Wind only starts to count when it is
    // strong enough to lean on. Cold counts more than warm, because this is
    // Anglesey in the wind and off the top of a hill.

    private const val PER_MM_RAIN = 12.0
    private const val PER_PERCENT_CHANCE = 0.06
    private const val GUST_FREE_MPH = 25.0
    private const val PER_MPH_GUST = 0.5
    private const val COLD_BELOW_C = 4.0
    private const val PER_DEGREE_COLD = 0.6
    private const val HOT_ABOVE_C = 20.0
    private const val PER_DEGREE_HOT = 0.4

    fun score(rainMm: Double, rainProb: Int, gustMph: Double, tempLo: Double, tempHi: Double): Double {
        var s = rainMm * PER_MM_RAIN + rainProb * PER_PERCENT_CHANCE
        s += maxOf(0.0, gustMph - GUST_FREE_MPH) * PER_MPH_GUST
        if (tempLo < COLD_BELOW_C) s += (COLD_BELOW_C - tempLo) * PER_DEGREE_COLD
        if (tempHi > HOT_ABOVE_C) s += (tempHi - HOT_ABOVE_C) * PER_DEGREE_HOT
        return s
    }

    /**
     * The walk as it would be, setting off at [departMs]. Rain is counted by
     * the *part* of each hour actually walked in — half an hour of a wet
     * hour is half its rain, and rounding that up turned a brisk hour's walk
     * into a downpour on paper.
     *
     * Null when the forecast does not reach the window at all; an invented
     * fair day is the one answer this must never give.
     */
    fun windowOver(hours: List<Hour>, departMs: Long, minutes: Double): Window? {
        val finish = departMs + (minutes * 60_000).toLong()
        var rain = 0.0
        var prob = 0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var wind = 0.0
        var gust = 0.0
        var dir = Double.NaN
        var covered = 0.0
        for (h in hours.sortedBy { it.timeMs }) {
            val from = maxOf(h.timeMs, departMs)
            val to = minOf(h.timeMs + 3_600_000L, finish)
            if (to <= from) continue
            val fraction = (to - from) / 3_600_000.0
            covered += fraction
            rain += h.rainMm * fraction
            prob = maxOf(prob, h.rainProb)
            lo = minOf(lo, h.tempC)
            hi = maxOf(hi, h.tempC)
            wind = maxOf(wind, h.windMph)
            gust = maxOf(gust, h.gustMph)
            // The direction at the start, never an average: averaging 350
            // with 10 gives due south, the exact opposite of the truth.
            if (dir.isNaN()) dir = h.windDeg
        }
        // Most of the walk has to be forecast for the answer to mean
        // anything. A window hanging off the end of the data is not a
        // quiet day, it is no data.
        if (covered < minutes / 60.0 * 0.6) return null
        return Window(
            departMs, minutes, rain, prob, lo, hi, wind, gust,
            if (dir.isNaN()) 0.0 else dir,
            score(rain, prob, gust, lo, hi),
        )
    }

    /** Every sensible departure between [earliestMs] and [latestMs], best
     *  first. Ties break earlier, because an earlier finish is more daylight
     *  in hand and daylight in hand is never worth nothing. */
    fun departures(
        hours: List<Hour>,
        earliestMs: Long,
        latestMs: Long,
        minutes: Double,
        stepMs: Long = 30 * 60_000L,
    ): List<Window> {
        if (latestMs < earliestMs) return emptyList()
        val out = ArrayList<Window>()
        var t = earliestMs
        while (t <= latestMs) {
            windowOver(hours, t, minutes)?.let { out.add(it) }
            t += stepMs
        }
        return out.sortedWith(compareBy({ it.score }, { it.departMs }))
    }

    // --- the fetching and the wording ---------------------------------------

    private const val MAX_ELEVATION_SAMPLES = 90

    private val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

    private fun at(ms: Long) = hhmm.format(java.util.Date(ms))

    /** Midnight local of the day [offset] days from now. */
    fun dayStartLocal(offset: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.DAY_OF_YEAR, offset)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * The brief. Network, so call it off the main thread; every part of it
     * degrades on its own rather than the whole thing failing — no signal
     * still gives distance, climb, time and daylight, and says which part is
     * missing instead of leaving a gap that reads as "fine".
     */
    fun compose(
        walk: RouteFinder.FoundWalk,
        dayOffset: Int,
        paceMinPerKm: Double,
        paceLabel: String,
        here: En?,
    ): String {
        val points = walk.routePoints()
        if (points.size < 2) return "That walk has no line to brief."
        val totalM = Geom.length(points)
        val form = Specifier.formOf(points)
        val (lat, lon) = Bng.toWgs84(points.first())

        val climb = runCatching { climbOf(points) }.getOrNull()
        val ascent = climb?.first ?: 0.0
        val minutes = Eta.minutes(totalM, ascent, paceMinPerKm)

        val sb = StringBuilder()
        sb.append("${walk.name}\n")
        sb.append("${fmtKm(totalM)} · ${Specifier.describe(form)} · ${walk.source}\n\n")

        sb.append(
            if (climb != null) {
                "Climb ${ascent.roundToInt()} m up, ${climb.second.roundToInt()} m down. "
            } else {
                "No signal for the terrain, so the time below is flat-ground only. "
            },
        )
        sb.append("About ${fmtMins(minutes)} at ${paceLabel}")
        sb.append(if (climb != null) ", climb included.\n\n" else ".\n\n")

        // --- light, computed on the phone: this part works with no signal ---
        val noon = dayStartLocal(dayOffset) + 12 * 3_600_000L
        val sunrise = Sun.sunrise(noon, lat, lon)
        val sunset = Sun.sunset(noon, lat, lon)
        val dusk = Sun.civilDusk(noon, lat, lon)

        val now = System.currentTimeMillis()
        val dayStart = dayStartLocal(dayOffset)
        val dayEnd = dayStart + 24 * 3_600_000L
        // Half an hour's grace to get out of the door, and never a departure
        // in the past.
        val earliest = maxOf(sunrise ?: dayStart, if (dayOffset == 0) roundUp(now + 20 * 60_000L) else dayStart)
        val latest = minOf(
            (sunset ?: (dayEnd - 4 * 3_600_000L)) - (minutes * 60_000).toLong(),
            dayEnd - 3_600_000L,
        )

        val hours = runCatching { forecast(lat, lon, dayOffset) }.getOrNull()
        val best = hours?.let { departures(it, earliest, latest, minutes) } ?: emptyList()

        when {
            hours == null ->
                sb.append("Couldn't reach the forecast, so there is no best-time advice here — " +
                    "everything above and the daylight below is computed on the phone and stands.\n\n")
            latest < earliest -> {
                sb.append("It doesn't fit in the daylight ${WalkSpec.dayName(dayOffset)}: ")
                sb.append("light from ${at(earliest)} to ${sunset?.let { at(it) } ?: "dusk"} is less ")
                sb.append("than the ${fmtMins(minutes)} this takes. Set off at first light and take a ")
                sb.append("head torch, or save it for a longer day.\n\n")
            }
            best.isEmpty() ->
                sb.append("The forecast doesn't reach ${WalkSpec.dayName(dayOffset)} in enough detail " +
                    "to pick an hour. Daylight is below.\n\n")
            else -> {
                val w = best.first()
                sb.append("SET OFF ${at(w.departMs)} — back about ${at(w.finishMs)}.\n")
                sb.append(describeWeather(w))
                val worse = best.maxByOrNull { it.score }
                if (worse != null && worse.score > w.score + 3.0) {
                    sb.append("Worst of the day would be ${at(worse.departMs)}: ")
                    sb.append(rainPhrase(worse).replaceFirstChar { it.lowercase() })
                    sb.append("\n")
                }
                val alt = best.drop(1).firstOrNull { kotlin.math.abs(it.departMs - w.departMs) > 90 * 60_000L }
                if (alt != null && alt.score < w.score + 2.0) {
                    sb.append("Nearly as good: ${at(alt.departMs)}.\n")
                }
                sb.append("\n")
            }
        }

        sunset?.let {
            sb.append("Sunset ${at(it)}")
            dusk?.let { d -> sb.append(", useful light gone by ${at(d)}") }
            if (best.isNotEmpty()) {
                val spare = (it - best.first().finishMs) / 60_000.0
                sb.append(
                    when {
                        spare < 0 -> " — that finish is after sunset."
                        spare < 45 -> " — only ${spare.roundToInt()} min in hand. Take a torch."
                        else -> " — ${fmtMins(spare)} in hand."
                    },
                )
            } else {
                sb.append(".")
            }
            sb.append("\n")
        }

        // --- worth knowing ---------------------------------------------------
        val notes = ArrayList<String>()
        if (paceLabel == "a book pace") {
            notes.add("The time is a book pace — Waymark hasn't recorded enough of your walks " +
                "to know yours yet, so treat it as a stranger's estimate.")
        }
        if (climb != null && totalM > 0 && ascent / (totalM / 1000.0) > 60) {
            notes.add("Steep for its length: ${(ascent / (totalM / 1000.0)).roundToInt()} m of climb " +
                "per kilometre.")
        }
        if (form == Form.LINEAR) {
            val apart = hypot(
                points.last().e - points.first().e,
                points.last().n - points.first().n,
            )
            notes.add(
                "This one doesn't come back — it finishes ${fmtKm(apart)} from where it " +
                    "starts, so you'll need a way back to the car.",
            )
        }
        if (form == Form.OUT_AND_BACK) {
            notes.add("Out and back, so the turning point is the halfway bell: at ${fmtMins(minutes / 2)} " +
                "you should be at the far end.")
        }
        here?.let {
            val toStart = hypot(points.first().e - it.e, points.first().n - it.n)
            if (toStart > 1_500) {
                notes.add("The start is ${fmtKm(toStart)} away — Parking on the picker will drive you there, " +
                    "and that drive is on top of everything above.")
            }
        }
        if (best.isNotEmpty() && best.first().gustMph >= 35) {
            notes.add("Gusting ${best.first().gustMph.roundToInt()} mph even at the best hour — " +
                "exposed ground and cliff paths will feel it.")
        }
        if (dayOffset >= 4) {
            notes.add("${WalkSpec.dayName(dayOffset).replaceFirstChar { it.uppercase() }} is far enough " +
                "out that the weather half of this will move. Worth asking again the day before.")
        }
        if (notes.isNotEmpty()) {
            sb.append("\nWorth knowing\n")
            notes.forEach { sb.append("• $it\n") }
        }
        return sb.toString().trimEnd()
    }

    private fun describeWeather(w: Window): String {
        val sb = StringBuilder()
        sb.append(rainPhrase(w))
        sb.append("${w.tempLo.roundToInt()}–${w.tempHi.roundToInt()}°C, ")
        sb.append("wind ${w.windMph.roundToInt()} mph from the ${Sun.compass(w.windDeg)}")
        if (w.gustMph > w.windMph + 5) sb.append(", gusting ${w.gustMph.roundToInt()}")
        sb.append(".\n")
        return sb.toString()
    }

    private fun rainPhrase(w: Window): String = when {
        w.rainMm >= 2.0 -> "Wet — about %.1f mm falling across the walk. ".format(w.rainMm)
        w.rainMm >= 0.3 -> "A bit of rain, about %.1f mm. ".format(w.rainMm)
        w.rainProb >= 40 -> "Dry on the numbers but a ${w.rainProb}% chance of a shower. "
        else -> "Dry. "
    }

    /** Up and down along the line, from the terrain model. */
    private fun climbOf(points: List<En>): Pair<Double, Double> {
        val total = Geom.length(points)
        val step = maxOf(150.0, total / (MAX_ELEVATION_SAMPLES - 1))
        val samples = sampleAlong(points, step)
        val lats = StringBuilder()
        val lons = StringBuilder()
        samples.forEachIndexed { i, en ->
            val (la, lo) = Bng.toWgs84(en)
            if (i > 0) { lats.append(','); lons.append(',') }
            lats.append("%.5f".format(java.util.Locale.UK, la))
            lons.append("%.5f".format(java.util.Locale.UK, lo))
        }
        val elev = JSONObject(
            Net.get("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"),
        ).getJSONArray("elevation")
        var up = 0.0
        var down = 0.0
        var prev = elev.getDouble(0)
        for (i in 1 until elev.length()) {
            val h = elev.getDouble(i)
            if (h > prev) up += h - prev else down += prev - h
            prev = h
        }
        return up to down
    }

    private fun sampleAlong(pts: List<En>, stepM: Double): List<En> {
        val out = ArrayList<En>()
        out.add(pts.first())
        var next = stepM
        var along = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = hypot(b.e - a.e, b.n - a.n)
            while (seg > 0 && next <= along + seg) {
                val t = (next - along) / seg
                out.add(En(a.e + t * (b.e - a.e), a.n + t * (b.n - a.n)))
                next += stepM
            }
            along += seg
        }
        if (out.size < 2) out.add(pts.last())
        return out
    }

    /** The hourly forecast for the chosen day, in walker's units. */
    private fun forecast(lat: Double, lon: Double, dayOffset: Int): List<Hour> {
        val days = (dayOffset + 2).coerceIn(2, 8)
        val json = JSONObject(
            Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    .format(java.util.Locale.UK, lat, lon) +
                    "&hourly=temperature_2m,precipitation_probability,precipitation," +
                    "wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                    "&forecast_days=$days&wind_speed_unit=mph&timezone=auto",
            ),
        )
        val h = json.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val temp = h.getJSONArray("temperature_2m")
        val prob = h.optJSONArray("precipitation_probability")
        val rain = h.getJSONArray("precipitation")
        val wind = h.getJSONArray("wind_speed_10m")
        val dir = h.optJSONArray("wind_direction_10m")
        val gust = h.optJSONArray("wind_gusts_10m")
        // timezone=auto means these come back as local wall-clock with no
        // offset, which is exactly what the device's own parser assumes.
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.UK)
        val out = ArrayList<Hour>(times.length())
        for (i in 0 until times.length()) {
            val ms = iso.parse(times.getString(i))?.time ?: continue
            out.add(
                Hour(
                    timeMs = ms,
                    tempC = temp.optDouble(i, Double.NaN).let { if (it.isNaN()) 10.0 else it },
                    rainMm = rain.optDouble(i, 0.0),
                    rainProb = prob?.optInt(i) ?: 0,
                    windMph = wind.optDouble(i, 0.0),
                    gustMph = gust?.optDouble(i, 0.0) ?: 0.0,
                    windDeg = dir?.optDouble(i, 0.0) ?: 0.0,
                ),
            )
        }
        return out
    }

    private fun roundUp(ms: Long): Long {
        val half = 30 * 60_000L
        return (ms + half - 1) / half * half
    }

    fun fmtKm(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else "%.1f km".format(m / 1000)

    fun fmtMins(mins: Double): String {
        val m = mins.roundToInt()
        return if (m >= 90) "${m / 60} h ${m % 60} min" else "$m min"
    }
}
