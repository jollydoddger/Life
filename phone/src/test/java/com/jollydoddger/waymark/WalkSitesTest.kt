package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The site guides are advice to a model and it will usually take it. The
 * per-site *rule* is the part that has to hold when it doesn't, because it
 * is about somebody else's work: one site puts a person's tap in front of a
 * download on purpose, and another sells bulk access as a membership.
 *
 * Rendering and the rules are testable without a Context; the JSON store
 * isn't, so what is checked here is what can honestly be checked.
 */
class WalkSitesTest {

    @Test
    fun `a gated site allows no automated fetches at all`() {
        // Walking Britain: the /walk-N-gps page is a click-through. Zero is
        // the point — one "just this once" is the gate defeated.
        assertEquals(0, Rule.GATED.perSession)
    }

    @Test
    fun `a site that sells bulk is capped well below a bulk download`() {
        assertTrue(
            "a cap that allows an area's worth is not a cap",
            Rule.SELLS_BULK.perSession in 1..5,
        )
        assertTrue(
            "and it must be stricter than an open site",
            Rule.SELLS_BULK.perSession < Rule.OPEN.perSession,
        )
    }

    @Test
    fun `an open site is still bounded — politeness, not suspicion`() {
        assertTrue("free is not unlimited", Rule.OPEN.perSession in 1..20)
    }

    @Test
    fun `each rule says in words what it is, for the model to read`() {
        for (r in Rule.values()) {
            assertTrue("${r.name} needs a readable summary", r.short.length > 8)
        }
        assertTrue("gated says what to do instead", "link" in Rule.GATED.short)
        assertTrue("bulk names the membership", "membership" in Rule.SELLS_BULK.short)
    }

    private fun guide(host: String, rule: Rule = Rule.OPEN) = SiteGuide(
        host = host,
        name = "Test Site",
        finding = "index at /walks",
        getting = "plain .gpx on the page",
        rule = rule,
        note = "",
    )

    @Test
    fun `a rendered guide carries the rule, not just the URLs`() {
        val text = guide("example.co.uk", Rule.SELLS_BULK).render()
        assertTrue("names the site", "example.co.uk" in text)
        assertTrue("carries how to find walks", "index at /walks" in text)
        assertTrue("carries how to get the file", "plain .gpx" in text)
        assertTrue("and states the rule where it cannot be missed", "membership" in text)
    }

    @Test
    fun `an empty note is left out rather than rendered blank`() {
        val text = guide("example.co.uk").render()
        assertTrue("no dangling note line", "note:" !in text)
        val withNote = guide("example.co.uk").copy(note = "htm and html both in use").render()
        assertTrue("note:" in withNote)
    }
}
