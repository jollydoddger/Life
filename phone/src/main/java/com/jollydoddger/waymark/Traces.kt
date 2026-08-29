package com.jollydoddger.waymark

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Where people have actually walked: OpenStreetMap's public GPS traces,
 * fetched cell by cell for the visible map and drawn as faint dots. The
 * honest substitute for a Strava-style heatmap, which is not licensable at
 * any price — and honestly weaker in one stated way: the dots are
 * *cumulative*, an answer to "does this path exist on the ground", never to
 * "was it walked recently".
 *
 * The API (`/api/0.6/trackpoints`) is keyless, capped at 5 000 points a page
 * and a quarter-degree bbox. Cells are 0.02° squares cached as small binary
 * files, so a revisited area — including offline on a hillside — costs
 * nothing; an empty cell is cached too, as the empty file it is, because
 * "nobody has recorded here" is exactly the answer the overlay exists to
 * show and not worth re-asking the server for.
 */
object Traces {

    private const val CELL_DEG = 0.02

    /**
     * Enough to cover the whole visible map (the zoom gate keeps a viewport
     * to ~36 cells). The first cut fetched 8, which painted part of the
     * screen and stopped on a hard cell edge — read, correctly, as data
     * being held back.
     */
    private const val MAX_FETCH_PER_PASS = 24

    /**
     * Pages read per cell (5 000 points each). The API serves newest traces
     * first, and recent uploads skew heavily to driving and riding apps —
     * so a shallow read shows the roads and never reaches the older walking
     * traces underneath. Two pages was exactly that mistake; twenty goes
     * 100 000 points deep before giving up, which in practice is the
     * bottom of the archive for any 0.02° square outside a city centre.
     */
    private const val MAX_PAGES = 20

    /** Dots kept per cell — plenty for texture, bounded for the frame loop. */
    private const val MAX_PTS_PER_CELL = 6_000

    /** Zoomed out past this, dots are soup: draw nothing, fetch nothing. */
    private const val MAX_VIEW_DEG = 0.1

    // Three at a time. Deep paging plus a whole viewport on one thread is
    // how a first look at an area became a long wait for nothing visible.
    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "traces").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>() // guarded by synchronized(inFlight)

    private fun dir(ctx: Context): File {
        // v3: earlier cuts cached shallower reads (two pages, then eight);
        // those files under-represent footpaths and are replaced, not
        // trusted — a deeper limit means nothing for cells already cached
        // at the old one.
        for (old in listOf("traces", "traces2")) {
            val legacy = File(ctx.filesDir, old)
            if (legacy.exists()) legacy.deleteRecursively()
        }
        return File(ctx.filesDir, "traces3").apply { mkdirs() }
    }
    private fun key(latIdx: Int, lonIdx: Int) = "c_${latIdx}_$lonIdx"

    /**
     * The dots for a viewport, in grid metres: cached cells straight away via
     * [onCells] (on the main thread), then again as each missing cell lands.
     * [boundsEn] is the map's west, south, east, north.
     */
    fun refresh(
        ctx: Context,
        boundsEn: DoubleArray,
        onNote: (String) -> Unit = {},
        onCells: (List<FloatArray>) -> Unit,
    ) {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        if (east - west > MAX_VIEW_DEG || north - south > MAX_VIEW_DEG) {
            onCells(emptyList())
            return
        }

        val lat0 = Math.floor(south / CELL_DEG).toInt()
        val lat1 = Math.floor(north / CELL_DEG).toInt()
        val lon0 = Math.floor(west / CELL_DEG).toInt()
        val lon1 = Math.floor(east / CELL_DEG).toInt()

        val cached = ArrayList<FloatArray>()
        val missing = ArrayList<Pair<Int, Int>>()
        for (la in lat0..lat1) for (lo in lon0..lon1) {
            val f = File(dir(ctx), key(la, lo))
            if (f.exists()) {
                load(f)?.takeIf { it.isNotEmpty() }?.let { cached.add(it) }
            } else {
                missing.add(la to lo)
            }
        }
        onCells(cached)
        if (missing.isNotEmpty()) onNote("Where people have walked: looking…")

        for ((la, lo) in missing.take(MAX_FETCH_PER_PASS)) {
            val k = key(la, lo)
            val claimed = synchronized(inFlight) { inFlight.add(k) }
            if (!claimed) continue
            executor.execute {
                try {
                    val pts = fetchCell(la, lo)
                    save(File(dir(ctx), k), pts)
                } catch (e: Exception) {
                    // No file written: the cell stays missing and a later
                    // settle retries. A network blip must not be cached as
                    // "nobody walks here".
                } finally {
                    synchronized(inFlight) { inFlight.remove(k) }
                }
                main.post { refreshCached(ctx, boundsEn, onNote, onCells) }
            }
        }
    }

    /** Re-deliver whatever is cached for [boundsEn] — no fetching. */
    private fun refreshCached(
        ctx: Context,
        boundsEn: DoubleArray,
        onNote: (String) -> Unit,
        onCells: (List<FloatArray>) -> Unit,
    ) {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        if (east - west > MAX_VIEW_DEG || north - south > MAX_VIEW_DEG) return
        val cells = ArrayList<FloatArray>()
        var stillMissing = 0
        for (la in Math.floor(south / CELL_DEG).toInt()..Math.floor(north / CELL_DEG).toInt()) {
            for (lo in Math.floor(west / CELL_DEG).toInt()..Math.floor(east / CELL_DEG).toInt()) {
                val f = File(dir(ctx), key(la, lo))
                if (!f.exists()) stillMissing++
                else load(f)?.takeIf { it.isNotEmpty() }?.let { cells.add(it) }
            }
        }
        onCells(cells)
        if (stillMissing == 0 && cells.isEmpty()) {
            onNote("Nobody has publicly recorded a GPS track around here.")
        }
    }

    /** One cell's trackpoints, several pages deep, decimated, as [e,n,e,n,…]. */
    private fun fetchCell(latIdx: Int, lonIdx: Int): FloatArray {
        val south = latIdx * CELL_DEG
        val west = lonIdx * CELL_DEG
        val pts = ArrayList<En>()
        for (page in 0 until MAX_PAGES) {
            val bbox = "%.5f,%.5f,%.5f,%.5f".format(west, south, west + CELL_DEG, south + CELL_DEG)
            val xml = Net.get(
                "https://api.openstreetmap.org/api/0.6/trackpoints?bbox=$bbox&page=$page",
                timeoutMs = 20_000,
            )
            val got = parseTrkpts(xml)
            pts.addAll(got)
            if (got.size < 5_000) break // a short page is the last page
        }
        val stride = (pts.size / MAX_PTS_PER_CELL) + 1
        val out = FloatArray(2 * ((pts.size + stride - 1) / stride))
        var n = 0
        for (i in pts.indices step stride) {
            out[n++] = pts[i].e.toFloat()
            out[n++] = pts[i].n.toFloat()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun parseTrkpts(xml: String): List<En> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        val pts = ArrayList<En>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) pts.add(Bng.fromWgs84(lat, lon))
            }
            event = parser.next()
        }
        return pts
    }

    private fun save(f: File, pts: FloatArray) {
        val buf = ByteBuffer.allocate(pts.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(pts)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun load(f: File): FloatArray? = try {
        val bytes = f.readBytes()
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        out
    } catch (e: Exception) {
        null
    }
}
