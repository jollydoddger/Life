package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things about terrain rasters that go quietly wrong: which way up
 * the rows are, and which way the light comes from. Neither is something
 * anyone would catch by glancing at a hillside on a phone — a picture lit
 * from the wrong side simply looks like different countryside, and a grid
 * read upside down puts the path on the wrong slope while looking perfectly
 * plausible. So both are pinned here.
 */
class TerrainTest {

    /** An Esri ASCII grid, the shape the Environment Agency published
     *  before 2019. Row 0 is the NORTH row. */
    private val asc = """
        ncols 3
        nrows 2
        xllcorner 400000
        yllcorner 300000
        cellsize 10
        NODATA_value -9999
        10 11 12
        20 -9999 22
    """.trimIndent()

    private fun grid() = Terrain.parseAsc(asc.byteInputStream())

    @Test fun `the header is read and the corner is the corner`() {
        val g = grid()
        assertEquals(3, g.cols)
        assertEquals(2, g.rows)
        assertEquals(400_000.0, g.west, 0.0)
        assertEquals(300_000.0, g.south, 0.0)
        assertEquals(10.0, g.cellSize, 0.0)
        assertEquals(400_030.0, g.east, 0.0)
        assertEquals(300_020.0, g.north, 0.0)
    }

    @Test fun `row zero is the north row, not the south one`() {
        // The single most likely thing to be upside down. The first data
        // row is the TOP of the square, so it must be found at the northern
        // edge, not the southern one.
        val g = grid()
        assertEquals(10.0f, g.at(0, 0), 0.0f)
        assertEquals(20.0f, g.at(1, 0), 0.0f)
        // Just inside the north-west corner.
        assertEquals(10.0f, g.heightAt(En(400_005.0, 300_015.0)), 0.0f)
        // Just inside the south-west corner.
        assertEquals(20.0f, g.heightAt(En(400_005.0, 300_005.0)), 0.0f)
    }

    @Test fun `a no-data cell is nothing, never a height of minus nine thousand`() {
        // Left as -9999 it would shade as a cliff round every gap in the
        // survey, which reads as real terrain and is not.
        assertTrue(grid().at(1, 1).isNaN())
    }

    @Test fun `a centre-referenced corner is half a cell inside the real one`() {
        val g = Terrain.parseAsc(
            """
            ncols 2
            nrows 1
            xllcenter 400005
            yllcenter 300005
            cellsize 10
            NODATA_value -9999
            1 2
            """.trimIndent().byteInputStream(),
        )
        assertEquals(400_000.0, g.west, 1e-9)
        assertEquals(300_000.0, g.south, 1e-9)
    }

    @Test fun `off the edge of the square is nothing, not the nearest cell`() {
        val g = grid()
        assertTrue(g.heightAt(En(399_999.0, 300_005.0)).isNaN())
        assertTrue(En(400_015.0, 300_015.0) in g)
        assertTrue(En(400_035.0, 300_015.0) !in g)
    }

    // --- hillshade -----------------------------------------------------------

    /** A plane tilting down towards [towardsNw] or away from it. */
    private fun slope(towardsNw: Boolean): Terrain.Grid {
        val n = 9
        val h = FloatArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            // Rows run north→south, columns west→east. Rising to the
            // south-east means the surface faces north-west.
            val rise = (r + c).toFloat()
            h[r * n + c] = if (towardsNw) rise else -rise
        }
        return Terrain.Grid(0.0, 0.0, 1.0, n, n, h)
    }

    @Test fun `the light comes from the north-west`() {
        // Lit from the south, hills read as hollows — an illusion strong
        // enough to survive being told about it, so the convention matters.
        val facingNw = Terrain.hillshade(slope(true))
        val facingSe = Terrain.hillshade(slope(false))
        val mid = 4 * 9 + 4
        assertTrue(
            "a north-west face (${facingNw[mid]}) must be brighter than a south-east one (${facingSe[mid]})",
            facingNw[mid] > facingSe[mid],
        )
    }

    @Test fun `a low sun rakes a facing slope harder than a high one`() {
        // The test the direction one cannot do. Written with sin and cos of
        // the sun's altitude the wrong way round, the shading is exactly
        // right at 45° — where they are equal — and has the sun's height
        // inverted everywhere else. Only an altitude either side of 45°
        // can tell the two apart, and nobody would ever see it by eye.
        val g = slope(true) // faces north-west, into the light
        val mid = 4 * 9 + 4
        val low = Terrain.hillshade(g, altitudeDeg = 30.0)[mid]
        val high = Terrain.hillshade(g, altitudeDeg = 60.0)[mid]
        assertTrue(
            "a 30° sun ($low) should light a facing slope more than a 60° one ($high)",
            low > high,
        )
    }

    @Test fun `flat ground is evenly lit and nothing stands out`() {
        val n = 5
        val g = Terrain.Grid(0.0, 0.0, 1.0, n, n, FloatArray(n * n) { 100f })
        val shade = Terrain.hillshade(g)
        val first = shade[0]
        assertTrue(shade.all { it == first })
        assertTrue("flat ground is lit, not black", first > 0)
    }

    @Test fun `a gap in the survey is transparent, not black`() {
        val g = Terrain.Grid(0.0, 0.0, 1.0, 2, 1, floatArrayOf(50f, Float.NaN))
        val shade = Terrain.hillshade(g)
        assertTrue(shade[0] >= 0)
        assertEquals(-1, shade[1])
    }

    // --- local relief --------------------------------------------------------

    @Test fun `subtracting the hillside leaves the path on it`() {
        // A steep even hillside with a narrow groove cut across it — which
        // is what a worn path is. Plain height is dominated by the hill;
        // after the hillside is taken out, the groove is what remains.
        val n = 61
        val h = FloatArray(n * n)
        for (r in 0 until n) for (c in 0 until n) {
            var v = r * 2.0f // a steep slope, 2 m per cell
            if (c == 30) v -= 0.4f // a 40 cm groove running down it
            h[r * n + c] = v
        }
        val g = Terrain.Grid(0.0, 0.0, 1.0, n, n, h)
        val relief = Terrain.localRelief(g, radius = 8)
        val mid = 30
        val onPath = relief.at(mid, 30)
        val offPath = relief.at(mid, 20)
        assertTrue("the groove should read as a dip, got $onPath", onPath < -0.2f)
        assertTrue("open hillside should read as flat, got $offPath", kotlin.math.abs(offPath) < 0.1f)
        assertTrue("the groove must stand out from the slope", onPath < offPath - 0.2f)
    }

    @Test fun `local relief keeps the square where it was`() {
        val g = grid()
        val r = Terrain.localRelief(g, radius = 1)
        assertEquals(g.west, r.west, 0.0)
        assertEquals(g.south, r.south, 0.0)
        assertEquals(g.cols, r.cols)
        assertEquals(g.rows, r.rows)
    }

    @Test fun `a profile reads heights along a line, and its distances`() {
        val g = grid()
        val line = listOf(En(400_005.0, 300_015.0), En(400_015.0, 300_015.0))
        val p = Terrain.profile(g, line)
        assertEquals(10.0, p[0], 0.0)
        assertEquals(11.0, p[1], 0.0)
        val d = Terrain.along(line)
        assertEquals(0.0, d[0], 0.0)
        assertEquals(10.0, d[1], 1e-9)
    }
}
