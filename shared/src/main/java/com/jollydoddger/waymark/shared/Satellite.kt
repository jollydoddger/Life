package com.jollydoddger.waymark.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.jollydoddger.waymark.shared.Prefs.mapboxKey
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh

/**
 * Aerial photography under the paper: the other way of asking whether a
 * path is really there.
 *
 * His reason for wanting it, and it is a good one: *"a lot of the time a
 * path can be seen this way if there is doubt."* An OS map is a survey —
 * authoritative about what was there and silent about what has happened
 * since. A photograph shows the ground. Fading one over the other answers
 * "is that dashed line real" in a way neither does alone, which is why the
 * layer carries an opacity rather than being a straight swap.
 *
 * **Why not Google, and why not the Environment Agency.** Google's imagery
 * is the obvious answer and is not licensable here at any price: Maps
 * Platform forbids their tiles on a third-party map surface or in a cache,
 * so Google stays a link out ([MainActivity.openSatellite]). The Environment
 * Agency's vertical aerial photography is far better — 10 to 50 cm, Open
 * Government Licence, free for any use — but it is published as bulk
 * GeoTIFF downloads by grid square with only an *index* WMS, so it cannot
 * be streamed onto a map. Mapbox's raster tiles are explicitly usable in
 * "any spatial application that supports image-based XYZ tilesets", which
 * this is, on his own free token — the same bargain as the OS key.
 *
 * Their one restriction that touches this app is redistribution of offline
 * maps, so aerial tiles are **never** sent to the watch. [Sync] keeps
 * shipping OS tiles and only OS tiles.
 *
 * Tiles are Web Mercator and this map is the National Grid, so each one is
 * bent onto the grid through the same [BngMapView.MeshTile] path the rain
 * radar already uses — a solved problem here, reused rather than reinvented.
 */
object Satellite {

    /** Mapbox serves satellite to 18 over Britain; past that it upscales. */
    private const val MAX_Z = 18

    /** Kept on disk so a revisited area works with no signal, exactly like
     *  the OS tiles. Not synced anywhere — see the note above. */
    private fun dir(ctx: Context) = File(ctx.filesDir, "satellite").apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "satellite").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()
    private val failed = HashMap<String, Long>()

    /** Set when Mapbox refused the token, so the UI can say so in words
     *  rather than showing an empty layer that looks like a bug. */
    @Volatile var lastAuthError: Int = 0

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    // --- Web Mercator, the small amount of it that is needed ----------------

    private fun lonToX(lon: Double, z: Int): Int =
        Math.floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    private fun latToY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return Math.floor((1.0 - ln(Math.tan(r) + 1 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
    }

    private fun xToLon(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360.0 - 180.0

    private fun yToLat(y: Int, z: Int): Double =
        Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y.toDouble() / (1 shl z)))))

    /**
     * The Mercator zoom whose pixels are about the size of the ones being
     * drawn. Mercator resolution varies with latitude and the National Grid's
     * does not, so this is worked out where the map actually is rather than
     * from a fixed table.
     */
    fun zoomFor(metresPerPx: Double, lat: Double): Int {
        if (metresPerPx <= 0) return MAX_Z
        val world = 156_543.03392 * cos(Math.toRadians(lat))
        return Math.round(ln(world / metresPerPx) / ln(2.0)).toInt().coerceIn(0, MAX_Z)
    }

    private fun url(z: Int, x: Int, y: Int, token: String): String =
        "https://api.mapbox.com/v4/mapbox.satellite/$z/$x/$y@2x.jpg90?access_token=$token"

    // --- tiles for a viewport ------------------------------------------------

    /**
     * Whatever is already decoded for [boundsEn] (west, south, east, north in
     * grid metres), fetching the rest and calling [onReady] as each lands.
     *
     * Answers immediately with what it has so panning never blanks the map,
     * which is the same bargain [TileStore] makes for the OS paper.
     */
    fun tiles(
        ctx: Context,
        boundsEn: DoubleArray,
        metresPerPx: Double,
        onReady: () -> Unit,
    ): List<BngMapView.MeshTile> {
        val token = ctx.mapboxKey
        if (token.isEmpty()) return emptyList()

        // All four corners: a grid rectangle is not a lat/lon one, and two
        // corners would clip the layer at an angle.
        var south = 90.0; var north = -90.0; var west = 180.0; var east = -180.0
        for (e in doubleArrayOf(boundsEn[0], boundsEn[2])) {
            for (n in doubleArrayOf(boundsEn[1], boundsEn[3])) {
                val (la, lo) = Bng.toWgs84(En(e, n))
                if (la < south) south = la
                if (la > north) north = la
                if (lo < west) west = lo
                if (lo > east) east = lo
            }
        }
        val z = zoomFor(metresPerPx, (south + north) / 2)
        val x0 = lonToX(west, z); val x1 = lonToX(east, z)
        val y0 = latToY(north, z); val y1 = latToY(south, z)
        // A sanity bound: a zoomed-right-out viewport is thousands of tiles
        // and none of them would be legible anyway.
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 64) return emptyList()

        val out = ArrayList<BngMapView.MeshTile>()
        for (x in x0..x1) {
            for (y in y0..y1) {
                if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) continue
                val k = key(z, x, y)
                val bmp = memory.get(k) ?: run { load(ctx, z, x, y, k, token, onReady); null }
                if (bmp != null) {
                    out.add(
                        BngMapView.MeshTile(
                            bmp,
                            yToLat(y + 1, z), xToLon(x, z), yToLat(y, z), xToLon(x + 1, z),
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun load(ctx: Context, z: Int, x: Int, y: Int, k: String, token: String, onReady: () -> Unit) {
        synchronized(inFlight) {
            if (!inFlight.add(k)) return
            // A tile the server refused is not retried on every single frame
            // of a pan; a minute later is soon enough.
            failed[k]?.let { if (System.currentTimeMillis() - it < 60_000) { inFlight.remove(k); return } }
        }
        executor.execute {
            try {
                val f = File(dir(ctx), "$z/$x/$y.jpg")
                val bytes = if (f.exists()) f.readBytes() else fetch(z, x, y, token, f)
                if (bytes != null) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        memory.put(k, bmp)
                        main.post { onReady() }
                    }
                } else {
                    synchronized(inFlight) { failed[k] = System.currentTimeMillis() }
                }
            } catch (e: Exception) {
                synchronized(inFlight) { failed[k] = System.currentTimeMillis() }
            } finally {
                synchronized(inFlight) { inFlight.remove(k) }
            }
        }
    }

    /** One tile to disk, atomically — a half-written JPEG must never be
     *  mistaken for a tile, the same rule the OS tiles keep. */
    private fun fetch(z: Int, x: Int, y: Int, token: String, f: File): ByteArray? {
        val conn = URL(url(z, x, y, token)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            return when (val code = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    f.parentFile?.mkdirs()
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                    bytes
                }
                401, 403 -> { lastAuthError = code; null }
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Bytes held on disk, for the Settings line that says what it costs. */
    fun cacheBytes(ctx: Context): Long =
        dir(ctx).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear(ctx: Context) {
        memory.evictAll()
        dir(ctx).deleteRecursively()
    }
}
