package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * "It snaps to roads but not footpaths."
 *
 * The network below is the reason, drawn to scale. A lane runs north–south
 * with a node every fifty metres, the way OpenStreetMap gives a road a node
 * at every junction and bend. A field footpath runs parallel forty metres to
 * the west with nodes only at its two ends, four hundred metres apart —
 * which is exactly how a right of way across two fields is mapped.
 *
 * Tap squarely on the footpath, halfway along it. It is forty metres from
 * the footpath and eighty from the lane, so there is no ambiguity about what
 * was meant. But the nearest footpath *node* is two hundred metres away and
 * the nearest lane node is eighty — so asking for the nearest node hands
 * back the lane, every time, on ground where the footpath is unmistakably
 * closer.
 */
class SnapTest {

    private fun graph(): Router.Graph {
        val nodes = ArrayList<En>()
        val edges = ArrayList<ArrayList<Router.Graph.Edge>>()
        fun add(p: En): Int {
            nodes.add(p)
            edges.add(ArrayList())
            return nodes.size - 1
        }
        fun link(a: Int, b: Int, kind: String) {
            val d = hypot(nodes[a].e - nodes[b].e, nodes[a].n - nodes[b].n)
            edges[a].add(Router.Graph.Edge(b, d, d, kind, kind == "secondary"))
            edges[b].add(Router.Graph.Edge(a, d, d, kind, kind == "secondary"))
        }
        // The lane: nine nodes, fifty metres apart, at e = 1000.
        val lane = (0..8).map { add(En(1000.0, it * 50.0)) }
        for (i in 0 until lane.size - 1) link(lane[i], lane[i + 1], "secondary")
        // The footpath: two nodes, four hundred metres apart, at e = 960.
        val a = add(En(960.0, 0.0))
        val b = add(En(960.0, 400.0))
        link(a, b, "footway")
        return Router.Graph(nodes, edges)
    }

    /** The tap: on the footpath, halfway up, well clear of the lane. */
    private val tap = En(960.0, 200.0)

    @Test fun `nearest node picks the lane, which is the bug`() {
        val g = graph()
        val i = g.nearest(tap)
        assertNotNull(i)
        // Not an assertion about what should happen — a record of why
        // nearest-node cannot be the thing the editor uses.
        assertEquals("the lane", 1000.0, g.nodes[i!!].e, 0.1)
    }

    @Test fun `nearest way picks the footpath`() {
        val g = graph()
        val on = g.onWay(tap, Router.TAP_SNAP_M)
        assertNotNull("the tap is on the footpath", on)
        assertEquals("a footpath node", 960.0, g.nodes[on!!.node].e, 0.1)
    }

    @Test fun `the handle lands on the way, not at its far end`() {
        // The node routed from may be two hundred metres off, but the point
        // he sees must be where he put it — on the line.
        val g = graph()
        val on = g.onWay(tap, Router.TAP_SNAP_M)!!
        assertEquals(960.0, on.at.e, 0.1)
        assertEquals(200.0, on.at.n, 0.1)
    }

    @Test fun `the end nearer the tap is the one routed from`() {
        val g = graph()
        // Three quarters of the way up: the northern end is nearer.
        val on = g.onWay(En(960.0, 300.0), Router.TAP_SNAP_M)!!
        assertEquals(400.0, g.nodes[on.node].n, 0.1)
    }

    @Test fun `open ground snaps to nothing rather than to something far off`() {
        // Out in a field, hundreds of metres from any way. Claiming a path
        // here would be inventing one.
        val g = graph()
        assertNull(g.onWay(En(300.0, 200.0), Router.TAP_SNAP_M))
    }

    @Test fun `a tap on the lane still gets the lane`() {
        // The fix must not overcorrect: where the road genuinely is the
        // nearest way, it is still the answer.
        val g = graph()
        val on = g.onWay(En(1002.0, 175.0), Router.TAP_SNAP_M)
        assertNotNull(on)
        assertEquals(1000.0, g.nodes[on!!.node].e, 0.1)
    }

    @Test fun `a way whose ends are both out of reach is still found`() {
        // The whole difficulty: the segment wanted can have both its ends
        // far outside the tolerance the tap is judged by.
        val g = graph()
        val on = g.onWay(tap, Router.TAP_SNAP_M)!!
        val d = hypot(g.nodes[on.node].e - tap.e, g.nodes[on.node].n - tap.n)
        assertTrue("the node routed from is beyond the tap tolerance", d > Router.TAP_SNAP_M)
    }

    // --- between(): taps on one long segment -------------------------------

    @Test fun `two taps on the same stretch of footpath are a leg along it`() {
        // Both resolve to the same node, the search collapses, and this
        // used to come back null — a leg lying exactly on the path,
        // reported as "no path between those points".
        val g = graph()
        val leg = Router.between(g, En(960.0, 50.0), En(960.0, 150.0))
        assertNotNull(leg)
        assertEquals(2, leg!!.points.size)
        assertEquals(100.0, leg.metres, 0.001)
        assertEquals("it is the path, and says so", 100.0, leg.byGroup["path"] ?: 0.0, 0.001)
    }

    @Test fun `taps near opposite ends of one segment stay between the taps`() {
        // Resolved to different ends, the leg used to run out to one end
        // of the segment and back past both taps to the other.
        val g = graph()
        val leg = Router.between(g, En(960.0, 100.0), En(960.0, 300.0))
        assertNotNull(leg)
        assertEquals(2, leg!!.points.size)
        assertEquals(100.0, leg.points.first().n, 0.001)
        assertEquals(300.0, leg.points.last().n, 0.001)
        assertEquals(200.0, leg.metres, 0.001)
    }

    @Test fun `a leg does not set off backwards to the end of the tapped segment`() {
        // Taps mid-segment on the lane: the routed walk starts at a node
        // behind the first tap and ends at one past the second. Both
        // outer vertices are doubling-back, and are trimmed.
        val g = graph()
        val leg = Router.between(g, En(1000.0, 120.0), En(1000.0, 330.0))
        assertNotNull(leg)
        val ns = leg!!.points.map { it.n }
        assertTrue("no vertex behind the first tap: $ns", ns.min() >= 120.0 - 0.001)
        assertTrue("no vertex past the second tap: $ns", ns.max() <= 330.0 + 0.001)
    }

    // --- what may honestly be called a snapped leg -------------------------

    @Test fun `two taps either side of one junction are not a path`() {
        // The regression this replaces: both taps resolve to the same node
        // — which two taps on open ground either side of a junction do just
        // as well as two taps on one footpath — and the leg came back as a
        // straight line across the field, marked snapped. An honest "this
        // leg is straight" had become a claim to have found a path, which
        // is the worst thing this app can do.
        val g = graph()
        // Well off both ways, either side of the lane's node at n=200.
        val leg = Router.between(g, En(700.0, 190.0), En(700.0, 210.0))
        assertNull("no way under them, so no leg", leg)
    }

    @Test fun `a leg starts at one tap and ends at the other`() {
        // The contract that makes legs joinable: the assistant stitches
        // consecutive legs with drop(1), which is only right if each starts
        // exactly where the last ended. Bare graph nodes left a gap at
        // every joint and then deleted a vertex trying to close it.
        val g = graph()
        // On the lane, where a snapped handle actually sits.
        val from = En(1000.0, 120.0)
        val to = En(1000.0, 330.0)
        val leg = Router.between(g, from, to)
        assertNotNull(leg)
        assertEquals(from, leg!!.points.first())
        assertEquals(to, leg.points.last())
    }

    @Test fun `a leg over one intermediate junction keeps it`() {
        // The three-point case: both handles sit on the leg's own outer
        // segments, so both outer vertices are trimmed — which used to
        // leave one vertex, be rejected, and return the walk untrimmed.
        // The junction between them is real and must survive.
        val g = graph()
        val leg = Router.between(g, En(1000.0, 160.0), En(1000.0, 240.0))!!
        assertEquals(En(1000.0, 160.0), leg.points.first())
        assertEquals(En(1000.0, 240.0), leg.points.last())
        assertTrue("the junction between them is kept", leg.points.size >= 3)
        assertTrue("and it is a real one", leg.points.any { kotlin.math.abs(it.n - 200.0) < 0.001 })
    }
}
