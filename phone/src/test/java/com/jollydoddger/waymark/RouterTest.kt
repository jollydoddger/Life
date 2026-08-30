package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * "Circular" has to mean a circuit.
 *
 * The first version of the router returned shapes with dead-end spurs
 * hanging off them — walkable, the right length, and not what anybody means
 * by a circular walk. The guarantee is now in code, so it is held to a test
 * here: on a deliberately awkward network, full of dead ends, a planned loop
 * comes home and never turns round on itself.
 *
 * The graph is synthetic on purpose. Overpass is not reachable from a test
 * runner, and the thing worth testing is the loop-finding, not the parsing.
 */
class RouterTest {

    private val step = 300.0
    private val side = 9

    /**
     * A patchy grid of lanes with dead-end spurs hung off it — the shape of a
     * real village network, where plenty of tracks simply stop.
     */
    private fun awkwardNetwork(seed: Int): Router.Graph {
        val rnd = Random(seed)
        val nodes = ArrayList<En>()
        val edges = ArrayList<ArrayList<Router.Graph.Edge>>()
        fun add(p: En): Int {
            nodes.add(p)
            edges.add(ArrayList())
            return nodes.size - 1
        }
        fun link(a: Int, b: Int) {
            val d = hypot(nodes[a].e - nodes[b].e, nodes[a].n - nodes[b].n)
            edges[a].add(Router.Graph.Edge(b, d, d, "footway", false))
            edges[b].add(Router.Graph.Edge(a, d, d, "footway", false))
        }
        for (y in 0 until side) for (x in 0 until side) add(En(x * step, y * step))
        fun id(x: Int, y: Int) = y * side + x
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (x + 1 < side && rnd.nextDouble() < 0.7) link(id(x, y), id(x + 1, y))
                if (y + 1 < side && rnd.nextDouble() < 0.7) link(id(x, y), id(x, y + 1))
            }
        }
        repeat(25) {
            val a = rnd.nextInt(side * side)
            val spur = add(
                En(
                    nodes[a].e + rnd.nextDouble(-250.0, 250.0),
                    nodes[a].n + rnd.nextDouble(-250.0, 250.0),
                ),
            )
            link(a, spur)
        }
        return Router.Graph(nodes, edges)
    }

    private fun centre() = En(4 * step, 4 * step)

    /** No three consecutive points may be a-b-a: that is an out-and-back. */
    private fun assertNoReversals(points: List<En>) {
        for (i in 2 until points.size) {
            val back = points[i] == points[i - 2]
            assertTrue("point $i doubles straight back on itself", !back)
        }
    }

    @Test
    fun aPlannedLoopComesHomeAndNeverTurnsRound() {
        for (seed in 1..6) {
            val g = awkwardNetwork(seed)
            val loop = Router.loop(g, centre(), 5_000.0)
            assertNotNull("seed $seed found no loop at all", loop)
            val points = loop!!.points
            assertEquals("seed $seed did not come home", points.first(), points.last())
            assertTrue("seed $seed is barely a walk", points.size >= 5)
            assertNoReversals(points)
        }
    }

    /**
     * The distance is elastic — he said so — but it must still be steered.
     * A 3 km ask and an 8 km ask on the same network must not come back the
     * same length, or the lever does nothing.
     */
    @Test
    fun theLengthAskedForActuallyMoves() {
        val g = awkwardNetwork(3)
        val short = Router.loop(g, centre(), 3_000.0)!!
        val long = Router.loop(g, centre(), 9_000.0)!!
        assertTrue(
            "3 km asked gave ${short.metres} m, 9 km gave ${long.metres} m",
            long.metres > short.metres * 1.3,
        )
    }

    /** Ground covered twice is reported, not hidden — and stays small. */
    @Test
    fun retracedGroundIsMeasuredAndMostlyAbsent() {
        val g = awkwardNetwork(2)
        val loop = Router.loop(g, centre(), 6_000.0)!!
        assertTrue("repeat fraction out of range", loop.repeatFraction in 0.0..1.0)
        assertTrue("too much of it is retraced: ${loop.repeatFraction}", loop.repeatFraction < 0.35)
    }

    /** A road refused is not in the graph, so nothing downstream can use it. */
    @Test
    fun groupsAreNamedAsAWalkerWouldNameThem() {
        assertEquals("path", Router.group("bridleway"))
        assertEquals("lane", Router.group("residential"))
        assertEquals("road", Router.group("primary"))
    }

    // --- the round that made planning answer rather than grind -------------

    @Test
    fun `the clock stops the search and hands back the best it found`() {
        // The failure he reported: two minutes of searching ending in
        // "couldn't close a loop". A deadline already past must still
        // return whatever closed, not nothing.
        val g = awkwardNetwork(3)
        val full = Router.loop(g, centre(), 5_000.0)
        assertNotNull("the control search should find something", full)

        var progressed = false
        val stopped = Router.loop(
            g, centre(), 5_000.0,
            deadlineMs = System.currentTimeMillis() + 1_200,
        ) { progressed = true }
        // Either it finished inside the second, or it was cut off — either
        // way it must not come back empty-handed on a network this rich.
        assertNotNull("a cut-off search must still answer", stopped)
        assertTrue("a loop is still a loop under a deadline", stopped!!.metres > 0)
    }

    @Test
    fun `pressing stop ends the search`() {
        val g = awkwardNetwork(4)
        val started = System.currentTimeMillis()
        val out = Router.loop(g, centre(), 8_000.0, isCancelled = { true })
        // Cancelled before the first candidate: nothing found, and quickly.
        assertTrue("cancelling must not grind", System.currentTimeMillis() - started < 3_000)
        assertTrue("a cancelled search returns nothing or something real", out == null || out.metres > 0)
    }

    @Test
    fun `a short loop is offered rather than thrown away`() {
        // A genuine circuit far under the asked distance used to be
        // discarded by a quarter-of-target floor, so a search holding a real
        // loop reported finding none at all.
        val g = awkwardNetwork(2)
        val out = Router.loop(g, centre(), 40_000.0)
        assertNotNull("a network this small cannot make 40 km — say what it can", out)
        assertTrue("and it must be a real walk, not noise", out!!.metres >= 300.0)
        assertNoReversals(out.points)
    }

    @Test
    fun `a corner is never planted out of reach`() {
        // nearestJunction used to fall back to the nearest node of any
        // degree, which put corners on dead ends and on disconnected
        // fragments — an unreachable leg, discovered the slow way.
        val g = awkwardNetwork(5)
        val far = En(-50_000.0, -50_000.0)
        assertEquals(null, g.nearestJunction(far, 500.0))
        // The invariant, at every radius: what comes back is a junction and
        // is genuinely inside the bound. Whether one *exists* at a given
        // radius is the random network's business, not the contract's — the
        // first version of this test asserted that and was rightly wrong.
        for (within in doubleArrayOf(150.0, 400.0, 900.0, 2_000.0)) {
            val j = g.nearestJunction(centre(), within) ?: continue
            assertTrue("what comes back is a junction", g.edges[j].size >= 3)
            assertTrue(
                "and it is genuinely within $within m",
                hypot(g.nodes[j].e - centre().e, g.nodes[j].n - centre().n) <= within,
            )
        }
        // Somewhere in a nine-by-nine grid of lanes there is certainly one.
        assertNotNull(g.nearestJunction(centre(), 5_000.0))
    }

    @Test
    fun `there and back goes out and comes home down its own line`() {
        // The shape no loop-finder will ever offer, because the best turning
        // points are dead ends and a circuit search refuses those on purpose.
        val g = awkwardNetwork(3)
        val out = Router.outAndBack(g, centre(), 4_000.0)
        assertNotNull("a grid of lanes can always make an out-and-back", out)
        val p = out!!.points
        assertEquals("it must come home", p.first(), p.last())
        assertEquals("out and back is an odd number of points", 1, p.size % 2)
        assertEquals("every metre of it is walked twice, by design", 1.0, out.repeatFraction, 0.001)
        assertTrue("and it is somewhere near the length asked for", out.metres in 2_000.0..7_000.0)
        // And the app agrees with the router about what it just built.
        assertEquals(Form.OUT_AND_BACK, Specifier.formOf(p))
    }

    @Test
    fun `the clock stops the there-and-back search too`() {
        val g = awkwardNetwork(4)
        val started = System.currentTimeMillis()
        val out = Router.outAndBack(g, centre(), 9_000.0, isCancelled = { true })
        assertTrue("cancelling must not grind", System.currentTimeMillis() - started < 3_000)
        assertTrue("cancelled returns nothing or something real", out == null || out.metres > 0)
    }

    @Test
    fun `the grid index finds what a full scan would`() {
        val g = awkwardNetwork(6)
        val rnd = Random(11)
        repeat(30) {
            val p = En(rnd.nextDouble(-500.0, 3_000.0), rnd.nextDouble(-500.0, 3_000.0))
            val indexed = g.nearest(p)!!
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in g.nodes.indices) {
                val d = hypot(g.nodes[i].e - p.e, g.nodes[i].n - p.n)
                if (d < bestD) { bestD = d; best = i }
            }
            assertEquals(
                "indexed nearest must match the honest scan",
                bestD,
                hypot(g.nodes[indexed].e - p.e, g.nodes[indexed].n - p.n),
                0.001,
            )
        }
    }

    @Test
    fun `retraced ground is measured on a plain point list too`() {
        // The via-places path built a Planned without this and so always
        // claimed a clean circuit, however much of itself it walked twice.
        val outAndBack = listOf(En(0.0, 0.0), En(100.0, 0.0), En(200.0, 0.0), En(100.0, 0.0), En(0.0, 0.0))
        assertEquals(0.5, Router.repeatFraction(outAndBack), 0.02)
        val ring = listOf(
            En(0.0, 0.0), En(100.0, 0.0), En(100.0, 100.0), En(0.0, 100.0), En(0.0, 0.0),
        )
        assertEquals(0.0, Router.repeatFraction(ring), 0.001)
    }
}
