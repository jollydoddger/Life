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

    /** Any mapped path or track — the ground, not the law. */
    const val ALL_PATH = 4

    // Three at a time, not one: a viewport is several cells and a single
    // thread made a new area take minutes of blank map. Three concurrent
    // queries is polite to a shared Overpass instance and roughly triples
    // how fast the screen fills.
    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "prow").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "prow").apply { mkdirs() }
    private fun key(latIdx: Int, lonIdx: Int) = "p_${latIdx}_$lonIdx"

    /** The all-mapped-paths layer caches in its own cell files, so switching
     *  it on later never invalidates what the rights-of-way layer holds. */
    private fun keyAll(latIdx: Int, lonIdx: Int) = "a_${latIdx}_$lonIdx"

    /**
     * The rights of way for a viewport, in grid metres: cached cells at once
     * through [onLines], then again as each missing cell arrives.
     */
    fun refresh(
        ctx: Context,
        boundsEn: DoubleArray,
        allPaths: Boolean = false,
        onNote: (String) -> Unit = {},
        onLines: (List<ProwLine>) -> Unit,
    ) {
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
        val missing = ArrayList<Triple<Int, Int, Boolean>>() // la, lo, isAllPaths
        for (la in lat0..lat1) for (lo in lon0..lon1) {
            val f = File(dir(ctx), key(la, lo))
            if (f.exists()) load(f)?.let { cached.addAll(it) } else missing.add(Triple(la, lo, false))
            if (allPaths) {
                val fa = File(dir(ctx), keyAll(la, lo))
                if (fa.exists()) load(fa)?.let { cached.addAll(it) } else missing.add(Triple(la, lo, true))
            }
        }
        onLines(cached)
        if (missing.isNotEmpty()) onNote("Rights of way: looking…")

        for ((la, lo, all) in missing.take(MAX_FETCH_PER_PASS)) {
            val k = if (all) keyAll(la, lo) else key(la, lo)
            val claimed = synchronized(inFlight) { inFlight.add(k) }
            if (!claimed) continue
            executor.execute {
                try {
                    save(File(dir(ctx), k), if (all) fetchAllPathsCell(la, lo) else fetchCell(la, lo))
                } catch (e: Exception) {
                    // Nothing written: the cell stays missing and a later
                    // settle retries. A dropped query must not be cached as
                    // "no rights of way here" — that is the one wrong answer
                    // this layer could give.
                } finally {
                    synchronized(inFlight) { inFlight.remove(k) }
                }
                main.post { refreshCached(ctx, boundsEn, allPaths, onNote, onLines) }
            }
        }
    }

    /** Re-deliver whatever is cached for these bounds — no fetching. */
    private fun refreshCached(
        ctx: Context,
        boundsEn: DoubleArray,
        allPaths: Boolean,
        onNote: (String) -> Unit,
        onLines: (List<ProwLine>) -> Unit,
    ) {
        val (south, west) = Bng.toWgs84(En(boundsEn[0], boundsEn[1]))
        val (north, east) = Bng.toWgs84(En(boundsEn[2], boundsEn[3]))
        if (east - west > MAX_VIEW_DEG || north - south > MAX_VIEW_DEG) return
        val lines = ArrayList<ProwLine>()
        var stillMissing = 0
        for (la in Math.floor(south / CELL_DEG).toInt()..Math.floor(north / CELL_DEG).toInt()) {
            for (lo in Math.floor(west / CELL_DEG).toInt()..Math.floor(east / CELL_DEG).toInt()) {
                val f = File(dir(ctx), key(la, lo))
                if (f.exists()) load(f)?.let { lines.addAll(it) } else stillMissing++
                if (allPaths) {
                    val fa = File(dir(ctx), keyAll(la, lo))
                    if (fa.exists()) load(fa)?.let { lines.addAll(it) } else stillMissing++
                }
            }
        }
        onLines(lines)
        // A blank map cannot tell "still fetching" from "nothing here" from
        // "broken", which is exactly the confusion this cost a round of.
        if (stillMissing == 0) {
            if (lines.isEmpty()) {
                onNote(
                    "No rights of way recorded here in OpenStreetMap. " +
                        "Pull your council's own map: \u2699 \u2192 Official council data.",
                )
            } else {
                onNote("Rights of way: ${lines.size} drawn.")
            }
        }
    }

    /** One cell's rights of way from Overpass, as lines in grid metres. */
    private fun fetchCell(latIdx: Int, lonIdx: Int): List<ProwLine> {
        val south = latIdx * CELL_DEG
        val west = lonIdx * CELL_DEG
        // One literal with templates; ${'$'} for the regex end anchor.
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(south, west, south + CELL_DEG, west + CELL_DEG)
        val kinds = "public_footpath|public_bridleway|restricted_byway|byway_open_to_all_traffic"
        val end = "${'$'}"
        // Two ways of recording the same fact: the designation tag, and a
        // council path reference (AN/023/1 and the like) left by a mapper who
        // never added the designation. Taking both finds paths the first
        // query alone walks straight past.
        val query = "[out:json][timeout:60];" +
            "(way[\"designation\"~\"^($kinds)$end\"]($bbox);" +
            "way[\"prow_ref\"]($bbox););" +
            "out geom;"
        val json = Net.overpass(query, timeoutMs = 70_000)

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

    /**
     * Every mapped path and track in a cell, whatever its legal status — the
     * physical network. Ways already carrying a designation are excluded so
     * the same path is not drawn twice in two colours.
     */
    private fun fetchAllPathsCell(latIdx: Int, lonIdx: Int): List<ProwLine> {
        val south = latIdx * CELL_DEG
        val west = lonIdx * CELL_DEG
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(south, west, south + CELL_DEG, west + CELL_DEG)
        val kinds = "path|footway|track|bridleway|steps"
        val end = "${'$'}"
        val query = "[out:json][timeout:60];" +
            "way[\"highway\"~\"^($kinds)$end\"][\"designation\"!~\".\"]($bbox);" +
            "out geom;"
        val json = Net.overpass(query, timeoutMs = 70_000)
        val out = ArrayList<ProwLine>()
        val elements = JSONObject(json).getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val geom = elements.getJSONObject(i).optJSONArray("geometry") ?: continue
            val pts = FloatArray(geom.length() * 2)
            var n = 0
            for (g in 0 until geom.length()) {
                val nd = geom.optJSONObject(g) ?: continue
                val en = Bng.fromWgs84(nd.getDouble("lat"), nd.getDouble("lon"))
                pts[n++] = en.e.toFloat()
                pts[n++] = en.n.toFloat()
            }
            if (n >= 4) out.add(ProwLine(ALL_PATH, if (n == pts.size) pts else pts.copyOf(n)))
        }
        return out
    }

    // --- official council data ------------------------------------------------

    /**
     * Rowmaps publishes what each council released from its own definitive
     * map, one GeoJSON per authority. That is the real answer where OSM's
     * volunteers simply have not tagged an area yet — which, on the ground,
     * is most of the point of this layer.
     *
     * Both the council list and the file name are read from the site at run
     * time rather than baked in here: this was written without being able to
     * reach rowmaps.com, and a hard-coded guess at a URL fails silently
     * forever, where a discovered one fails loudly once and says what it
     * could not find.
     */
    private const val ROWMAPS = "https://www.rowmaps.com/jsons/"

    data class Council(val code: String, val name: String)

    /** Every council rowmaps holds data for, newest listing each time. */
    fun councils(): List<Council> {
        val html = Net.get(ROWMAPS, timeoutMs = 30_000)
        val out = HashMap<String, String>()
        // <a href="XX/">Name</a>, where the name may sit on its own line —
        // DOT_MATCHES_ALL is load-bearing: without it every anchor whose text
        // wrapped was skipped, which is why this first returned a dozen
        // councils in page order instead of all of them.
        val link = Regex(
            """<a[^>]+href="([^"]*?/)?([A-Za-z]{2,3})/?(?:index\.html?)?"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in link.findAll(html)) {
            val code = m.groupValues[2].uppercase()
            val name = unescape(m.groupValues[3].replace(Regex("<[^>]*>"), "")).trim()
            if (name.length < 3 || name.startsWith("..")) continue
            out[code] = name
        }
        // Alphabetical: ninety councils in a scrolling dialog is only usable
        // if the one you want is where you would look for it.
        return out.map { Council(it.key, it.value) }.sortedBy { it.name.lowercase() }
    }

    /** The handful of HTML entities a council name actually contains. */
    private fun unescape(s: String): String {
        var t = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        // Numeric entities, for the apostrophes and dashes in place names.
        t = Regex("&#(\\d{1,5});").replace(t) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        return t.replace(Regex("\\s+"), " ")
    }

    /**
     * Fetch one council's rights of way and file them into the same cells the
     * map already draws from, so everything downstream — drawing, caching,
     * working with no signal — is unchanged. Returns words about what
     * happened, including exactly what could not be found.
     */
    fun downloadCouncil(ctx: Context, code: String, onProgress: (String) -> Unit): String {
        onProgress("Looking up what $code publishes…")
        val page = Net.get("$ROWMAPS$code/", timeoutMs = 30_000)
        val files = Regex("""href="([^"]+\.json)"""", RegexOption.IGNORE_CASE)
            .findAll(page).map { it.groupValues[1] }.distinct().toList()
        if (files.isEmpty()) {
            return "Found the page for $code but no .json file linked on it — " +
                "rowmaps may have changed its layout. Nothing downloaded."
        }
        // Names carry dates; the last in sort order is the most recent release.
        val file = files.sorted().last()
        val url = if (file.startsWith("http")) file else "$ROWMAPS$code/$file"

        onProgress("Downloading $code rights of way…")
        val cells = HashMap<String, ArrayList<ProwLine>>()
        var features = 0
        Net.stream(url, timeoutMs = 120_000) { input ->
            android.util.JsonReader(input.reader().buffered()).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "features") {
                        r.beginArray()
                        while (r.hasNext()) {
                            readFeature(r) { line, lat, lon ->
                                features++
                                val k = key(
                                    Math.floor(lat / CELL_DEG).toInt(),
                                    Math.floor(lon / CELL_DEG).toInt(),
                                )
                                cells.getOrPut(k) { ArrayList() }.add(line)
                                if (features % 500 == 0) onProgress("Reading… $features paths")
                            }
                        }
                        r.endArray()
                    } else {
                        r.skipValue()
                    }
                }
                r.endObject()
            }
        }
        if (cells.isEmpty()) {
            return "Downloaded $code but found no path geometry in it. Nothing changed."
        }
        // Council data replaces whatever OSM had for these squares: it is the
        // better answer, and a cell present is a cell never re-queried.
        for ((k, lines) in cells) save(File(dir(ctx), k), lines)
        return "$features official rights of way saved for $code. They draw " +
            "wherever you look now, with or without signal."
    }

    /** One GeoJSON feature → zero or more lines, with its first point's WGS84. */
    private fun readFeature(
        r: android.util.JsonReader,
        emit: (ProwLine, Double, Double) -> Unit,
    ) {
        var kind = FOOTPATH
        val lines = ArrayList<Pair<FloatArray, Pair<Double, Double>>>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "properties" -> {
                    // No fixed schema across councils, so read every string
                    // value and let the words say which right it carries.
                    r.beginObject()
                    while (r.hasNext()) {
                        r.nextName()
                        val v = runCatching { r.nextString() }.getOrElse { r.skipValue(); "" }
                        val t = v.lowercase()
                        when {
                            "bridleway" in t -> kind = BRIDLEWAY
                            "restricted" in t -> kind = RESTRICTED_BYWAY
                            "byway" in t -> if (kind == FOOTPATH) kind = BYWAY
                        }
                    }
                    r.endObject()
                }
                "geometry" -> readGeometry(r) { pts, lat, lon -> lines.add(pts to (lat to lon)) }
                else -> r.skipValue()
            }
        }
        r.endObject()
        for ((pts, at) in lines) emit(ProwLine(kind, pts), at.first, at.second)
    }

    /** LineString or MultiLineString → grid-metre point arrays. */
    private fun readGeometry(
        r: android.util.JsonReader,
        emit: (FloatArray, Double, Double) -> Unit,
    ) {
        var type = ""
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "coordinates" -> {
                    if (type == "MultiLineString" || type == "MultiPolygon") {
                        r.beginArray()
                        while (r.hasNext()) readLine(r, emit)
                        r.endArray()
                    } else {
                        readLine(r, emit)
                    }
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
    }

    private fun readLine(
        r: android.util.JsonReader,
        emit: (FloatArray, Double, Double) -> Unit,
    ) {
        val es = ArrayList<Float>()
        val ns = ArrayList<Float>()
        var firstLat = 0.0
        var firstLon = 0.0
        r.beginArray()
        while (r.hasNext()) {
            r.beginArray()
            val lon = r.nextDouble()
            val lat = r.nextDouble()
            while (r.hasNext()) r.skipValue() // elevation, if the file carries it
            r.endArray()
            if (es.isEmpty()) { firstLat = lat; firstLon = lon }
            val en = Bng.fromWgs84(lat, lon)
            es.add(en.e.toFloat())
            ns.add(en.n.toFloat())
        }
        r.endArray()
        if (es.size < 2) return
        val pts = FloatArray(es.size * 2)
        for (i in es.indices) {
            pts[i * 2] = es[i]
            pts[i * 2 + 1] = ns[i]
        }
        emit(pts, firstLat, firstLon)
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
