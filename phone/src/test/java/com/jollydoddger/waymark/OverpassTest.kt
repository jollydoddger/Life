package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reader itself walks `android.util.JsonReader`, which is a stub in a
 * plain JVM unit test — so the streaming half is verified by reading it,
 * not by a test dressed up to look like proof. What *is* tested is the one
 * part carrying a decision: where a geometry breaks.
 *
 * That decision is not cosmetic. `out geom(bbox)` replaces a clipped-out
 * node with a null, and the old tree-parsing code in the router and the
 * rights-of-way overlay skipped those nulls and carried on — welding the
 * two halves of a way together with a straight line across ground that was
 * never fetched. The router would then plan along it and call it a path.
 */
class OverpassTest {

    private fun flat(vararg v: Double) = v

    @Test fun `an unbroken geometry is one run`() {
        val runs = Overpass.runs(flat(53.0, -4.0, 53.1, -4.1, 53.2, -4.2))
        assertEquals(1, runs.size)
        assertEquals(6, runs[0].size)
    }

    @Test fun `a gap splits the way rather than closing over it`() {
        val runs = Overpass.runs(
            flat(
                53.0, -4.0, 53.1, -4.1,
                Double.NaN, Double.NaN,
                53.5, -4.5, 53.6, -4.6,
            ),
        )
        assertEquals(2, runs.size)
        assertEquals(53.1, runs[0][2], 1e-9)
        assertEquals(53.5, runs[1][0], 1e-9)
    }

    @Test fun `a lone point either side of a gap is dropped`() {
        // One coordinate is not a line, and a router asked to walk it
        // would have nothing to walk along.
        val runs = Overpass.runs(
            flat(53.0, -4.0, Double.NaN, Double.NaN, 53.5, -4.5, 53.6, -4.6),
        )
        assertEquals(1, runs.size)
        assertEquals(53.5, runs[0][0], 1e-9)
    }

    @Test fun `gaps at both ends leave the middle intact`() {
        val runs = Overpass.runs(
            flat(
                Double.NaN, Double.NaN,
                53.0, -4.0, 53.1, -4.1,
                Double.NaN, Double.NaN,
            ),
        )
        assertEquals(1, runs.size)
        assertEquals(4, runs[0].size)
    }

    @Test fun `a geometry that is entirely gaps yields nothing`() {
        assertEquals(0, Overpass.runs(flat(Double.NaN, Double.NaN, Double.NaN, Double.NaN)).size)
    }

    @Test fun `an empty geometry yields nothing`() {
        assertEquals(0, Overpass.runs(DoubleArray(0)).size)
    }

    @Test fun `a break is either coordinate being absent`() {
        assertEquals(true, Overpass.isBreak(Double.NaN, -4.0))
        assertEquals(true, Overpass.isBreak(53.0, Double.NaN))
        assertEquals(false, Overpass.isBreak(53.0, -4.0))
    }
}
