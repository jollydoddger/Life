package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Joining an official route where it passes, rather than where it starts.
 *
 * All synthetic: a "trail" is a straight line east, which is enough to hold
 * every decision that matters — where the section starts, which way it
 * heads, how long it comes out, and what it is called.
 */
class SectionsTest {

    /** A straight run east from the origin, a point every 100 m. */
    private fun trail(metres: Int) =
        (0..metres / 100).map { En(it * 100.0, 0.0) }

    private fun walk(
        name: String,
        lines: List<List<En>>,
        closestM: Double,
        lengthM: Double = lines.sumOf { Geom.length(it) },
    ) = RouteFinder.FoundWalk(name, "OSM", lines, closestM, lengthM, uri = "content://whole-thing")

    // --- stitching ---------------------------------------------------------

    @Test fun `members meeting end to start become one run`() {
        val a = listOf(En(0.0, 0.0), En(100.0, 0.0))
        val b = listOf(En(100.0, 0.0), En(200.0, 0.0))
        val chains = Sections.chains(listOf(a, b))
        assertEquals(1, chains.size)
        assertEquals(3, chains[0].size)
    }

    @Test fun `a member drawn backwards is turned round rather than dropped`() {
        // OSM members arrive pointing whichever way they were drawn. This
        // is the case that makes "the next point" mean something.
        val a = listOf(En(0.0, 0.0), En(100.0, 0.0))
        val reversed = listOf(En(200.0, 0.0), En(100.0, 0.0))
        val chains = Sections.chains(listOf(a, reversed))
        assertEquals(1, chains.size)
        assertEquals(200.0, Geom.length(chains[0]), 0.001)
    }

    @Test fun `a genuine gap stays a gap`() {
        // Two halves of a route a kilometre apart are two runs. Welding
        // them would invent a kilometre of walking that is not there.
        val a = listOf(En(0.0, 0.0), En(100.0, 0.0))
        val far = listOf(En(1100.0, 0.0), En(1200.0, 0.0))
        assertEquals(2, Sections.chains(listOf(a, far)).size)
    }

    @Test fun `the longest run comes first`() {
        val short = listOf(En(0.0, 0.0), En(50.0, 0.0))
        val long = listOf(En(5000.0, 0.0), En(9000.0, 0.0))
        assertEquals(4000.0, Geom.length(Sections.chains(listOf(short, long))[0]), 0.001)
    }

    // --- cutting the section ------------------------------------------------

    @Test fun `the run starts where the route passes closest`() {
        // Standing beside the 3 km mark of a 10 km trail.
        val run = Sections.runFrom(trail(10_000), En(3000.0, 40.0), 2_000.0)
        assertEquals(3000.0, run.first().e, 1.0)
    }

    @Test fun `the run is the length asked, not the next vertex along`() {
        // 1550 m falls in the middle of a 100 m step; an offer of "3.1 km"
        // that is really 3.2 is a length nobody can plan a day around.
        val run = Sections.runFrom(trail(10_000), En(0.0, 0.0), 1_550.0)
        assertEquals(1_550.0, Geom.length(run), 0.001)
    }

    @Test fun `it heads the way with route left on it`() {
        // Standing 500 m from the western end of a 10 km trail and asking
        // for 4 km: west runs out, so it must go east.
        val run = Sections.runFrom(trail(10_000), En(500.0, 0.0), 4_000.0)
        assertTrue("must head east", run.last().e > run.first().e)
        assertEquals(4_000.0, Geom.length(run), 0.001)
    }

    @Test fun `a route too short to give the length asked gives what it has`() {
        val run = Sections.runFrom(trail(800), En(0.0, 0.0), 5_000.0)
        assertEquals(800.0, Geom.length(run), 0.001)
    }

    // --- what gets offered ---------------------------------------------------

    @Test fun `a long trail passing nearby becomes a walk of the right length`() {
        val w = walk("Calderdale Way", listOf(trail(50_000)), closestM = 300.0)
        val out = Sections.near(listOf(w), En(20_000.0, 300.0), 8_000.0, 15_000.0, withinM = 500.0)
        assertEquals(1, out.size)
        // Out and back: half the middle of 8-15 km each way.
        assertEquals(11_500.0, out[0].lengthM, 1.0)
        assertTrue("named for the route it is", out[0].name.startsWith("Calderdale Way"))
        assertTrue("and for what it is", out[0].name.contains("there and back"))
    }

    @Test fun `the section really does come home`() {
        val w = walk("Pennine Way", listOf(trail(50_000)), closestM = 100.0)
        val out = Sections.near(listOf(w), En(9_000.0, 0.0), 4_000.0, 6_000.0, withinM = 500.0)
        val pts = out[0].routePoints()
        assertEquals(pts.first(), pts.last())
    }

    @Test fun `a walk that fits whole is left alone`() {
        // Sectioning one that already matches would be the same walk,
        // shortened, for no reason — and it is offered whole elsewhere.
        val w = walk("A 10 km circuit", listOf(trail(10_000)), closestM = 100.0)
        assertEquals(0, Sections.near(listOf(w), En(0.0, 0.0), 8_000.0, 15_000.0, 500.0).size)
    }

    @Test fun `a route that passes too far off is not offered as being from here`() {
        // The load-bearing one. The search widens to 12 km when nothing is
        // on the doorstep; cutting a section of something that passes 12 km
        // away and calling it "from here" is a lie about where the walk
        // starts.
        val w = walk("Some distant trail", listOf(trail(50_000)), closestM = 9_000.0)
        assertEquals(0, Sections.near(listOf(w), En(0.0, 9_000.0), 8_000.0, 15_000.0, 500.0).size)
    }

    @Test fun `a trail that runs out is not padded into an answer`() {
        // Only 2 km of route exists near him but he asked for 8 to 15.
        // Offering 4 km would be answering a different question.
        val w = walk("A stub", listOf(trail(2_000)), closestM = 50.0, lengthM = 60_000.0)
        assertEquals(0, Sections.near(listOf(w), En(0.0, 0.0), 8_000.0, 15_000.0, 500.0).size)
    }

    @Test fun `the section drops the document behind the whole route`() {
        // Re-reading the source on adoption would hand back all fifty
        // kilometres and call it this.
        val w = walk("Calderdale Way", listOf(trail(50_000)), closestM = 300.0)
        val out = Sections.near(listOf(w), En(20_000.0, 300.0), 8_000.0, 15_000.0, 500.0)
        assertNull(out[0].uri)
    }

    @Test fun `the join distance reported is the join, not the old one`() {
        val w = walk("Calderdale Way", listOf(trail(50_000)), closestM = 300.0)
        val out = Sections.near(listOf(w), En(20_000.0, 300.0), 8_000.0, 15_000.0, 500.0)
        assertEquals(300.0, out[0].closestM, 1.0)
    }
}
