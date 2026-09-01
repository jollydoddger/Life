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

    @Test
    fun `a loaded route is editable, not two handles round a frozen line`() {
        // The bug he reported as "only adding to a gpx and not editing it":
        // load made a start and an end with the whole route as one leg, so
        // every point was drawn and none of the shape could be touched.
        val real = (0..200).map { En(it * 50.0, 0.0) }
        val e = editor()
        e.load(real, maxAnchors = 12)
        assertTrue("needs handles along it, not just two", e.count() >= 8)
        assertEquals("and it is still the same walk", real, e.line())
    }

    @Test
    fun `handles are spread along the ground, not bunched where points crowd`() {
        // A watch GPX has points packed where the walker dawdled. Splitting
        // by index puts every handle in the lay-by where he had lunch.
        val dawdle = (0..100).map { En(it * 0.5, 0.0) }          // 50 m, 101 points
        val march = (1..40).map { En(50.0 + it * 100.0, 0.0) }   // 4 km, 40 points
        val e = editor()
        e.load(dawdle + march, maxAnchors = 9)
        val xs = e.anchorPoints().map { it.e }
        assertTrue("handles must reach the far end", xs.last() > 3_000)
        val beyondDawdle = xs.count { it > 200 }
        assertTrue("most handles belong on the long stretch, got $xs", beyondDawdle >= 5)
    }

    @Test
    fun `editing a loaded route re-routes only the leg that changed`() {
        val real = (0..100).map { En(it * 100.0, 0.0) }
        var legCalls = 0
        val e = RouteEdit { a, b, snap -> legCalls++; dogleg(a, b, snap) }
        e.load(real, maxAnchors = 10)
        legCalls = 0
        e.removeAt(4)
        assertTrue("one mend, not a whole re-route: $legCalls", legCalls <= 2)
    }
}
