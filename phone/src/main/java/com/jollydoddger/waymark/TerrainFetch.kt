package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Whatever the map is on at the moment, click and it does its stuff."
 *
 * A square of LIDAR fetched for wherever he is looking, with no zip, no
 * file picker, no leaving the app — the Environment Agency's LIDAR
 * Composite DTM, served live as an OGC **WCS** (Web Coverage Service): the
 * standard way to ask for a raster by bounding box, not a scrape of a
 * download page. Everything past the fetch — the shading, the tiling, the
 * entry on the Ground page — is [MainActivity.adoptTerrain] unchanged; the
 * only new thing here is where the bytes come from.
 *
 * **Nothing here was guessed.** The obvious search-engine answer for this
 * service's URL turned out to be wrong — the GetCapabilities response
 * advertises a different host (`geoservices/datasets/<uuid>/wcs`, not the
 * `spatialdata/...` path every write-up quotes) — and the coverage id, the
 * supported CRS and the output format are all read off that same real
 * response rather than assumed, the same discipline the GeoTIFF reader
 * itself was built under. Two things stated here are still inference
 * rather than a fact seen in the response: the `subset=E(...)&subset=N(...)`
 * axis labels, which are the EPSG:27700 axis abbreviations the CRS
 * registry itself uses and are how GeoServer — which every profile URI in
 * that response points at — names them by default; and the per-request
 * size limit, which nothing in the capabilities response states at all, so
 * [SPAN_M] is set to match the 5 km square a manual download already hands
 * out rather than guessed at something larger. If either is wrong the
 * fault shows up as [WcsError] with the server's own words in it, because
 * a WCS refusal is an XML **ExceptionReport** — this is written to read
 * that report on any refusal rather than surface a bare "HTTP 400", so a
 * wrong guess is a message that says what to fix, not a dead end. A
 * third failure mode is not a refusal at all — a perfectly good reply
 * covering ground the dataset simply does not include, Wales chief among
 * them, since it sits entirely inside England's own bounding rectangle
 * and so cannot be ruled out before asking. See [obviouslyOutside] and
 * the empty-grid check in [fetch] for the two different ways that is
 * caught, neither of which is "trust the bounding box".
 */
object TerrainFetch {

    /** The LIDAR Composite DTM 2 m dataset, by the id its own
     *  GetCapabilities response gives it — not the friendlier slug every
     *  search result quotes, which is a different, non-working host. */
    private const val DATASET = "09ea3b37-df3a-4e8b-ac69-fb0842227b04"
    private const val BASE = "https://environment.data.gov.uk/geoservices/datasets/$DATASET/wcs"
    private const val COVERAGE_ID = "${DATASET}__Lidar_Composite_Elevation_DTM_2m"

    /**
     * The dataset's own advertised extent, EPSG:27700, off the same
     * response — a rectangle, and only that: worth knowing what it can and
     * cannot rule out. England's coastline is not a rectangle, and Wales
     * sits entirely inside the eastings this box spans (Anglesey is
     * around E 240,000 — comfortably inside 80,000–656,000), so passing
     * this check is not evidence of English coverage. It only rules out
     * the unambiguous cases: the Scottish Highlands north of the box, or
     * open sea west of it — a free, instant "definitely not" before a
     * request is even sent, never a "definitely yes". The real answer for
     * anywhere in between — Wales included — is [fetch] coming back with
     * a square of nothing, which is the one honest signal this dataset
     * can actually give: it does not describe its own shape, only cover
     * or refuse to.
     */
    private const val MIN_E = 80_000.0
    private const val MIN_N = 4_000.0
    private const val MAX_E = 656_000.0
    private const val MAX_N = 665_000.0

    fun obviouslyOutside(p: En): Boolean = p.e !in MIN_E..MAX_E || p.n !in MIN_N..MAX_N

    /** One request's span — a 5 km square, matching what a manual download
     *  already hands out and what the imported-square pipeline has
     *  actually been run against. Nothing advertises a server-side limit,
     *  so this is a choice rather than a discovered ceiling. */
    const val SPAN_M = 5_000.0

    /** However large the true size is, this is where a reply stops being
     *  trusted and starts being an error — the same reasoning the GPX and
     *  Overpass fetches already apply to a server that keeps talking. */
    private const val MAX_BYTES = 60_000_000

    class WcsError(message: String) : Exception(message)

    private fun fmt(v: Double) = "%.0f".format(java.util.Locale.UK, v)

    fun url(west: Double, south: Double, east: Double, north: Double): String =
        "$BASE?service=WCS&version=2.0.1&request=GetCoverage&CoverageId=$COVERAGE_ID" +
            "&subset=E(${fmt(west)},${fmt(east)})&subset=N(${fmt(south)},${fmt(north)})" +
            "&format=image/tiff"

    /**
     * A [SPAN_M] square centred on [centre], as a [Terrain.Grid] — the same
     * type a manually downloaded zip produces, so nothing downstream of
     * this call needs to know which door the data came through. Blocking;
     * callers run it off the main thread.
     */
    fun fetch(centre: En): Terrain.Grid {
        val half = SPAN_M / 2
        val bytes = getBody(
            url(centre.e - half, centre.n - half, centre.e + half, centre.n + half),
        )
        if (isXml(bytes)) {
            throw WcsError(exceptionText(bytes) ?: "The server refused the request and said nothing more.")
        }
        val grid = TerrainTiff.read(bytes)
        // The bbox check above cannot see England's actual shape, so this
        // is the real "no coverage here": a valid, well-formed reply with
        // every cell marked no-data. A square straddling the border comes
        // back genuinely part-empty, and that is fine — TerrainStore
        // already draws only the covered cells and leaves the rest
        // transparent. Entirely empty is the one case that means asking
        // again anywhere nearby would answer the same, most likely Wales
        // or Scotland, which are surveyed the same way by different
        // bodies and simply are not in this dataset.
        if (grid.heights.all { it.isNaN() }) {
            throw WcsError(
                "No LIDAR here — likely outside England. Wales and Scotland have their " +
                    "own, published the same way but not through this service; for Wales, " +
                    "download a square from datamap.gov.wales and share it in instead.",
            )
        }
        return grid
    }

    /**
     * The response body whatever the status code — [Net]'s own helpers
     * all throw on anything but 200 and never look at the body, which is
     * exactly the one place a WCS server puts the reason it refused.
     */
    private fun getBody(urlStr: String): ByteArray {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("User-Agent", Net.UA)
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val out = ByteArrayOutputStream()
            (stream ?: return ByteArray(0)).use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) {
                        throw WcsError("That square came back bigger than expected — try a smaller one.")
                    }
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /** A GeoTIFF opens `II* ` or `MM *`; a WCS fault report is
     *  XML, which after whatever whitespace precedes it starts with '<'. */
    private fun isXml(b: ByteArray): Boolean {
        for (c in b) {
            val ch = c.toInt().toChar()
            if (ch.isWhitespace()) continue
            return ch == '<'
        }
        return false
    }

    private val EXCEPTION_TEXT = Regex("<(?:ows:)?ExceptionText>([^<]*)</")

    private fun exceptionText(b: ByteArray): String? =
        EXCEPTION_TEXT.find(b.decodeToString()).let { it?.groupValues?.get(1)?.trim() }
}
