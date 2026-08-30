package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Best time to set off, weather dependent" is a recommendation, and a
 * recommendation that quietly picks the wet hour is worse than none at all —
 * he would be out in it before finding out. So the choosing is arithmetic,
 * separated from the fetching and the wording, and tested here.
 */
class BriefTest {

    private val day = 1_800_000_000_000L // an arbitrary midnight-ish anchor

    private fun hours(vararg rainMm: Double): List<Brief.Hour> =
        rainMm.mapIndexed { i, mm ->
            Brief.Hour(
                timeMs = day + i * 3_600_000L,
                tempC = 12.0,
                rainMm = mm,
                rainProb = if (mm > 0) 80 else 5,
                windMph = 8.0,
                gustMph = 14.0,
                windDeg = 225.0,
            )
        }

    @Test
    fun `it sets off into the dry gap, not the downpour`() {
        // Wet, wet, dry, dry, wet: a two-hour walk belongs at hour 2.
        val h = hours(3.0, 3.0, 0.0, 0.0, 3.0)
        val best = Brief.departures(h, day, day + 4 * 3_600_000L, 120.0, 3_600_000L).first()
        assertEquals(day + 2 * 3_600_000L, best.departMs)
        assertEquals(0.0, best.rainMm, 0.01)
    }

    @Test
    fun `rain is counted by the part of the hour actually walked in`() {
        // Half an hour of a 2 mm hour is 1 mm, not 2. Rounding that up made
        // a brisk walk into a soaking on paper.
        val h = hours(2.0, 0.0)
        val w = Brief.windowOver(h, day, 30.0)!!
        assertEquals(1.0, w.rainMm, 0.01)
    }

    @Test
    fun `a window the forecast does not reach is no answer, not a fine day`() {
        val h = hours(0.0, 0.0)
        // A four-hour walk against two hours of forecast: mostly unknown.
        assertNull(Brief.windowOver(h, day, 240.0))
        // And one that is covered comes back.
        assertNotNull(Brief.windowOver(h, day, 90.0))
    }

    @Test
    fun `real rain outweighs a mere chance of it`() {
        val wet = Brief.score(rainMm = 1.0, rainProb = 20, gustMph = 10.0, tempLo = 12.0, tempHi = 14.0)
        val threatened = Brief.score(rainMm = 0.0, rainProb = 90, gustMph = 10.0, tempLo = 12.0, tempHi = 14.0)
        assertTrue("a millimetre falling beats a 90% chance of nothing", wet > threatened)
    }

    @Test
    fun `wind only counts once it is strong enough to lean on`() {
        val breeze = Brief.score(0.0, 0, gustMph = 20.0, tempLo = 12.0, tempHi = 14.0)
        val same = Brief.score(0.0, 0, gustMph = 25.0, tempLo = 12.0, tempHi = 14.0)
        val gale = Brief.score(0.0, 0, gustMph = 50.0, tempLo = 12.0, tempHi = 14.0)
        assertEquals(breeze, same, 0.001)
        assertTrue(gale > same + 5)
    }

    @Test
    fun `cold is punished harder than warm — this is Anglesey`() {
        val freezing = Brief.score(0.0, 0, 5.0, tempLo = -1.0, tempHi = 1.0)
        val hot = Brief.score(0.0, 0, 5.0, tempLo = 25.0, tempHi = 25.0)
        assertTrue(freezing > 0)
        assertTrue(hot > 0)
        assertTrue("five degrees below is worse than five above", freezing > hot)
    }

    @Test
    fun `no room in the day means no departures, never a wrong one`() {
        val h = hours(0.0, 0.0, 0.0)
        assertTrue(Brief.departures(h, day + 3_600_000L, day, 60.0).isEmpty())
    }

    @Test
    fun `an equally good hour earlier wins — daylight in hand is never worth nothing`() {
        val h = hours(0.0, 0.0, 0.0, 0.0)
        val best = Brief.departures(h, day, day + 2 * 3_600_000L, 60.0, 3_600_000L)
        assertEquals(day, best.first().departMs)
    }
}
