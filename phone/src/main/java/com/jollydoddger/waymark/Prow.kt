package com.jollydoddger.waymark

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.ProwLine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Public rights of way: the paths you are legally entitled to walk, as
 * opposed to the red dots' "somebody once did".
 *
 * The OS map underneath already draws these in green dashes, so this layer
 * is not revealing something invisible — it makes them loud, and it makes
 * them *data*, which the paper map can never be. Being data is what lets
 * the app answer "is that a right of way" rather than leaving it to the
 * reader's eyesight in the rain.
 *
 * Source is OpenStreetMap's `designation` tag, the England-and-Wales
 * convention for recording what a council's definitive map says. That is a
 * copy of the official record rather than the record itself — good, not
 * gospel — but it covers every council at once, where council GIS feeds are
 * a different endpoint, schema and licence per authority. The honest test
 * is on the screen: where this layer and the OS map's own green dashes
 * disagree, the paper is right.
 */
object Prow {

    /** Degrees per cached cell. Bigger than the traces grid: one Overpass
     *  query per cell, so fewer and larger beats many and small. */
    private const val CELL_DEG = 0.1

    private const val MAX_FETCH_PER_PASS = 9

    /** Zoomed out past this, it is spaghetti and a heavy query. */
    private const val MAX_VIEW_DEG = 0.35

    const val FOOTPATH = 0
    const val BRIDLEWAY = 1
    const val RESTRICTED_BYWAY = 2
    const val BYWAY = 3

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "prow").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "prow").apply { mkdirs() }
    private fun key(latIdx: Int, lonIdx: Int) = "p_${latIdx}_$lonIdx"

    /**
     * The rights of way for a viewport, in grid metres: cached cells at once
     * through [onLines], then again as each missing cell arrives.
     */
    fun refresh(ctx: Context, boundsEn: DoubleArray, onLines: (List<ProwLine>) -> Unit) {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        if (east - west > MAX_VIEW_DEG || north - south > MAX_VIEW_DEG) {
            onLines(emptyList())
            return
        }

        val lat0 = Math.floor(south / CELL_DEG).toInt()
        val lat1 = Math.floor(north / CELL_DEG).toInt()
        val lon0 = Math.floor(west / CELL_DEG).toInt()
        val lon1 = Math.floor(east / CELL_DEG).toInt()

        val cached = ArrayList<ProwLine>()
        val missing = ArrayList<Pair<Int, Int>>()
        for (la in lat0..lat1) for (lo in lon0..lon1) {
            val f = File(dir(ctx), key(la, lo))
            if (f.exists()) load(f)?.let { cached.addAll(it) } else missing.add(la to lo)
        }
        onLines(cached)

        for ((la, lo) in missing.take(MAX_FETCH_PER_PASS)) {
            val k = key(la, lo)
            val claimed = synchronized(inFlight) { inFlight.add(k) }
            if (!claimed) continue
            executor.execute {
                try {
                    save(File(dir(ctx), k), fetchCell(la, lo))
                } catch (e: Exception) {
                    // Nothing written: the cell stays missing and a later
                    // settle retries. A dropped query must not be cached as
                    // "no rights of way here" — that is the one wrong answer
                    // this layer could give.
                } finally {
                    synchronized(inFlight) { inFlight.remove(k) }
                }
                main.post { refreshCached(ctx, boundsEn, onLines) }
            }
        }
    }

    /** Re-deliver whatever is cached for these bounds — no fetching. */
    private fun refreshCached(ctx: Context, boundsEn: DoubleArray, onLines: (List<ProwLine>) -> Unit) {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        if (east - west > MAX_VIEW_DEG || north - south > MAX_VIEW_DEG) return
        val lines = ArrayList<ProwLine>()
        for (la in Math.floor(south / CELL_DEG).toInt()..Math.floor(north / CELL_DEG).toInt()) {
            for (lo in Math.floor(west / CELL_DEG).toInt()..Math.floor(east / CELL_DEG).toInt()) {
                val f = File(dir(ctx), key(la, lo))
                if (f.exists()) load(f)?.let { lines.addAll(it) }
            }
        }
        onLines(lines)
    }

    /** One cell's rights of way from Overpass, as lines in grid metres. */
    private fun fetchCell(latIdx: Int, lonIdx: Int): List<ProwLine> {
        val south = latIdx * CELL_DEG
        val west = lonIdx * CELL_DEG
        // One literal with templates; ${'$'} for the regex end anchor.
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(south, west, south + CELL_DEG, west + CELL_DEG)
        val kinds = "public_footpath|public_bridleway|restricted_byway|byway_open_to_all_traffic"
        val end = "${'$'}"
        val query = "[out:json][timeout:60];" +
            "way[\"designation\"~\"^($kinds)$end\"]($bbox);" +
            "out geom;"
        val json = Net.post(
            "https://overpass-api.de/api/interpreter",
            "data=" + Net.encode(query),
            "application/x-www-form-urlencoded",
            timeoutMs = 70_000,
        )

        val out = ArrayList<ProwLine>()
        val elements = JSONObject(json).getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val geom = el.optJSONArray("geometry") ?: continue
            val kind = when (el.optJSONObject("tags")?.optString("designation")) {
                "public_bridleway" -> BRIDLEWAY
                "restricted_byway" -> RESTRICTED_BYWAY
                "byway_open_to_all_traffic" -> BYWAY
                else -> FOOTPATH
            }
            val pts = FloatArray(geom.length() * 2)
            var n = 0
            for (g in 0 until geom.length()) {
                val nd = geom.optJSONObject(g) ?: continue
                val en = Bng.fromWgs84(nd.getDouble("lat"), nd.getDouble("lon"))
                pts[n++] = en.e.toFloat()
                pts[n++] = en.n.toFloat()
            }
            if (n >= 4) out.add(ProwLine(kind, if (n == pts.size) pts else pts.copyOf(n)))
        }
        return out
    }

    private fun save(f: File, lines: List<ProwLine>) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { d ->
            d.writeInt(lines.size)
            for (line in lines) {
                d.writeInt(line.kind)
                d.writeInt(line.pts.size)
                for (v in line.pts) d.writeFloat(v)
            }
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes.toByteArray())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun load(f: File): List<ProwLine>? = try {
        DataInputStream(f.inputStream().buffered()).use { d ->
            val count = d.readInt()
            val out = ArrayList<ProwLine>(count)
            repeat(count) {
                val kind = d.readInt()
                val n = d.readInt()
                val pts = FloatArray(n)
                for (i in 0 until n) pts[i] = d.readFloat()
                out.add(ProwLine(kind, pts))
            }
            out
        }
    } catch (e: Exception) {
        null
    }
}
