package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline mixes a measurement with a model, so the rules about which
 * wins where are worth holding to a test: radar is never covered over by a
 * forecast hour, nothing outside the ten-hour span gets in, and the scrubber
 * opens on now.
 */
class TimelineTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    /** Radar as RainViewer publishes it: 10-minute steps, -2 h to +30 min. */
    private fun radarFrames(): List<WxFrame> {
        val out = ArrayList<WxFrame>()
        var t = now - 2 * hour
        while (t <= now + hour / 2) {
            out.add(WxFrame(t, "/v2/radar/$t", nowcast = t > now))
            t += 10 * 60_000L
        }
        return out
    }

    private fun hours(from: Int, to: Int): List<Long> =
        (from..to).map { now + it * hour }

    @Test
    fun forecastHoursNeverLandInsideTheRadarWindow() {
        val merged = Timeline.merge(radarFrames(), hours(-8, 8), now)
        val radarFrom = merged.filter { it.radarPath != null }.minOf { it.timeMs }
        val radarTo = merged.filter { it.radarPath != null }.maxOf { it.timeMs }
        for (f in merged) {
            if (f.radarPath == null) {
                assertTrue(
                    "a forecast hour landed on top of real radar",
                    f.timeMs < radarFrom || f.timeMs > radarTo,
                )
            }
        }
    }

    @Test
    fun theSpanIsFiveHoursEitherSideAndSorted() {
        val merged = Timeline.merge(radarFrames(), hours(-9, 9), now)
        assertTrue(merged.isNotEmpty())
        assertTrue("too early a frame", merged.first().timeMs >= now - 5 * hour)
        assertTrue("too late a frame", merged.last().timeMs <= now + 5 * hour)
        for (i in 1 until merged.size) {
            assertTrue("frames out of order", merged[i].timeMs >= merged[i - 1].timeMs)
        }
        // Both halves are actually reachable: five hours back and five on.
        assertTrue("nothing more than 3 h back", merged.any { it.timeMs <= now - 3 * hour })
        assertTrue("nothing more than 3 h ahead", merged.any { it.timeMs >= now + 3 * hour })
    }

    @Test
    fun withNoRadarAtAllTheForecastStillFillsTheTimeline() {
        val merged = Timeline.merge(emptyList(), hours(-5, 5), now)
        assertEquals(11L, merged.size.toLong())
        merged.forEach { assertNull(it.radarPath) }
        assertEquals("forecast", merged.first().kind)
    }

    @Test
    fun theScrubberOpensOnTheFrameNearestNow() {
        val merged = Timeline.merge(radarFrames(), hours(-5, 5), now)
        val i = Timeline.indexOfNow(merged, now)
        assertTrue(
            "opened ${(merged[i].timeMs - now) / 60_000} min from now",
            kotlin.math.abs(merged[i].timeMs - now) <= 10 * 60_000L,
        )
    }

    @Test
    fun aFrameSaysWhetherItIsMeasuredOrModelled() {
        assertEquals("radar", WxFrame(now, "/p").kind)
        assertEquals("radar nowcast", WxFrame(now, "/p", nowcast = true).kind)
        assertEquals("forecast", WxFrame(now, null).kind)
    }
}
