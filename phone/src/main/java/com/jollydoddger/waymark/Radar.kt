package com.jollydoddger.waymark

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The rainfall radar: RainViewer's composite of real weather radars, warped
 * onto the OS map. Weather data by RainViewer (rainviewer.com) — the
 * attribution is a condition of the free API and appears in Settings.
 *
 * Every published frame is fetched, not just the newest, so the timeline can
 * be scrubbed and the rain can be watched moving. That is the whole value of
 * a radar over a forecast: you can see which way the shower is travelling and
 * decide whether it is coming for you.
 *
 * Nothing is cached to disk. Radar is *now* by definition, frames go stale in
 * minutes, and an offline radar would be a lie about the sky. Tiles are
 * Web-Mercator slippy XYZ; the map view warps each one through a mesh,
 * because pretending Mercator and the National Grid agree is how rain ends up
 * over the wrong village.
 */
object Radar {

    /** The frame catalogue is republished about every ten minutes. */
    private const val CATALOGUE_TTL_MS = 4 * 60_000L

    /** Slippy zoom for the tiles: z7 tiles are ~200 km across at UK
     *  latitudes — two to six cover any viewport the app allows. */
    private const val TILE_Z = 7

    /**
     * Colour scheme 1 ("Original") rather than 2 ("Universal Blue").
     *
     * Universal Blue fades to near-white at the light end, which over a pale
     * OS Explorer sheet is invisible — light rain is exactly the rain you want
     * warning of, and it was the one thing the layer could not show. Scheme 1
     * keeps a solid colour all the way down. If RainViewer ever stops serving
     * it the first failed tile drops back to 2 rather than leaving a blank
     * layer that looks like clear skies.
     */
    private const val SCHEME_BOLD = 1
    private const val SCHEME_FALLBACK = 2
    private var scheme = SCHEME_BOLD

    /** Bitmaps are ~256 KB each; enough for a scrub over a small viewport. */
    private const val CACHE_TILES = 48

    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "radar").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private var host = ""
    private var frames: List<WxFrame> = emptyList()
    private var cataloguedAt = 0L

    private val tileCache = object : LinkedHashMap<String, BngMapView.MeshTile>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, BngMapView.MeshTile>?,
        ): Boolean = size > CACHE_TILES
    }
    private val inFlight = HashSet<String>()

    /**
     * The published radar frames, newest catalogue first fetched if stale.
     * Failure is reported in words: a silent empty layer is indistinguishable
     * from a clear sky, which is the one wrong answer this can give.
     */
    fun catalogue(onNote: (String) -> Unit, onFrames: (List<WxFrame>) -> Unit) {
        executor.execute {
            val fresh = synchronized(this) {
                frames.isNotEmpty() && System.currentTimeMillis() - cataloguedAt < CATALOGUE_TTL_MS
            }
            if (!fresh) {
                try {
                    fetchCatalogue()
                } catch (e: Exception) {
                    main.post {
                        onNote("Rain radar: couldn't reach RainViewer (${e.message ?: "no connection"}).")
                    }
                    return@execute
                }
            }
            val list = synchronized(this) { frames }
            main.post { onFrames(list) }
        }
    }

    private fun fetchCatalogue() {
        val json = JSONObject(
            Net.get("https://api.rainviewer.com/public/weather-maps.json", timeoutMs = 20_000),
        )
        val h = json.getString("host")
        val radar = json.getJSONObject("radar")
        val out = ArrayList<WxFrame>()
        fun take(key: String, nowcast: Boolean) {
            val arr = radar.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out.add(WxFrame(f.getLong("time") * 1000L, f.getString("path"), nowcast))
            }
        }
        take("past", false)
        take("nowcast", true)
        out.sortBy { it.timeMs }
        synchronized(this) {
            host = h
            frames = out
            cataloguedAt = System.currentTimeMillis()
        }
    }

    /**
     * Tiles for one frame over one viewport. Whatever is already decoded
     * arrives at once so scrubbing does not blink, the rest as they land.
     */
    fun tiles(
        boundsEn: DoubleArray,
        frame: WxFrame,
        onTiles: (List<BngMapView.MeshTile>) -> Unit,
    ) {
        val path = frame.radarPath ?: run { onTiles(emptyList()); return }
        executor.execute {
            val keys = tileKeys(boundsEn, path)
            fun deliver() {
                val got = synchronized(this) { keys.mapNotNull { tileCache[it.first] } }
                main.post { onTiles(got) }
            }
            deliver()
            for ((key, xy) in keys) {
                val skip = synchronized(this) { tileCache.containsKey(key) || !inFlight.add(key) }
                if (skip) continue
                try {
                    decode(path, xy[0], xy[1], key)
                    deliver()
                } catch (e: Exception) {
                    // A missed tile is a gap in the picture, not words worth
                    // spending; the next settle asks again.
                } finally {
                    synchronized(this) { inFlight.remove(key) }
                }
            }
        }
    }

    /**
     * Warm the frames either side of the one on screen, so dragging the
     * scrubber shows rain moving rather than a slideshow of blank maps.
     */
    fun prefetch(boundsEn: DoubleArray, around: List<WxFrame>) {
        if (around.isEmpty()) return
        executor.execute {
            for (frame in around) {
                val path = frame.radarPath ?: continue
                for ((key, xy) in tileKeys(boundsEn, path)) {
                    val skip = synchronized(this) { tileCache.containsKey(key) || !inFlight.add(key) }
                    if (skip) continue
                    try {
                        decode(path, xy[0], xy[1], key)
                    } catch (e: Exception) {
                    } finally {
                        synchronized(this) { inFlight.remove(key) }
                    }
                }
            }
        }
    }

    private fun decode(path: String, x: Int, y: Int, key: String) {
        val h = synchronized(this) { host }
        val scheme0 = synchronized(this) { scheme }
        val bytes = try {
            Net.getBytes("$h$path/256/$TILE_Z/$x/$y/$scheme0/1_1.png")
        } catch (e: Exception) {
            // A colour scheme this server will not serve must not leave the
            // layer permanently blank — that reads as "no rain anywhere".
            if (scheme0 == SCHEME_FALLBACK) throw e
            synchronized(this) { scheme = SCHEME_FALLBACK }
            Net.getBytes("$h$path/256/$TILE_Z/$x/$y/$SCHEME_FALLBACK/1_1.png")
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        val tile = BngMapView.MeshTile(
            bitmap = bmp,
            south = yToLat(y + 1), west = xToLon(x),
            north = yToLat(y), east = xToLon(x + 1),
        )
        synchronized(this) { tileCache[key] = tile }
    }

    /** The tiles covering a viewport, as cache key to (x, y). */
    private fun tileKeys(boundsEn: DoubleArray, path: String): List<Pair<String, IntArray>> {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        val out = ArrayList<Pair<String, IntArray>>()
        for (x in lonToX(west)..lonToX(east)) {
            for (y in latToY(north)..latToY(south)) {
                out.add("$path/$x/$y" to intArrayOf(x, y))
            }
        }
        return out
    }

    // --- slippy-tile arithmetic at TILE_Z ------------------------------------

    private fun lonToX(lon: Double): Int =
        floor((lon + 180.0) / 360.0 * (1 shl TILE_Z)).toInt()

    private fun latToY(lat: Double): Int {
        val rad = Math.toRadians(lat)
        val y = (1.0 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2.0 * (1 shl TILE_Z)
        return floor(y).toInt().coerceIn(0, (1 shl TILE_Z) - 1)
    }

    private fun xToLon(x: Int): Double = x.toDouble() / (1 shl TILE_Z) * 360.0 - 180.0

    private fun yToLat(y: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl TILE_Z)))))
}
