package com.jollydoddger.waymark

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

/**
 * The weather across the map, rather than at a point.
 *
 * One request to Open-Meteo for a small grid of points over the viewport
 * brings back temperature, rainfall, cloud and wind for every hour from
 * yesterday to tomorrow — so temperature, cloud and wind cost nothing extra
 * once any one of them is switched on, and the whole timeline is already in
 * hand when the scrubber is dragged.
 *
 * This is a **model**, not an observation: it is what the forecast says the
 * air is doing, at a spacing of a few kilometres. Radar is a measurement of
 * rain that actually fell; these fields are an opinion about the sky. The
 * frame label keeps the two apart and nothing here may blur them.
 */
object Weather {

    /** Points per side. Twenty-five readings is a smooth field and a
     *  comfortable arrow density, and keeps one request small. */
    const val GRID = 5

    /** A fetched grid is good for this long before it is asked for again. */
    private const val TTL_MS = 12 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "weather").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Hourly values on a lat/lon grid. Points run row-major from the
     * north-west corner: row 0 is the northern edge, column 0 the western.
     */
    class Field(
        val timesMs: LongArray,
        val lat: DoubleArray,
        val lon: DoubleArray,
        val temp: Array<DoubleArray>,
        val rain: Array<DoubleArray>,
        val cloud: Array<DoubleArray>,
        val cloudLow: Array<DoubleArray>,
        val cloudMid: Array<DoubleArray>,
        val cloudHigh: Array<DoubleArray>,
        /** Metres — under about a kilometre is fog, and fog is not "cloud". */
        val visibility: Array<DoubleArray>,
        val windSpeed: Array<DoubleArray>,
        val windDir: Array<DoubleArray>,
        val south: Double, val west: Double, val north: Double, val east: Double,
    ) {
        /**
         * The hour nearest a moment on the scrubber, or -1 if the nearest one
         * is not near. The tolerance is the honest half: without it a frame
         * five hours outside the data was painted with the edge hour's
         * values and labelled with its own time — a reading from the wrong
         * afternoon, presented as this one.
         */
        fun hourIndex(timeMs: Long): Int {
            if (timesMs.isEmpty()) return -1
            var best = 0
            var gap = Long.MAX_VALUE
            for (i in timesMs.indices) {
                val g = kotlin.math.abs(timesMs[i] - timeMs)
                if (g < gap) { gap = g; best = i }
            }
            return if (gap <= TOLERANCE_MS) best else -1
        }

        fun hours(): List<Long> = timesMs.toList()

        companion object {
            /** How far from an hour a moment may sit and still be described
             *  by it. Radar frames land on ten-minute marks, so a shade over
             *  half an hour covers every legitimate case and nothing else. */
            private const val TOLERANCE_MS = 40 * 60_000L
        }
    }

    private var cached: Field? = null
    private var cachedKey = ""
    private var cachedAt = 0L

    /**
     * The grid over a viewport. Re-fetched when the map has moved far enough
     * to matter or the hour has turned; otherwise the held one is handed
     * straight back, because scrubbing must not cost a request per drag.
     */
    fun refresh(
        boundsEn: DoubleArray,
        onNote: (String) -> Unit = {},
        onField: (Field) -> Unit,
    ) {
        val box = latLonBox(boundsEn)
        // Rounded to about a kilometre: a nudge of the map is not a new sky.
        // Locale.UK explicitly, here and in the URL below: String.format
        // uses the default locale, and in a comma-decimal one "53.12" becomes
        // "53,12" — which in a comma-separated coordinate list is not a typo
        // the server can see, it is a different request.
        val key = "%.2f,%.2f,%.2f,%.2f".format(java.util.Locale.UK, box[0], box[1], box[2], box[3])
        synchronized(this) {
            val f = cached
            if (f != null && key == cachedKey && System.currentTimeMillis() - cachedAt < TTL_MS) {
                main.post { onField(f) }
                return
            }
        }
        executor.execute {
            try {
                val f = fetch(box)
                synchronized(this) {
                    cached = f
                    cachedKey = key
                    cachedAt = System.currentTimeMillis()
                }
                main.post { onField(f) }
            } catch (e: Exception) {
                main.post {
                    onNote("Weather: couldn't reach Open-Meteo (${e.message ?: "no connection"}).")
                }
            }
        }
    }

    /** South, west, north and east of the viewport, from all four corners —
     *  a grid rectangle is not a lat/lon one, and two corners would clip. */
    private fun latLonBox(b: DoubleArray): DoubleArray {
        var south = 90.0; var north = -90.0; var west = 180.0; var east = -180.0
        for (e in doubleArrayOf(b[0], b[2])) {
            for (n in doubleArrayOf(b[1], b[3])) {
                val (la, lo) = Bng.toWgs84(En(e, n))
                if (la < south) south = la
                if (la > north) north = la
                if (lo < west) west = lo
                if (lo > east) east = lo
            }
        }
        return doubleArrayOf(south, west, north, east)
    }

    private fun fetch(box: DoubleArray): Field {
        val south = box[0]; val west = box[1]; val north = box[2]; val east = box[3]
        val lat = DoubleArray(GRID * GRID)
        val lon = DoubleArray(GRID * GRID)
        val lats = StringBuilder()
        val lons = StringBuilder()
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val i = r * GRID + c
                lat[i] = north - (north - south) * r / (GRID - 1)
                lon[i] = west + (east - west) * c / (GRID - 1)
                if (i > 0) { lats.append(','); lons.append(',') }
                lats.append("%.4f".format(java.util.Locale.UK, lat[i]))
                lons.append("%.4f".format(java.util.Locale.UK, lon[i]))
            }
        }

        // unixtime rather than an ISO string: no parsing, no timezone to get
        // wrong, and the scrubber works in milliseconds anyway.
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=temperature_2m,precipitation,cloud_cover,cloud_cover_low,cloud_cover_mid," +
            "cloud_cover_high,visibility,wind_speed_10m,wind_direction_10m" +
            "&past_days=1&forecast_days=2&wind_speed_unit=mph" +
            "&timeformat=unixtime&timezone=UTC"
        return parseField(Net.get(url, timeoutMs = 30_000), lat, lon, box)
    }

    /**
     * The response into a [Field]. Split from the fetch so the one rule that
     * has already burned us — points are matched BY POSITION — sits under a
     * unit test with a synthetic response.
     */
    internal fun parseField(
        body: String,
        lat: DoubleArray,
        lon: DoubleArray,
        box: DoubleArray,
    ): Field {
        val south = box[0]; val west = box[1]; val north = box[2]; val east = box[3]
        // One coordinate comes back as an object, several as an array. Accept
        // both rather than depending on which shape the request happened to
        // ask for.
        val parsed = JSONTokener(body).nextValue()
        val places = ArrayList<JSONObject>()
        when (parsed) {
            is JSONArray -> for (i in 0 until parsed.length()) places.add(parsed.getJSONObject(i))
            is JSONObject -> places.add(parsed)
            else -> throw RuntimeException("Open-Meteo returned something unreadable")
        }
        if (places.isEmpty()) throw RuntimeException("Open-Meteo returned no locations")

        val first = places[0].getJSONObject("hourly")
        val timeArr = first.getJSONArray("time")
        val hours = timeArr.length()
        val timesMs = LongArray(hours) { timeArr.getLong(it) * 1000L }

        fun grid() = Array(hours) { DoubleArray(GRID * GRID) { Double.NaN } }
        val temp = grid(); val rain = grid(); val cloud = grid()
        val low = grid(); val mid = grid(); val high = grid(); val vis = grid()
        val wind = grid(); val dir = grid()

        // Places are matched to grid points BY POSITION in the response,
        // which is the order the request asked for them in. Matching by the
        // coordinates each place reports was tried and was a disaster:
        // Open-Meteo echoes the centre of its own model cell (a tenth of a
        // degree across), so twenty-five points on a walking-scale viewport
        // all report near-identical coordinates and collapse onto one grid
        // index — verified 1-of-25 filled — leaving the field NaN, the wind
        // invisible, the temperature blank and the washes patchy, worse the
        // further in the map was zoomed. Position is the reliable key.
        for (p in places.indices) {
            if (p >= GRID * GRID) break
            val h = places[p].optJSONObject("hourly") ?: continue
            fun pull(name: String, into: Array<DoubleArray>) {
                val a = h.optJSONArray(name) ?: return
                for (t in 0 until minOf(hours, a.length())) into[t][p] = a.optDouble(t, Double.NaN)
            }
            pull("temperature_2m", temp)
            pull("precipitation", rain)
            pull("cloud_cover", cloud)
            pull("cloud_cover_low", low)
            pull("cloud_cover_mid", mid)
            pull("cloud_cover_high", high)
            pull("visibility", vis)
            pull("wind_speed_10m", wind)
            pull("wind_direction_10m", dir)
        }
        return Field(
            timesMs, lat, lon, temp, rain, cloud, low, mid, high, vis,
            wind, dir, south, west, north, east,
        )
    }

    // --- rendering -----------------------------------------------------------

    /** Rendered field bitmaps are this many pixels a side, then stretched. */
    private const val RENDER = 96

    /**
     * Paint one hour of one variable into a bitmap covering the field's own
     * lat/lon box, ready for the map to bend onto the grid.
     *
     * Deliberately smooth: the readings are kilometres apart and the thing
     * being drawn is a model, so a soft wash is a more honest picture than
     * crisp cells that would imply a precision nobody has.
     */
    fun render(values: DoubleArray, ramp: (Double) -> Int): Bitmap {
        val pixels = IntArray(RENDER * RENDER)
        for (py in 0 until RENDER) {
            val fr = py.toDouble() / (RENDER - 1) * (GRID - 1)
            for (px in 0 until RENDER) {
                val fc = px.toDouble() / (RENDER - 1) * (GRID - 1)
                val v = Ramp.bilinear(values, GRID, fr, fc)
                pixels[py * RENDER + px] = if (v.isNaN()) 0 else ramp(v)
            }
        }
        return Bitmap.createBitmap(pixels, RENDER, RENDER, Bitmap.Config.ARGB_8888)
    }

    /**
     * The sky as one wash: low, mid and high cloud weighed by how much each
     * actually dims a day out, and fog drawn as its own thing — a hill fog
     * with clear sky above it is not "0% cloud", it is the one condition
     * that turns a navigation-by-sight walk into a compass leg.
     */
    fun renderSky(field: Field, hour: Int): Bitmap {
        val pixels = IntArray(RENDER * RENDER)
        val low = field.cloudLow[hour]
        val mid = field.cloudMid[hour]
        val high = field.cloudHigh[hour]
        val vis = field.visibility[hour]
        for (py in 0 until RENDER) {
            val fr = py.toDouble() / (RENDER - 1) * (GRID - 1)
            for (px in 0 until RENDER) {
                val fc = px.toDouble() / (RENDER - 1) * (GRID - 1)
                val l = Ramp.bilinear(low, GRID, fr, fc)
                val m = Ramp.bilinear(mid, GRID, fr, fc)
                val h = Ramp.bilinear(high, GRID, fr, fc)
                val v = Ramp.bilinear(vis, GRID, fr, fc)
                pixels[py * RENDER + px] = Ramp.sky(l, m, h, v)
            }
        }
        return Bitmap.createBitmap(pixels, RENDER, RENDER, Bitmap.Config.ARGB_8888)
    }
}
