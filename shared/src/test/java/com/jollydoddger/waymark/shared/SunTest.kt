package com.jollydoddger.waymark.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The solar maths, checked against facts about the sky rather than against
 * itself. Astronomy in this app gets verified before it ships — the same
 * rule the grid projection is held to.
 */
class SunTest {

    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    /** Minutes past midnight UTC, for readable assertions. */
    private fun minutesUtc(ms: Long): Int = ((ms - Sun.dayStart(ms)) / 60_000L).toInt()

    private val angleseyLat = 53.28
    private val angleseyLon = -4.50

    /**
     * At an equinox the sun rises due east and sets due west, everywhere on
     * earth. If the azimuth branches are inverted — which they were on the
     * first attempt — this is what catches it.
     */
    @Test
    fun equinoxSunRisesEastAndSetsWest() {
        val day = utc(2026, 3, 20)
        val rise = Sun.sunrise(day, angleseyLat, angleseyLon)!!
        val set = Sun.sunset(day, angleseyLat, angleseyLon)!!
        assertEquals(90.0, Sun.positionAt(rise, angleseyLat, angleseyLon).azimuth, 2.0)
        assertEquals(270.0, Sun.positionAt(set, angleseyLat, angleseyLon).azimuth, 2.0)
    }

    /**
     * Solar noon: the sun is due south from the northern mid-latitudes, and
     * at its highest. Checked at the moment the algorithm itself calls noon
     * by searching for the peak.
     */
    @Test
    fun solarNoonIsDueSouth() {
        val day = utc(2026, 8, 29)
        var best = day
        var bestEl = -90.0
        for (i in 0 until 24 * 12) { // every five minutes
            val t = day + i * 5 * 60_000L
            val el = Sun.positionAt(t, angleseyLat, angleseyLon).elevation
            if (el > bestEl) { bestEl = el; best = t }
        }
        assertEquals(180.0, Sun.positionAt(best, angleseyLat, angleseyLon).azimuth, 1.5)
    }

    /**
     * Anglesey, 29 August 2026: sunrise about 05:22 UTC and sunset about
     * 19:14 UTC (06:22 and 20:14 British Summer Time). Ordinary published
     * almanac times for the place this app is actually used.
     */
    @Test
    fun angleseySunriseAndSunsetInLateAugust() {
        val day = utc(2026, 8, 29)
        val rise = Sun.sunrise(day, angleseyLat, angleseyLon)!!
        val set = Sun.sunset(day, angleseyLat, angleseyLon)!!
        assertEquals((5 * 60 + 22).toDouble(), minutesUtc(rise).toDouble(), 4.0)
        assertEquals((19 * 60 + 14).toDouble(), minutesUtc(set).toDouble(), 4.0)
        // Late August: the sun still sets north of due west.
        val az = Sun.positionAt(set, angleseyLat, angleseyLon).azimuth
        assertTrue("sunset azimuth $az should be WNW-ish", az in 280.0..295.0)
        assertEquals("WNW", Sun.compass(az))
    }

    /** Dusk runs in the right order, and golden hour precedes sunset. */
    @Test
    fun eveningEventsAreOrdered() {
        val day = utc(2026, 8, 29)
        val golden = Sun.goldenHourStart(day, angleseyLat, angleseyLon)!!
        val set = Sun.sunset(day, angleseyLat, angleseyLon)!!
        val dusk = Sun.civilDusk(day, angleseyLat, angleseyLon)!!
        assertTrue("golden hour before sunset", golden < set)
        assertTrue("sunset before civil dusk", set < dusk)
    }

    /**
     * Above the Arctic circle in midsummer the sun does not set, and the
     * honest answer is "no time", not a fabricated one.
     */
    @Test
    fun midnightSunHasNoSunset() {
        val day = utc(2026, 6, 21)
        assertNull(Sun.sunset(day, 78.0, 15.0)) // Svalbard
        assertNotNull(Sun.sunset(day, angleseyLat, angleseyLon))
    }

    @Test
    fun compassPointsReadAsPeopleSayThem() {
        assertEquals("N", Sun.compass(0.0))
        assertEquals("N", Sun.compass(359.0))
        assertEquals("E", Sun.compass(90.0))
        assertEquals("SW", Sun.compass(225.0))
        assertEquals("WNW", Sun.compass(292.5))
    }
}
