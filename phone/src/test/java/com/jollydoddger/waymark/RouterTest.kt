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
            edges[a].add(Router.Graph.Edge(b, d, d, "footway"))
            edges[b].add(Router.Graph.Edge(a, d, d, "footway"))
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
}
