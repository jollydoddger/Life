package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index answers "a walk near me", so the ordering is the whole point:
 * a walk that is genuinely close beats one whose name merely contains the
 * word. Search and rendering are pure; the JSON store needs a Context and
 * is not tested here, which is said rather than papered over.
 */
class WalkIndexTest {

    private fun walk(name: String, area: String, lat: Double = Double.NaN, lon: Double = Double.NaN) =
        IndexedWalk("example.com", name, area, "https://example.com/${name.hashCode()}", lat, lon)

    @Test
    fun `a walk without a position cannot answer a question about the ground`() {
        assertTrue(walk("Rhoscolyn Headland", "Anglesey", 53.24, -4.60).located())
        assertTrue(!walk("Somewhere", "Anglesey").located())
    }

    @Test
    fun `rendering names the walk and gives the page to fetch it from`() {
        val text = walk("Rhoscolyn Headland", "Anglesey", 53.24, -4.60).render()
        assertTrue("names it", "Rhoscolyn Headland" in text)
        assertTrue("says where", "Anglesey" in text)
        assertTrue("and how to get it", "https://example.com/" in text)
    }

    @Test
    fun `an area is left out of the line rather than rendered as empty brackets`() {
        assertTrue("()" !in walk("Some Walk", "").render())
        assertTrue("(Anglesey)" in walk("Some Walk", "Anglesey").render())
    }

    @Test
    fun `distance is measured on the grid, so Anglesey walks are near Anglesey`() {
        // Caergeiliog to Rhoscolyn is a few miles; to Fort William is not.
        val here = Bng.fromWgs84(53.26, -4.55)
        val rhoscolyn = Bng.fromWgs84(53.24, -4.60)
        val fortWilliam = Bng.fromWgs84(56.82, -5.11)
        val near = kotlin.math.hypot(rhoscolyn.e - here.e, rhoscolyn.n - here.n)
        val far = kotlin.math.hypot(fortWilliam.e - here.e, fortWilliam.n - here.n)
        assertTrue("Rhoscolyn is within a few miles: $near m", near < 8_000)
        assertTrue("Fort William is not", far > 300_000)
    }

    @Test
    fun `staleness is a real number of days, not a vague intention`() {
        assertTrue(WalkIndex.STALE_DAYS in 30..730)
    }
}
