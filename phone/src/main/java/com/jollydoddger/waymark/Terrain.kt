package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import java.io.InputStream
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ground itself, with the vegetation taken off it.
 *
 * The Environment Agency's LIDAR composite is a bare-earth terrain model of
 * England at one metre, free under the Open Government Licence, published
 * in 5 km tiles **aligned to the National Grid** — which is this app's own
 * coordinate system, so a tile drops onto the map at its grid rectangle
 * with no reprojection at all. Wales publishes the same thing.
 *
 * Why it is worth the download, and why it is not just a prettier aerial
 * photograph: a terrain model has had the trees removed. A worn path, a
 * holloway, an old terrace or a field boundary that has not been ploughed
 * shows up in hillshade **under woodland**, which is the one place both
 * aerial imagery and a satellite layer can tell you nothing at all. In a
 * wooded clough it is the difference between seeing nothing and seeing
 * where the path has actually run for two hundred years.
 *
 * It answers a slightly different question from the other checks, and the
 * difference is worth keeping straight: GPS traces say *people go here*,
 * access tags say *you are allowed*, a photograph says *there is a line on
 * the ground today*, and this says *the ground itself is shaped as though
 * something has run along here*. An abandoned right of way shows here and
 * nowhere else.
 *
 * Everything in this file is pure arithmetic over a grid of heights, which
 * is deliberate: the parsing and the picture are the two things that can be
 * quietly wrong, and a hillshade that is subtly mis-lit is not something
 * anyone would catch by looking at it on a hillside.
 */
object Terrain {

    /**
     * A square of heights on the National Grid. [west] and [south] are the
     * grid metres of the **bottom-left corner of the bottom-left cell**, and
     * row 0 of [heights] is the **northernmost** row — the order every
     * raster format in this business uses, and the one thing about them
     * most likely to be got upside down.
     */
    class Grid(
        val west: Double,
        val south: Double,
        val cellSize: Double,
        val cols: Int,
        val rows: Int,
        val heights: FloatArray,
        /** Cells with no survey. Kept as NaN so they can never be shaded as
         *  though they were sea level, which is what a -9999 left in place
         *  does: a cliff round the edge of every gap. */
        val noData: Float = Float.NaN,
    ) {
        val east: Double get() = west + cols * cellSize
        val north: Double get() = south + rows * cellSize

        /** Height at a row and column, or NaN off the edge or in a gap. */
        fun at(row: Int, col: Int): Float {
            if (row < 0 || col < 0 || row >= rows || col >= cols) return Float.NaN
            return heights[row * cols + col]
        }

        /** Whether a grid point falls inside this square. */
        operator fun contains(p: En): Boolean =
            p.e >= west && p.e < east && p.n >= south && p.n < north

        /** The height under a grid point, nearest cell, or NaN. */
        fun heightAt(p: En): Float {
            if (p !in this) return Float.NaN
            val col = ((p.e - west) / cellSize).toInt()
            val row = ((north - p.n) / cellSize).toInt()
            return at(row, col)
        }
    }

    // --- ASCII Grid ----------------------------------------------------------

    /**
     * Esri ASCII Grid, which is what the Environment Agency published before
     * the 2019 release and what plenty of their archive still is: six header
     * lines and then the numbers, row by row from the north.
     *
     * Read as a stream rather than into a string: a 5 km tile at one metre
     * is twenty-five million numbers, and holding that as text first is
     * about 150 MB of `String` before a single height exists — the same
     * mistake the Overpass parser was rewritten to stop making.
     */
    fun parseAsc(input: InputStream): Grid {
        val reader = input.bufferedReader()
        var cols = 0
        var rows = 0
        var xll = Double.NaN
        var yll = Double.NaN
        var cell = Double.NaN
        var nodata = -9999.0f
        var corner = true

        // The header is six keyword lines, but the keywords vary in case and
        // the corner may be given as a cell centre rather than a corner.
        var line = reader.readLine()
        while (line != null) {
            val t = line.trim()
            if (t.isEmpty()) { line = reader.readLine(); continue }
            val parts = t.split(Regex("\\s+"), limit = 2)
            val head = parts[0].lowercase()
            if (head.toDoubleOrNull() != null || head.startsWith("-")) break // into the data
            val v = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            when (head) {
                "ncols" -> cols = v?.toInt() ?: 0
                "nrows" -> rows = v?.toInt() ?: 0
                "xllcorner" -> { xll = v ?: Double.NaN; corner = true }
                "yllcorner" -> { yll = v ?: Double.NaN; corner = true }
                "xllcenter", "xllcentre" -> { xll = v ?: Double.NaN; corner = false }
                "yllcenter", "yllcentre" -> { yll = v ?: Double.NaN; corner = false }
                "cellsize" -> cell = v ?: Double.NaN
                "nodata_value" -> nodata = (v ?: -9999.0).toFloat()
                else -> {}
            }
            line = reader.readLine()
        }
        require(cols > 0 && rows > 0 && !cell.isNaN()) { "not an ASCII grid header" }
        // A centre-referenced corner is half a cell inside the real one.
        if (!corner) { xll -= cell / 2; yll -= cell / 2 }

        val heights = FloatArray(cols * rows) { Float.NaN }
        var i = 0
        // `line` is already holding the first row of data.
        while (line != null && i < heights.size) {
            val t = line.trim()
            if (t.isNotEmpty()) {
                for (tok in t.split(Regex("\\s+"))) {
                    if (i >= heights.size) break
                    val h = tok.toFloatOrNull()
                    heights[i++] = if (h == null || h == nodata) Float.NaN else h
                }
            }
            line = reader.readLine()
        }
        return Grid(xll, yll, cell, cols, rows, heights)
    }

    // --- hillshade -----------------------------------------------------------

    /** Light from the north-west, high-ish. The cartographic convention, and
     *  not arbitrary: lit from the south, hills read as hollows to most
     *  people, an illusion strong enough to survive being told about it. */
    const val AZIMUTH_DEG = 315.0
    const val ALTITUDE_DEG = 45.0

    /**
     * Grey hillshade, 0..255 per cell, NaN cells transparent (-1).
     *
     * Standard Horn slope over the eight neighbours. [exaggeration] lifts
     * small features: a path worn thirty centimetres into a hillside is a
     * real shape and an almost invisible one at true scale, and the whole
     * point of looking is to see it.
     */
    fun hillshade(
        g: Grid,
        exaggeration: Double = 3.0,
        azimuthDeg: Double = AZIMUTH_DEG,
        altitudeDeg: Double = ALTITUDE_DEG,
    ): IntArray {
        val out = IntArray(g.cols * g.rows)
        val az = Math.toRadians(360.0 - azimuthDeg + 90.0)
        val alt = Math.toRadians(altitudeDeg)
        val sinAlt = sin(alt)
        val cosAlt = cos(alt)
        val scale = 8.0 * g.cellSize / exaggeration
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                val here = g.at(r, c)
                if (here.isNaN()) { out[r * g.cols + c] = -1; continue }
                // Horn's kernel, falling back to the centre height at the
                // edges so a tile's border is shaded rather than black.
                fun h(dr: Int, dc: Int): Double {
                    val v = g.at(r + dr, c + dc)
                    return if (v.isNaN()) here.toDouble() else v.toDouble()
                }
                val dzdx = ((h(-1, 1) + 2 * h(0, 1) + h(1, 1)) -
                    (h(-1, -1) + 2 * h(0, -1) + h(1, -1))) / scale
                val dzdy = ((h(1, -1) + 2 * h(1, 0) + h(1, 1)) -
                    (h(-1, -1) + 2 * h(-1, 0) + h(-1, 1))) / scale
                val slope = atan(sqrt(dzdx * dzdx + dzdy * dzdy))
                val aspect = atan2(dzdy, -dzdx)
                // The standard form, which works in the sun's ZENITH angle:
                // cos(zenith) is sin(altitude) and sin(zenith) is
                // cos(altitude). Written with those two the wrong way round
                // it is exactly right at 45°, where they are equal, and
                // silently inverts the sun's height at every other angle —
                // a bug nobody would ever catch by looking at a hillside.
                val v = sinAlt * cos(slope) + cosAlt * sin(slope) * cos(az - aspect)
                out[r * g.cols + c] = ((v.coerceIn(0.0, 1.0)) * 255).toInt()
            }
        }
        return out
    }

    /**
     * A local relief model: the terrain with the hillside taken out of it,
     * leaving what sits *on* the slope.
     *
     * Plain hillshade lights the landscape, and on a steep Pennine valley
     * side the landscape is all you see — a thirty-centimetre path is a
     * rounding error against a two-hundred-metre hill. Subtracting a
     * smoothed copy of the terrain removes the hill and leaves the path,
     * the terrace, the holloway. This is the upgrade that makes the feature
     * worth having on exactly the ground he walks.
     *
     * [radius] is in cells: roughly the size of the features to keep.
     *
     * [keep] leaves that fraction of the landscape in. At zero the result is
     * a pure relief model, which finds everything and looks like grey noise
     * — no hills, no valleys, nothing a person can navigate by. A little of
     * the hillside back and it reads as countryside *with* the small things
     * showing, which is the picture actually worth putting on a map.
     */
    fun localRelief(g: Grid, radius: Int = 12, keep: Double = 0.0): Grid {
        val smooth = boxBlur(g, radius)
        val f = (1.0 - keep).toFloat()
        val out = FloatArray(g.heights.size)
        for (i in g.heights.indices) {
            val a = g.heights[i]
            val b = smooth[i]
            out[i] = if (a.isNaN() || b.isNaN()) Float.NaN else a - f * b
        }
        return Grid(g.west, g.south, g.cellSize, g.cols, g.rows, out)
    }

    /**
     * Mean over a square window, NaN-aware, as two one-dimensional passes —
     * a 12-cell radius done naively is 625 reads per cell over twenty-five
     * million cells, which is not a thing a phone does while he waits.
     */
    private fun boxBlur(g: Grid, radius: Int): FloatArray {
        val w = g.cols
        val h = g.rows
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        // Horizontal.
        for (r in 0 until h) {
            var sum = 0.0
            var n = 0
            for (c in -radius..radius) {
                val v = g.at(r, c)
                if (!v.isNaN()) { sum += v; n++ }
            }
            for (c in 0 until w) {
                tmp[r * w + c] = if (n > 0) (sum / n).toFloat() else Float.NaN
                val out0 = g.at(r, c - radius)
                if (!out0.isNaN()) { sum -= out0; n-- }
                val in0 = g.at(r, c + radius + 1)
                if (!in0.isNaN()) { sum += in0; n++ }
            }
        }
        // Vertical, over the horizontal result.
        fun t(r: Int, c: Int): Float =
            if (r < 0 || r >= h || c < 0 || c >= w) Float.NaN else tmp[r * w + c]
        for (c in 0 until w) {
            var sum = 0.0
            var n = 0
            for (r in -radius..radius) {
                val v = t(r, c)
                if (!v.isNaN()) { sum += v; n++ }
            }
            for (r in 0 until h) {
                out[r * w + c] = if (n > 0) (sum / n).toFloat() else Float.NaN
                val out0 = t(r - radius, c)
                if (!out0.isNaN()) { sum -= out0; n-- }
                val in0 = t(r + radius + 1, c)
                if (!in0.isNaN()) { sum += in0; n++ }
            }
        }
        return out
    }

    /**
     * How much this square rises and falls along a line — the profile a
     * route walks. Useful on its own, and the reason the grid keeps its
     * heights rather than only a picture of them.
     */
    fun profile(g: Grid, line: List<En>): DoubleArray =
        DoubleArray(line.size) { g.heightAt(line[it]).toDouble() }

    /** Straight-line length of a line in grid metres, for a profile's x axis. */
    fun along(line: List<En>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) {
            out[i] = out[i - 1] + hypot(line[i].e - line[i - 1].e, line[i].n - line[i - 1].n)
        }
        return out
    }
}
