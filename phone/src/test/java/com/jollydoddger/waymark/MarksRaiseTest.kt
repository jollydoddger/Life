package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Mark
import com.jollydoddger.waymark.shared.Marks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise the automatic flags rest on: **a flag he tapped is never
 * removed, renumbered or crowded out by one the app raised.**
 *
 * There are five slots. A path check that found seven doubtful stretches
 * must not evict the summit he flagged last week to make room — his are
 * the point of the feature and the app's are a convenience, and the day
 * that inverts is the day he stops trusting either.
 */
class MarksRaiseTest {

    private fun his(n: Int) = Mark(n, 400_000.0 + n, 300_000.0, n * 100.0)
    private fun found(n: Int) = (1..n).map { Triple(500_000.0 + it, 300_000.0, it * 1000.0) }
    private val why: (Int) -> String = { "${(it + 1) * 100} m with no recorded tracks" }

    @Test fun `his own flags all survive and keep their numbers`() {
        val before = listOf(his(1), his(2), his(3))
        val after = Marks.raised(before, found(5), why)
        for (m in before) {
            val kept = after.firstOrNull { it.number == m.number }
            assertTrue("mark ${m.number} was lost", kept != null)
            assertEquals(m.alongM, kept!!.alongM, 0.0)
            assertTrue("his mark must stay his", !kept.automatic)
        }
    }

    @Test fun `the budget is five, his first`() {
        // Three of his leaves room for two.
        val after = Marks.raised(listOf(his(1), his(2), his(3)), found(7), why)
        assertEquals(Marks.MAX, after.size)
        assertEquals(3, after.count { !it.automatic })
        assertEquals(2, after.count { it.automatic })
    }

    @Test fun `with five of his own, nothing is raised and nothing is lost`() {
        val before = (1..5).map { his(it) }
        val after = Marks.raised(before, found(4), why)
        assertEquals(5, after.size)
        assertTrue("not one of his may be evicted", after.none { it.automatic })
    }

    @Test fun `raising again replaces the app's flags rather than stacking them`() {
        // The check is re-run every time a route is set. Appending would
        // fill the route with duplicates of the same doubt within a walk.
        val once = Marks.raised(listOf(his(1)), found(3), why)
        val twice = Marks.raised(once, found(3), why)
        assertEquals(once.size, twice.size)
        assertEquals(1, twice.count { !it.automatic })
        assertEquals(4 - 1, twice.count { it.automatic })
    }

    @Test fun `numbers never collide`() {
        val after = Marks.raised(listOf(his(2), his(5)), found(3), why)
        assertEquals(after.size, after.map { it.number }.toSet().size)
    }

    @Test fun `a raised flag carries the reason it exists`() {
        val after = Marks.raised(emptyList(), found(2), why)
        assertEquals("100 m with no recorded tracks", after[0].why)
        assertTrue(after[0].automatic)
        assertTrue("a tapped flag has no reason to give", !his(1).automatic)
    }

    @Test fun `nothing found leaves the route exactly as it was`() {
        val before = listOf(his(1), his(2))
        val after = Marks.raised(before, emptyList(), why)
        assertEquals(2, after.size)
        assertTrue(after.none { it.automatic })
    }
}
