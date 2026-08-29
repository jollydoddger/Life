package com.jollydoddger.waymark

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
     * Which of RainViewer's colour scales the rain is painted in — his
     * choice, set in Settings and handed here.
     *
     * The default is 1 ("Original") rather than 2 ("Universal Blue"), because
     * Universal Blue fades to near-white at its light end, and over a pale OS
     * Explorer sheet that is no colour at all — light rain is exactly the
     * rain worth warning about. A scale this server *refuses* — a 4xx, the
     * server answering that this URL is wrong — is remembered and dropped for
     * scheme 2, rather than leaving a blank layer that reads as clear skies.
     *
     * Only a refusal. A dropped connection on a hill must never retire his
     * chosen colours for the life of the process: he would come back to a
     * different palette with nothing anywhere saying why, and the one it
     * falls back to is the one that vanishes over pale paper.
     */
    @Volatile
    var scheme = 1

    private const val SCHEME_FALLBACK = 2
    private val refused = HashSet<Int>()

    /** The scale actually being fetched — his choice unless the server has
     *  refused it. The key on the map names this one, never the preference,
     *  or the words under the rain stop describing the rain. */
    fun schemeNow(): Int = synchronized(refused) {
        if (scheme in refused) SCHEME_FALLBACK else scheme
    }

    /**
     * Bitmaps are ~256 KB each. A merged timeline is around two dozen frames
     * at two to six tiles apiece, so 48 meant one sweep of the scrubber
     * evicted everything it had just fetched and sweeping back paid for the
     * lot again — on mobile data, on a hillside. [trim] hands it back when
     * the system is short.
     */
    private const val CACHE_TILES = 96

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
     * Which frame the map is meant to be showing. Every request takes a
     * number; a delivery whose number is no longer current is dropped on the
     * floor.
     *
     * Without this the map can settle on a different hour than the label
     * beneath it: tiles land per-tile on three threads, and a frame that is
     * fully cached finishes instantly while an older one is still fetching,
     * so the older one posts last and wins. Mid-drag the next event papers
     * over it; at the *end* of a drag nothing does, and it sticks. "The
     * timeline says 15:00 and the rain on screen is 13:20's" is a confident
     * wrong answer to the only question this feature exists to answer.
     */
    private val generation = AtomicLong(0)

    /**
     * Tiles for one frame over one viewport. Whatever is already decoded
     * arrives at once so scrubbing does not blink, the rest as they land.
     */
    fun tiles(
        boundsEn: DoubleArray,
        frame: WxFrame,
        onTiles: (List<BngMapView.MeshTile>) -> Unit,
    ) {
        // Taken before the early return as well: a forecast frame clearing
        // the radar must also cancel the radar frame still being fetched, or
        // it paints itself back over the top a second later.
        val mine = generation.incrementAndGet()
        val path = frame.radarPath ?: run { onTiles(emptyList()); return }
        executor.execute {
            // Two passes at most. If the server refuses the chosen scale
            // partway through, every key computed under it is now the wrong
            // key, so the whole viewport is asked for again under the
            // fallback rather than left half-painted in two palettes.
            for (pass in 0 until 2) {
                val chosen = schemeNow()
                val keys = tileKeys(boundsEn, path, chosen)
                fun deliver() {
                    if (generation.get() != mine) return
                    val got = synchronized(this) { keys.mapNotNull { tileCache[it.first] } }
                    main.post { if (generation.get() == mine) onTiles(got) }
                }
                deliver()
                for ((key, xy) in keys) {
                    if (generation.get() != mine) return@execute
                    val cached = synchronized(this) {
                        val hit = tileCache.containsKey(key)
                        if (!hit) inFlight.add(key)
                        hit
                    }
                    // Note what is deliberately absent: this does *not* skip a
                    // tile some background prefetch is already downloading.
                    // It used to, and because every scrub warms its
                    // neighbours, the frame he landed on was nearly always one
                    // a prefetch had started — so every key was skipped, the
                    // only delivery was the empty snapshot taken before the
                    // loop, and nothing ever re-posted when the tiles did
                    // land. A blank radar layer reads as clear skies. One
                    // duplicated 256-pixel PNG is the cheaper mistake.
                    if (cached) continue
                    try {
                        decode(path, xy[0], xy[1], key, chosen)
                        deliver()
                    } catch (e: Exception) {
                        // A missed tile is a gap in the picture, not words
                        // worth spending; the next settle asks again.
                    } finally {
                        synchronized(this) { inFlight.remove(key) }
                    }
                }
                if (schemeNow() == chosen) return@execute
            }
        }
    }

    /**
     * Warm the frames either side of the one on screen, so dragging the
     * scrubber shows rain moving rather than a slideshow of blank maps.
     */
    fun prefetch(boundsEn: DoubleArray, around: List<WxFrame>) {
        if (around.isEmpty()) return
        // A flick down the bar queues one of these per frame passed through,
        // and the work for a moment he has already scrubbed past is work the
        // frame he actually stopped on is waiting behind. Warming is only
        // ever worth doing for the frame on screen now.
        val mine = generation.get()
        executor.execute {
            val chosen = schemeNow()
            for (frame in around) {
                if (generation.get() != mine) return@execute
                val path = frame.radarPath ?: continue
                for ((key, xy) in tileKeys(boundsEn, path, chosen)) {
                    if (generation.get() != mine) return@execute
                    val skip = synchronized(this) { tileCache.containsKey(key) || !inFlight.add(key) }
                    if (skip) continue
                    try {
                        decode(path, xy[0], xy[1], key, chosen)
                    } catch (e: Exception) {
                    } finally {
                        synchronized(this) { inFlight.remove(key) }
                    }
                }
            }
        }
    }

    /** Let go of the decoded tiles when the system is short of memory. The
     *  sky will still be there; they cost one re-fetch each. */
    fun trim() {
        synchronized(this) { tileCache.clear() }
    }

    private fun decode(path: String, x: Int, y: Int, key: String, chosen: Int) {
        val h = synchronized(this) { host }
        val bytes = try {
            Net.getBytes("$h$path/256/$TILE_Z/$x/$y/$chosen/1_1.png")
        } catch (e: Net.HttpError) {
            // Only a refusal counts, and only of a scale we could drop. A
            // 5xx is the server having a bad minute, not a verdict on the
            // colours, and a transport failure never reaches here at all.
            if (chosen != SCHEME_FALLBACK && e.code in 400..499) {
                synchronized(refused) { refused.add(chosen) }
            }
            throw e
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
    private fun tileKeys(
        boundsEn: DoubleArray,
        path: String,
        chosen: Int,
    ): List<Pair<String, IntArray>> {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        val out = ArrayList<Pair<String, IntArray>>()
        // The scale is part of the key: switching colours must re-fetch, and
        // switching back must come straight out of the cache.
        for (x in lonToX(west)..lonToX(east)) {
            for (y in latToY(north)..latToY(south)) {
                out.add("$chosen/$path/$x/$y" to intArrayOf(x, y))
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
