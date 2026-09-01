package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a server that gave up from ground that has nothing on it.
 *
 * This is the bug that made the planner useless. Overpass does not fail a
 * slow query with a 5xx — it answers HTTP 200 carrying a `remark` and an
 * empty `elements` array. Read only for elements, that is a well-formed
 * reply saying "there are no paths here", and the app believed it: the
 * empty graph went into the cache with the full asked radius, and every
 * later plan was answered out of memory, without a server being asked
 * again, for the life of the process. Hours of "the planner doesn't get
 * anything", out of a query that was merely slow.
 *
 * `android.util.JsonReader` is a stub under a plain JVM test, so the parse
 * around it cannot run here — which is why the decision itself is a
 * function the parser calls rather than a condition buried inside it.
 * These exercise the real one.
 */
class OverpassRefusalTest {

    private fun refuses(seen: Int, remark: String?): Boolean =
        Overpass.isRefusal(seen, remark)

    @Test fun `a timed-out query is a refusal, not an empty moor`() {
        assertTrue(refuses(0, "runtime error: Query timed out in \"query\" at line 1"))
    }

    @Test fun `genuinely bare ground is an answer`() {
        // Out at sea, or a square of moor with nothing mapped: no remark,
        // no elements. That is a real reply and must not be reported as a
        // server failure — the ground is allowed to be empty.
        assertTrue(!refuses(0, null))
    }

    @Test fun `a remark alongside real data is a warning, not a failure`() {
        // Overpass also remarks about partial results. The data that did
        // arrive is still worth having, and throwing it away would trade
        // one wrong answer for another.
        assertTrue(!refuses(4_000, "runtime error: Query run out of memory"))
    }

    @Test fun `the refusal carries what the server said`() {
        val e = Net.GaveUp("runtime error: Query timed out")
        assertTrue("the remark survives for the message", e.remark.contains("timed out"))
        assertTrue("and reads as a server problem", e.message!!.contains("gave up"))
    }

    @Test fun `a refusal is transport, so the next mirror gets a turn`() {
        // Deliberately an IOException: Net's mirror loop treats those as
        // transport failures and moves on. A server too busy to finish in
        // sixty seconds will be too busy again in another sixty, so the
        // next mirror is the better question — and answering the caller
        // with "no paths here" is not an option at all.
        assertTrue(Net.GaveUp("x") is java.io.IOException)
    }

    @Test fun `an unusable graph is never worth caching`() {
        // The second half of the same bug: even a truthfully empty answer
        // must not replace a working network in the cache, or one bare
        // square poisons every later plan from memory.
        assertEquals(20, Router.MIN_USABLE_NODES)
        assertTrue(Router.MIN_USABLE_NODES > 0)
    }
}
