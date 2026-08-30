package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Gpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "South east, 4 to 6 miles" turning into the wrong candidates is a quiet
 * failure only arithmetic under test catches — his transcript shows the
 * assistant refusing exactly that ask for want of these filters.
 */
class WalkFilterTest {

    private val here = En(230_000.0, 380_000.0)

    private fun walk(name: String, at: En, lengthM: Double) = RouteFinder.FoundWalk(
        name = name,
        source = "OSM",
        lines = listOf(listOf(at, En(at.e + 100, at.n + 100))),
        closestM = kotlin.math.hypot(at.e - here.e, at.n - here.n),
        lengthM = lengthM,
    )

    @Test fun `bearing runs clockwise from north`() {
        assertEquals(0.0, WalkFilter.bearingDeg(here, En(here.e, here.n + 1000)), 0.01)
        assertEquals(90.0, WalkFilter.bearingDeg(here, En(here.e + 1000, here.n)), 0.01)
        assertEquals(180.0, WalkFilter.bearingDeg(here, En(here.e, here.n - 1000)), 0.01)
        assertEquals(225.0, WalkFilter.bearingDeg(here, En(here.e - 1000, here.n - 1000)), 0.01)
    }

    @Test fun `compass words match generously and wrap at north`() {
        assertTrue(WalkFilter.compassMatches("SE", 135.0))
        assertTrue(WalkFilter.compassMatches("south east", 100.0))
        assertTrue(WalkFilter.compassMatches("south-east", 170.0))
        assertFalse(WalkFilter.compassMatches("SE", 300.0))
        // North's sector spans the wrap.
        assertTrue(WalkFilter.compassMatches("N", 350.0))
        assertTrue(WalkFilter.compassMatches("north", 20.0))
        assertFalse(WalkFilter.compassMatches("N", 90.1))
    }

    @Test fun `an unparseable direction hides nothing`() {
        // A filter that cannot parse must not become one that hides every
        // result: "towards the sea" matches everything, and the model's
        // reply still lists real candidates.
        assertTrue(WalkFilter.compassMatches("towards the sea", 10.0))
        assertTrue(WalkFilter.compassMatches("", 200.0))
    }

    @Test fun `filter narrows by direction and length`() {
        val east = walk("east", En(here.e + 10_000, here.n), 8_000.0)
        val west = walk("west", En(here.e - 10_000, here.n), 8_000.0)
        val short = walk("short", En(here.e + 10_000, here.n + 500), 2_000.0)
        val out = WalkFilter.filter(listOf(east, west, short), here, "E", 6_000.0, 10_000.0)
        assertEquals(listOf("east"), out.map { it.name })
        // No bearing, no length cap: everything survives.
        assertEquals(3L, WalkFilter.filter(listOf(east, west, short), here, null, 0.0, 0.0).size.toLong())
    }

    @Test fun `a route passing your feet is in every direction at once`() {
        // Direction-to-nearest-point is degenerate when the line comes
        // within a kilometre — it must never be filtered out for being in
        // the "wrong" one.
        val underfoot = walk("underfoot", En(here.e + 300, here.n), 8_000.0)
        val out = WalkFilter.filter(listOf(underfoot), here, "W", 0.0, 0.0)
        assertEquals(1L, out.size.toLong())
    }

    @Test fun `gpx sniffing accepts gpx and refuses the page around it`() {
        assertTrue(Gpx.looksLikeGpx("""<?xml version="1.0"?><gpx version="1.1">"""))
        assertTrue(Gpx.looksLikeGpx("""  <GPX creator="x">"""))
        assertFalse(Gpx.looksLikeGpx("<!DOCTYPE html><html><head><title>Download</title>"))
    }
}
