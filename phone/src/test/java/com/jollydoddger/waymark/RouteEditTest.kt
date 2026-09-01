package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawing a walk by tapping. The routing is handed in, so a fake stands in
 * for the path network and the editing itself is what gets tested — which
 * is the half that ruins a drawing when it is wrong.
 */
class RouteEditTest {

    /** A "router" that goes via a dogleg, so a routed leg is always
     *  distinguishable from a straight one. */
    private fun dogleg(a: En, b: En, snap: RouteEdit.Snap): List<En>? =
        listOf(a, En(a.e, b.n), b)

    private fun editor(leg: (En, En, RouteEdit.Snap) -> List<En>? = ::dogleg) = RouteEdit(leg)

    @Test
    fun `the line follows the routed legs, not the taps`() {
        // The whole point he made: a walk is not straight lines between
        // taps. Two anchors must produce the router's three points.
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 100.0))
        assertEquals(2, e.count())
        assertEquals(listOf(En(0.0, 0.0), En(0.0, 100.0), En(100.0, 100.0)), e.line())
    }

    @Test
    fun `straight mode does not pretend to have found a path`() {
        val e = editor()
        e.snap = RouteEdit.Snap.STRAIGHT
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 100.0))
        assertEquals(listOf(En(0.0, 0.0), En(100.0, 100.0)), e.line())
        // Nothing is claimed as snapped, so nothing warns about it either.
        assertTrue(!e.hasUnsnapped())
    }

    @Test
    fun `a leg with no path is drawn straight and says so`() {
        // Silence here would be the bad kind: a straight line across a field
        // looks exactly like a routed one and would be walked as though it
        // were.
        val e = editor { _, _, _ -> null }
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 100.0))
        assertEquals(listOf(En(0.0, 0.0), En(100.0, 100.0)), e.line())
        assertTrue("must own up to the fallback", e.hasUnsnapped())
    }

    @Test
    fun `deleting a middle point mends the gap rather than leaving a hole`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 0.0))
        e.add(En(200.0, 0.0))
        assertTrue(e.removeAt(1))
        assertEquals(2, e.count())
        // The remaining leg is routed between the survivors, start to end.
        val line = e.line()
        assertEquals(En(0.0, 0.0), line.first())
        assertEquals(En(200.0, 0.0), line.last())
    }

    @Test
    fun `deleting the first point leaves the rest a valid walk`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 0.0))
        e.add(En(200.0, 0.0))
        assertTrue(e.removeAt(0))
        assertEquals(2, e.count())
        // No leg may still be pointing at the anchor that went.
        assertEquals(En(100.0, 0.0), e.line().first())
    }

    @Test
    fun `undo puts back exactly what was there, repeatedly`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 0.0))
        val two = e.line()
        e.add(En(200.0, 0.0))
        assertEquals(3, e.count())
        assertTrue(e.undo())
        assertEquals(2, e.count())
        assertEquals(two, e.line())
        assertTrue(e.undo())
        assertEquals(1, e.count())
        assertTrue(e.undo())
        assertEquals(0, e.count())
        assertTrue("nothing left to undo", !e.canUndo())
        assertTrue(!e.undo())
    }

    @Test
    fun `undo restores a deletion too, not only an addition`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 0.0))
        e.add(En(200.0, 0.0))
        val before = e.line()
        e.removeAt(1)
        assertTrue(e.undo())
        assertEquals(3, e.count())
        assertEquals(before, e.line())
    }

    @Test
    fun `tapping near a point finds it, tapping away from one does not`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(500.0, 0.0))
        assertEquals(1, e.anchorNear(En(510.0, 5.0), 40.0))
        assertEquals(-1, e.anchorNear(En(250.0, 0.0), 40.0))
    }

    @Test
    fun `closing the loop comes home, and needs a walk to close`() {
        val e = editor()
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 0.0))
        assertTrue("two points is not a circuit", !e.closeLoop())
        e.add(En(100.0, 100.0))
        assertTrue(e.closeLoop())
        assertEquals(e.line().first(), e.line().last())
    }

    @Test
    fun `changing what to stick to re-routes the whole walk`() {
        var mode = RouteEdit.Snap.PATHS
        val e = RouteEdit { a, b, snap -> mode = snap; dogleg(a, b, snap) }
        e.add(En(0.0, 0.0))
        e.add(En(100.0, 100.0))
        e.snap = RouteEdit.Snap.ANY
        e.resnapAll()
        assertEquals("the new mode reaches the router", RouteEdit.Snap.ANY, mode)
    }

    @Test
    fun `an imported line is kept as it was drawn, not re-invented`() {
        // Re-routing a real GPX through our own network would hand back a
        // walk the file never described.
        val e = editor()
        val real = listOf(En(0.0, 0.0), En(10.0, 90.0), En(50.0, 50.0), En(100.0, 100.0))
        e.load(real)
        assertEquals(real, e.line())
    }
}
