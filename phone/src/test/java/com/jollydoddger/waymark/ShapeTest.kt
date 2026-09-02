package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

/**
 * What "circular" is allowed to look like.
 *
 * His words after a plan that wandered over itself to make up distance:
 * a loop, a loop on a stem walked out and back, or a figure of eight —
 * and nothing that crosses or overlaps itself anywhere else. The
 * judgement is a function of the node walk, so it is held here to
 * walks written by hand; then the search itself is run on a plain grid,
 * where clean circuits exist in abundance, and every answer it offers has
 * to be one.
 */
class ShapeTest {

    @Before fun pinTheSpin() {
        var i = 0
        Router.spinSource = { (i++) * 2.399963229728653 }
    }

    @After fun unpinTheSpin() {
        Router.spinSource = { Math.random() * 2 * Math.PI }
    }

    @Test fun `a plain loop is clean`() {
        val sh = Router.shape(listOf(0, 1, 2, 3, 4, 0))
        assertEquals(0, sh.stemEdges)
        assertEquals(0, sh.revisits)
        assertTrue(sh.clean)
    }

    @Test fun `a lollipop's stem is the return leg to the start, and is allowed`() {
        // Out 0-1-2, round 2-3-4-5-2, home 2-1-0.
        val sh = Router.shape(listOf(0, 1, 2, 3, 4, 5, 2, 1, 0))
        assertEquals(2, sh.stemEdges)
        assertEquals(0, sh.revisits)
        assertTrue(!sh.reusedInCore)
        assertTrue(sh.clean)
    }

    @Test fun `a figure of eight meets itself once and is allowed`() {
        // Two lobes through junction 2.
        val sh = Router.shape(listOf(0, 1, 2, 3, 4, 2, 5, 6, 0))
        assertEquals(1, sh.revisits)
        assertTrue(sh.clean)
    }

    @Test fun `a walk that crosses itself twice is a tangle`() {
        val sh = Router.shape(listOf(0, 1, 2, 3, 1, 4, 5, 3, 6, 0))
        assertEquals(2, sh.revisits)
        assertTrue(!sh.clean)
    }

    @Test fun `doubling back anywhere but the stem is a tangle`() {
        // 0-1-2-3, then back 3-2 and on 2-4-0: the 2-3 edge walked twice
        // beyond the start, which is distance made up rather than walked.
        val sh = Router.shape(listOf(0, 1, 2, 3, 2, 4, 0))
        assertTrue(sh.reusedInCore)
        assertTrue(!sh.clean)
    }

    @Test fun `a stem that goes out and straight back is all stem`() {
        val sh = Router.shape(listOf(0, 1, 2, 1, 0))
        assertEquals(2, sh.stemEdges)
        assertEquals(0, sh.revisits)
        assertTrue(sh.clean)
    }

    // --- the search on ground where clean circuits exist ---------------------

    private val step = 250.0
    private val side = 9

    private fun grid(): Router.Graph {
        val nodes = ArrayList<En>()
        val edges = ArrayList<ArrayList<Router.Graph.Edge>>()
        fun add(p: En): Int { nodes.add(p); edges.add(ArrayList()); return nodes.size - 1 }
        fun link(a: Int, b: Int) {
            val d = hypot(nodes[a].e - nodes[b].e, nodes[a].n - nodes[b].n)
            edges[a].add(Router.Graph.Edge(b, d, d, "footway", false))
            edges[b].add(Router.Graph.Edge(a, d, d, "footway", false))
        }
        for (y in 0 until side) for (x in 0 until side) add(En(x * step, y * step))
        fun id(x: Int, y: Int) = y * side + x
        for (y in 0 until side) for (x in 0 until side) {
            if (x + 1 < side) link(id(x, y), id(x + 1, y))
            if (y + 1 < side) link(id(x, y), id(x, y + 1))
        }
        return Router.Graph(nodes, edges)
    }

    @Test fun `every circuit offered is a loop, a lollipop or a figure of eight`() {
        val g = grid()
        val found = Router.loops(g, En(4 * step, 4 * step), 5_000.0, wanted = 4)
        assertTrue("nothing planned on a full grid", found.isNotEmpty())
        for (p in found) {
            assertTrue(
                "offered a walk that meets itself ${p.revisits} times: ${p.metres} m",
                p.revisits <= 1,
            )
            assertTrue("offered a walk that doubles back: ${p.repeatFraction}", p.repeatFraction < 0.1)
        }
    }
}
