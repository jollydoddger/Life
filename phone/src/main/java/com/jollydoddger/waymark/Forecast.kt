package com.jollydoddger.waymark

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.En
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * The forecast half of the weather timeline: where the radar's reach ends,
 * the hourly model takes over. Open-Meteo's forecast grid, sampled at a
 * lattice of points over the viewport, drawn as a blue wash at the model's
 * own coarse resolution — deliberately nothing like the radar's fine
 * texture, because a model hour dressed up as a measurement would be the
 * layer lying about what it knows. Wind arrives in the same call and goes on
 * as arrows, since "is it behind me on the climb" is half of what a walker
 * wants from a forecast.
 *
 * Unlike the radar this *is* briefly cached: a forecast is an opinion about
 * the future, not a picture of now, and Open-Meteo only re-runs hourly —
 * refetching on every pan would hammer a free service to learn nothing new.
 */
object Forecast {

    /** The sample lattice over the viewport. 6×6 keeps one fetch to 36
     *  points — cell spacing lands near the model's own ~2 km over a
     *  walk-sized viewport, and a coarse broad-brush wash at county scale. */
    const val COLS = 6
    const val ROWS = 6

    /** A timeline moment is matched to its nearest model hour, or to none:
     *  past this gap the model genuinely has nothing to say about it. */
    const val HOUR_SLOP_MS = 45 * 60_000L

    /** The paint alpha the field is drawn with; how heavy each cell looks
     *  is in the pixels themselves, from [colourFor]. */
    const val FIELD_ALPHA = 235

    /** Open-Meteo updates hourly; half that keeps the wash honest. */
    private const val TTL_MS = 30 * 60_000L

    /** Fetched this much beyond the viewport, so a small pan re-uses the
     *  field already in hand instead of costing another call. */
    private const val PAD = 0.25

    /** A failed fetch with nothing in hand is worth words — once in a
     *  while, not on every settle of a map being idly panned. */
    private const val NOTE_GAP_MS = 5 * 60_000L

    /**
     * One fetched field: an hour-by-lattice grid of the model's rain and
     * wind. Cells run west→east within a row, rows north→south — the same
     * order as the bitmap pixels they become. NaN means the model returned
     * nothing for that cell, which is drawn as nothing and said as nothing.
     */
    class Field(
        val west: Double, val south: Double, val east: Double, val north: Double,
        val timesMs: LongArray,
        /** [hour][row * COLS + col] — mm of rain falling in that hour. */
        val rain: Array<FloatArray>,
        val probPct: Array<FloatArray>,
        val windMph: Array<FloatArray>,
        val windFromDeg: Array<FloatArray>,
        val fetchedAtMs: Long,
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "forecast").apply { isDaemon = true }
    }

    // Lazy on purpose: the pure arithmetic below is held to JVM unit tests,
    // and a Handler built at object-init would drag Android's main looper
    // into a test that never goes near it.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private var field: Field? = null
    private var lastNoteAt = 0L

    /**
     * The field for a viewport, fetched if what is in hand is stale or
     * elsewhere. While a fresh fetch runs, a stale field that still covers
     * the view is delivered first — a half-hour-old forecast beats a blank
     * timeline, and the update replaces it the moment it lands.
     */
    fun refresh(boundsEn: DoubleArray, onNote: (String) -> Unit, onField: (Field) -> Unit) {
        executor.execute {
            val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
            val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
            val have = synchronized(this) { field }
            val covered = have != null &&
                have.west <= west && have.east >= east &&
                have.south <= south && have.north >= north
            if (have != null && covered) {
                main.post { onField(have) }
                if (System.currentTimeMillis() - have.fetchedAtMs < TTL_MS) return@execute
            }
            try {
                val padLat = (north - south) * PAD
                val padLon = (east - west) * PAD
                val f = fetch(west - padLon, south - padLat, east + padLon, north + padLat)
                synchronized(this) { field = f }
                main.post { onField(f) }
            } catch (e: Exception) {
                // With a covering field still in hand the wash carries on
                // from it; with nothing, the silence needs explaining or the
                // timeline's future half just never appears.
                val speak = !covered && synchronized(this) {
                    val now = System.currentTimeMillis()
                    (now - lastNoteAt > NOTE_GAP_MS).also { if (it) lastNoteAt = now }
                }
                if (speak) {
                    main.post {
                        onNote("Rain forecast: couldn't reach Open-Meteo (${e.message ?: "no connection"}).")
                    }
                }
            }
        }
    }

    /** One call for the whole lattice and every hour — 36 points, six hours
     *  back to seven ahead, which the timeline then trims to its own span. */
    private fun fetch(west: Double, south: Double, east: Double, north: Double): Field {
        val lats = StringBuilder()
        val lons = StringBuilder()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
            lats.append(String.format(Locale.US, "%.3f", latOfRow(south, north, r)))
            lons.append(String.format(Locale.US, "%.3f", lonOfCol(west, east, c)))
        }
        val json = JSONArray(
            Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                    "&hourly=precipitation,precipitation_probability,wind_speed_10m,wind_direction_10m" +
                    "&wind_speed_unit=mph&past_hours=6&forecast_hours=8&timeformat=unixtime",
                timeoutMs = 20_000,
            ),
        )
        val timeArr = json.getJSONObject(0).getJSONObject("hourly").getJSONArray("time")
        val hours = timeArr.length()
        val timesMs = LongArray(hours) { timeArr.getLong(it) * 1000L }
        val cells = COLS * ROWS
        val rain = Array(hours) { FloatArray(cells) { Float.NaN } }
        val prob = Array(hours) { FloatArray(cells) { Float.NaN } }
        val wind = Array(hours) { FloatArray(cells) { Float.NaN } }
        val dir = Array(hours) { FloatArray(cells) { Float.NaN } }
        fun value(a: JSONArray?, i: Int): Float =
            if (a == null || i >= a.length() || a.isNull(i)) Float.NaN
            else a.optDouble(i, Double.NaN).toFloat()
        for (p in 0 until minOf(cells, json.length())) {
            val hourly = json.getJSONObject(p).getJSONObject("hourly")
            val pr = hourly.optJSONArray("precipitation")
            val pp = hourly.optJSONArray("precipitation_probability")
            val ws = hourly.optJSONArray("wind_speed_10m")
            val wd = hourly.optJSONArray("wind_direction_10m")
            for (h in 0 until hours) {
                rain[h][p] = value(pr, h)
                prob[h][p] = value(pp, h)
                wind[h][p] = value(ws, h)
                dir[h][p] = value(wd, h)
            }
        }
        return Field(west, south, east, north, timesMs, rain, prob, wind, dir, System.currentTimeMillis())
    }

    // --- drawing --------------------------------------------------------------

    private var tileFor: Field? = null
    private val tileCache = HashMap<Int, BngMapView.MeshTile?>()

    /**
     * The rain wash for one moment, as a bitmap the map warps onto the grid.
     * COLS×ROWS pixels, one per cell — the paint's bitmap filter smooths it
     * into blobs on the way up, which is bilinear interpolation of the
     * model's own values, not invented detail. A dry hour returns null and
     * draws nothing at all; the label is what says "dry" out loud.
     */
    fun tile(f: Field, timeMs: Long): BngMapView.MeshTile? {
        val h = hourIndex(f.timesMs, timeMs)
        if (h < 0) return null
        if (tileFor !== f) { tileFor = f; tileCache.clear() }
        if (tileCache.containsKey(h)) return tileCache[h]
        val px = IntArray(COLS * ROWS) { colourFor(f.rain[h][it]) }
        val tile = if (px.all { it == 0 }) {
            null
        } else {
            val bmp = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, COLS, 0, 0, COLS, ROWS)
            BngMapView.MeshTile(bmp, f.south, f.west, f.north, f.east)
        }
        tileCache[h] = tile
        return tile
    }

    /** Wind for one moment: an arrow at every other lattice point, nine over
     *  the view — enough to see the pattern, few enough to read the map. */
    fun arrows(f: Field, timeMs: Long): List<BngMapView.WindArrow> {
        val h = hourIndex(f.timesMs, timeMs)
        if (h < 0) return emptyList()
        val out = ArrayList<BngMapView.WindArrow>()
        for (r in 1 until ROWS step 2) for (c in 1 until COLS step 2) {
            val i = r * COLS + c
            val speed = f.windMph[h][i]
            val from = f.windFromDeg[h][i]
            if (speed.isNaN() || from.isNaN()) continue
            val en = Bng.fromWgs84(latOfRow(f.south, f.north, r), lonOfCol(f.west, f.east, c))
            out.add(BngMapView.WindArrow(en.e, en.n, speed.toDouble(), from.toDouble()))
        }
        return out
    }

    /**
     * What this moment holds for one point — the words after "forecast" in
     * the scrubber's label. Null when the model has nothing for the time or
     * the place, and the label then says "forecast" alone rather than
     * padding it out.
     */
    fun describe(f: Field, timeMs: Long, lat: Double, lon: Double): String? {
        val h = hourIndex(f.timesMs, timeMs)
        if (h < 0) return null
        val cell = cellFor(f, lat, lon)
        if (cell < 0) return null
        val mm = f.rain[h][cell]
        if (mm.isNaN()) return null
        val p = f.probPct[h][cell]
        val pct = if (p.isNaN()) null else Math.round(p)
        return when {
            // The amount is the deterministic run; the percentage is how
            // sure the ensemble is. In shower weather they disagree, which
            // is exactly what is worth saying.
            mm >= 0.1f -> "rain " + String.format(Locale.UK, "%.1f", mm) + " mm" +
                (pct?.let { " ($it%)" } ?: "")
            pct != null && pct >= 25 -> "rain possible ($pct%)"
            else -> "dry"
        }
    }

    // --- the pure arithmetic --------------------------------------------------

    /** Nearest model hour, or -1 when nothing is within [HOUR_SLOP_MS]. */
    fun hourIndex(timesMs: LongArray, timeMs: Long): Int {
        var best = -1
        var bestGap = HOUR_SLOP_MS + 1
        for (i in timesMs.indices) {
            val gap = abs(timesMs[i] - timeMs)
            if (gap < bestGap) { bestGap = gap; best = i }
        }
        return best
    }

    /**
     * Row centres are spaced evenly in Mercator Y, not in latitude, because
     * that is exactly how the map warps the bitmap back — sample and render
     * must agree or every cell sits slightly north or south of its rain.
     * Row 0 is the northmost, matching pixel row 0.
     */
    fun latOfRow(south: Double, north: Double, row: Int): Double {
        val yN = mercY(north)
        val yS = mercY(south)
        return invMercY(yN + (yS - yN) * (row + 0.5) / ROWS)
    }

    fun lonOfCol(west: Double, east: Double, col: Int): Double =
        west + (east - west) * (col + 0.5) / COLS

    /** The cell a point falls in, or -1 outside the field. */
    fun cellFor(f: Field, lat: Double, lon: Double): Int {
        if (lat < f.south || lat > f.north || lon < f.west || lon > f.east) return -1
        val yN = mercY(f.north)
        val yS = mercY(f.south)
        val r = ((yN - mercY(lat)) / (yN - yS) * ROWS).toInt().coerceIn(0, ROWS - 1)
        val c = ((lon - f.west) / (f.east - f.west) * COLS).toInt().coerceIn(0, COLS - 1)
        return r * COLS + c
    }

    /**
     * mm-in-the-hour → ARGB. Nothing at all below a trace, then a blue that
     * arrives already visible and deepens on a log ramp — 0.2 mm of drizzle
     * must read over pale OS paper (the radar layer's own hard-won lesson),
     * while 2 mm and 8 mm still look different. Blue on purpose: the radar
     * draws in RainViewer's palette, and measured rain and modelled rain
     * must not wear the same coat.
     */
    fun colourFor(mmInHour: Float): Int {
        if (mmInHour.isNaN() || mmInHour < 0.05f) return 0
        val mm = mmInHour.toDouble().coerceAtMost(12.0)
        val t = (ln(1.0 + mm / 0.4) / ln(1.0 + 12.0 / 0.4)).coerceIn(0.0, 1.0)
        val a = (90 + 120 * t).toInt()
        val r = (108 - 78 * t).toInt()
        val g = (158 - 108 * t).toInt()
        val b = (238 - 48 * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mercY(latDeg: Double): Double = ln(tan(PI / 4 + Math.toRadians(latDeg) / 2))

    private fun invMercY(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - PI / 2)
}
