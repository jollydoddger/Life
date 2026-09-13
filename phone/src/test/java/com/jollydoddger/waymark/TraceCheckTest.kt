package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this feature lives or dies by: **no traces is not no path.**
 *
 * A check that shouts about every stretch OSM happens not to hold an upload
 * for is a check he stops reading by the third walk, and then it is worse
 * than nothing — it will be ignored on the day it is right. So ground that
 * has never been looked up is left out of the sums entirely rather than
 * counted against the route, and the two honest "I don't know" answers get
 * their own words. Same reasoning as Capacity.UNKNOWN in the other app:
 * unknown must never be treated as bad.
 */
class TraceCheckTest {

    /** A straight route running east, [lengthM] long, a point every 500 m. */
    private fun route(lengthM: Double): List<En> =
        (0..(lengthM / 500).toInt()).map { En(400_000.0 + it * 500.0, 400_000.0) }

    /** Trace points every 10 m along the route between two offsets. */
    private fun traces(fromM: Double, toM: Double): FloatArray {
        val out = ArrayList<Float>()
        var d = fromM
        while (d <= toM) {
            out.add((400_000.0 + d).toFloat())
            out.add(400_000f)
            d += 10.0
        }
        return out.toFloatArray()
    }

    /** Everything looked up. */
    private val allKnown: (En) -> Boolean = { true }

    @Test fun `a route with tracks along all of it is reported clean`() {
        val r = TraceCheck.check(
            route(2_000.0), listOf(traces(0.0, 2_000.0)), allKnown,
            cellsWithData = 1,
        )
        assertTrue("no stretches expected, got ${r.stretches.size}", r.stretches.isEmpty())
        assertTrue(r.words(), r.words().contains("people have really walked all of it"))
        assertEquals(1.0, r.coverage, 0.01)
    }

    @Test fun `a bare middle section is found, measured and placed`() {
        // Tracks for the first 800 m and the last 800 m of 2 km.
        val r = TraceCheck.check(
            route(2_000.0),
            listOf(traces(0.0, 800.0), traces(1_200.0, 2_000.0)),
            allKnown,
            cellsWithData = 1,
        )
        assertEquals("one gap expected", 1, r.stretches.size)
        val s = r.stretches.first()
        // The gap is 400 m of route, less the 25 m reach of the traces at
        // each end of it.
        assertEquals(350.0, s.metres, 45.0)
        assertEquals(825.0, s.fromM, 45.0)
        assertTrue(r.words(), r.words().startsWith("Worth a look before you go:"))
    }

    @Test fun `a gap shorter than the minimum run is not worth saying`() {
        // 100 m bare — a bridge, a car park, a field crossed differently
        // every time. Under MIN_RUN_M and deliberately silent.
        val r = TraceCheck.check(
            route(2_000.0),
            listOf(traces(0.0, 900.0), traces(1_000.0, 2_000.0)),
            allKnown,
            cellsWithData = 1,
        )
        assertTrue("a 100 m gap should not be reported", r.stretches.isEmpty())
    }

    @Test fun `ground nobody has looked up is never counted against the route`() {
        // This is the whole point of the file. Tracks exist for the first
        // kilometre; the second kilometre has never been fetched. The
        // second kilometre must not appear as a fault.
        val r = TraceCheck.check(
            route(2_000.0),
            listOf(traces(0.0, 1_000.0)),
            known = { it.e < 401_000.0 },
            cellsMissing = 1, cellsWithData = 1,
        )
        assertTrue("unknown ground must not be reported as unwalked", r.stretches.isEmpty())
        assertEquals("only the looked-up half is checked", 1_000.0, r.checkedM, 60.0)
        assertEquals(2_000.0, r.routeM, 60.0)
        assertEquals(0.5, r.coverage, 0.05)
        assertTrue(r.words(), r.words().contains("not looked up yet"))
    }

    @Test fun `nothing looked up at all is said as ignorance, not as a verdict`() {
        val r = TraceCheck.check(
            route(2_000.0), emptyList(), known = { false },
            cellsMissing = 3,
        )
        assertTrue(r.blind)
        assertTrue(r.words(), r.words().contains("not a verdict on the paths"))
    }

    @Test fun `an area genuinely bare of uploads says so about the archive`() {
        // Cells fetched, and they really do hold nothing. That is a fact
        // about OpenStreetMap, not about the ground, and the words have to
        // carry the difference.
        val r = TraceCheck.check(
            route(2_000.0), emptyList(), allKnown,
            cellsEmpty = 3,
        )
        assertTrue(r.areaBare)
        assertTrue(!r.blind)
        val w = r.words()
        assertTrue(w, w.contains("gap in OpenStreetMap"))
        assertTrue("must not read as a judgement", w.contains("not a judgement on the ground"))
    }

    @Test fun `the route is measured evenly whatever its own point density`() {
        // A sparse import and a dense planner route must give run lengths
        // that mean the same thing.
        val sparse = listOf(En(400_000.0, 400_000.0), En(401_000.0, 400_000.0))
        val dense = (0..1000).map { En(400_000.0 + it, 400_000.0) }
        val a = TraceCheck.resample(sparse)
        val b = TraceCheck.resample(dense)
        assertEquals(a.size.toDouble(), b.size.toDouble(), 2.0)
        for (i in 1 until a.size - 1) {
            val step = a[i].e - a[i - 1].e
            assertEquals(TraceCheck.STEP_M, step, 0.001)
        }
    }

    @Test fun `a route of one point is not a route`() {
        val r = TraceCheck.check(listOf(En(400_000.0, 400_000.0)), emptyList(), allKnown)
        assertEquals(0.0, r.routeM, 0.0)
        assertEquals("No route to check.", r.words())
    }
}
