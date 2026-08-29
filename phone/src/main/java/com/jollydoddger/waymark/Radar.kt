package com.jollydoddger.waymark

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.BngMapView
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The rainfall radar: RainViewer's composite of real weather radars, the
 * newest frame, warped onto the OS map. Weather data by RainViewer
 * (rainviewer.com) — the attribution is a condition of the free API and
 * appears in Settings.
 *
 * Radar is *now* by definition, so unlike every other layer nothing here is
 * cached to disk: frames go stale in five minutes, and an offline radar
 * would be a lie about the sky. Tiles are Web-Mercator slippy XYZ; the map
 * view warps each one through a mesh, because pretending Mercator and the
 * National Grid agree is how rain ends up over the wrong village.
 */
object Radar {

    /** Frames refresh roughly this often; asking more is just load. */
    private const val FRAME_TTL_MS = 5 * 60_000L

    /** Slippy zoom for the tiles: z7 tiles are ~200 km across at UK
     *  latitudes — two to six cover any viewport the app allows. */
    private const val TILE_Z = 7

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "radar").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    // Latest frame: tile URL prefix + its unix time. Guarded by synchronized(Radar).
    private var frameHost = ""
    private var framePath = ""
    private var frameTime = 0L
    private var frameFetchedAt = 0L

    private val tileCache = HashMap<String, BngMapView.RadarTile>()
    private val inFlight = HashSet<String>()

    /**
     * The radar tiles for a viewport: whatever is already decoded now, the
     * rest as they land. [onNote] gets the frame's clock time once known.
     */
    fun refresh(
        boundsEn: DoubleArray,
        onNote: (String) -> Unit = {},
        onTiles: (List<BngMapView.RadarTile>) -> Unit,
    ) {
        executor.execute {
            try {
                ensureFrame()
            } catch (e: Exception) {
                main.post { onNote("Rain radar: couldn't reach RainViewer (${e.message ?: "no connection"}).") }
                return@execute
            }
            val (host, path, time) = synchronized(this) { Triple(frameHost, framePath, frameTime) }
            val clock = SimpleDateFormat("HH:mm", Locale.UK).format(Date(time * 1000))

            val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
            val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
            val x0 = lonToX(west)
            val x1 = lonToX(east)
            val y0 = latToY(north)
            val y1 = latToY(south)

            val wanted = ArrayList<String>()
            for (x in x0..x1) for (y in y0..y1) wanted.add("$time/$x/$y")

            fun deliver() {
                val tiles = synchronized(this) { wanted.mapNotNull { tileCache[it] } }
                main.post { onTiles(tiles) }
            }
            deliver()

            var fetched = false
            for (x in x0..x1) for (y in y0..y1) {
                val k = "$time/$x/$y"
                val have = synchronized(this) { tileCache.containsKey(k) || !inFlight.add(k) }
                if (have) continue
                fetched = true
                try {
                    val url = "$host$path/256/$TILE_Z/$x/$y/2/1_1.png"
                    val bytes = Net.getBytes(url)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val tile = BngMapView.RadarTile(
                        bitmap = bmp,
                        south = yToLat(y + 1), west = xToLon(x),
                        north = yToLat(y), east = xToLon(x + 1),
                    )
                    synchronized(this) {
                        // Old frames' tiles are dead weight; keep only this frame's.
                        tileCache.keys.retainAll { it.startsWith("$time/") }
                        tileCache[k] = tile
                    }
                    deliver()
                } catch (e: Exception) {
                    // A missed tile is a gap in the rain picture, not an error
                    // worth words; the next settle retries it.
                } finally {
                    synchronized(this) { inFlight.remove(k) }
                }
            }
            if (fetched) main.post { onNote("Rain radar: $clock (RainViewer).") }
        }
    }

    /** Fetch the frame catalogue if the one we hold has gone stale. */
    private fun ensureFrame() {
        synchronized(this) {
            if (frameTime != 0L && System.currentTimeMillis() - frameFetchedAt < FRAME_TTL_MS) return
        }
        val json = JSONObject(Net.get("https://api.rainviewer.com/public/weather-maps.json", timeoutMs = 20_000))
        val host = json.getString("host")
        val past = json.getJSONObject("radar").getJSONArray("past")
        val newest = past.getJSONObject(past.length() - 1)
        synchronized(this) {
            frameHost = host
            framePath = newest.getString("path")
            frameTime = newest.getLong("time")
            frameFetchedAt = System.currentTimeMillis()
        }
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
