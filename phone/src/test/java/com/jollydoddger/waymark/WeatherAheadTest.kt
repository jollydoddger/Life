package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The words about the hours ahead, held to the numbers they describe.
 * This is the thing that buzzes his wrist on a hill; a sentence that
 * says "clearing by three" when the numbers say four is worse than none.
 */
class WeatherAheadTest {

    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC
    private val hourMs = 3_600_000L
    private val h0 = now - (now % hourMs) // the hour now sits in

    @Before fun pinClock() {
        WeatherAhead.clock = { ms ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(ms))
        }
    }

    private fun hours(vararg rain: Double, cloud: Double = Double.NaN, gust: Double = Double.NaN) =
        rain.mapIndexed { i, mm ->
            WeatherAhead.Hour(h0 + i * hourMs, mm, cloudPct = cloud, gustMph = gust)
        }

    @Test fun `dry all the way says so and nothing else`() {
        val s = WeatherAhead.describe(hours(0.0, 0.0, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0), now)
        assertEquals("Dry for the next about 8 h.", s)
    }

    @Test fun `rain ahead is named by the hour it starts and the hour it clears`() {
        // 22:00 dry, 23:00 dry, 00:00 rain, 01:00 heavier, 02:00 dry…
        val s = WeatherAhead.describe(hours(0.0, 0.0, 0.5, 2.4, 0.0, 0.0, 0.0, 0.0), now)
        assertTrue(s, s.startsWith("Dry until 00:00, then rain for about 2 h (heaviest 2 mm/h around 01:00), clearing by 02:00."))
    }

    @Test fun `raining now says when it clears`() {
        val s = WeatherAhead.describe(hours(1.0, 1.0, 0.0, 0.0), now)
        assertTrue(s, s.startsWith("Raining now (1 mm/h), clearing by 00:00."))
    }

    @Test fun `raining now with no break says set in`() {
        val s = WeatherAhead.describe(hours(1.0, 1.0, 3.0), now)
        assertTrue(s, s.startsWith("Raining now (3 mm/h) and set in for the next about 3 h at least."))
    }

    @Test fun `spitting is not rain`() {
        // 0.1 mm with no probability figure is under the wet line.
        assertTrue(!WeatherAhead.isWet(WeatherAhead.Hour(h0, 0.1)))
        // But 0.1 mm at 70% likely is rain you should expect.
        assertTrue(WeatherAhead.isWet(WeatherAhead.Hour(h0, 0.1, rainProb = 70)))
        assertTrue(WeatherAhead.isWet(WeatherAhead.Hour(h0, 0.2)))
    }

    @Test fun `sun is only mentioned where cloud is known`() {
        val noCloud = WeatherAhead.describe(hours(0.0, 0.0), now)
        assertTrue(noCloud, !noCloud.contains("un"))
        val sunny = WeatherAhead.describe(hours(0.0, 0.0, 0.0, cloud = 10.0), now)
        assertTrue(sunny, sunny.contains("Sunny throughout."))
        val overcast = WeatherAhead.describe(hours(0.0, 0.0, cloud = 90.0), now)
        assertTrue(overcast, overcast.contains("No sun expected"))
    }

    @Test fun `sun from later is dated`() {
        val hs = listOf(
            WeatherAhead.Hour(h0, 0.0, cloudPct = 90.0),
            WeatherAhead.Hour(h0 + hourMs, 0.0, cloudPct = 90.0),
            WeatherAhead.Hour(h0 + 2 * hourMs, 0.0, cloudPct = 20.0),
            WeatherAhead.Hour(h0 + 3 * hourMs, 0.0, cloudPct = 85.0),
        )
        val s = WeatherAhead.describe(hs, now)
        assertTrue(s, s.contains("Sun from 00:00 until about 01:00."))
    }

    @Test fun `gusts are only worth a word when strong`() {
        assertTrue(!WeatherAhead.describe(hours(0.0, 0.0, gust = 20.0), now).contains("Gusts"))
        assertTrue(WeatherAhead.describe(hours(0.0, 0.0, gust = 41.0), now).contains("Gusts to 41 mph"))
    }

    @Test fun `hours already gone are not hours ahead`() {
        val old = listOf(
            WeatherAhead.Hour(h0 - 3 * hourMs, 5.0),
            WeatherAhead.Hour(h0 - 2 * hourMs, 5.0),
            WeatherAhead.Hour(h0, 0.0),
            WeatherAhead.Hour(h0 + hourMs, 0.0),
        )
        assertEquals("Dry for the next about 2 h.", WeatherAhead.describe(old, now))
        assertEquals("No forecast for the hours ahead.", WeatherAhead.describe(emptyList(), now))
    }

    @Test fun `the headline for coming rain is keyed on its onset`() {
        val a = WeatherAhead.headlines(hours(0.0, 0.0, 0.5, 0.5, 0.0), now)
        assertEquals(WeatherAhead.Kind.RAIN_SOON, a.first().kind)
        assertEquals("Rain from 00:00", a.first().title)
        // Re-read the same forecast: the same key, so nothing new is said.
        val b = WeatherAhead.headlines(hours(0.0, 0.0, 0.5, 0.5, 0.0), now + 20 * 60_000L)
        assertEquals(a.first().key, b.first().key)
        // The model moving the onset an hour is news again.
        val c = WeatherAhead.headlines(hours(0.0, 0.0, 0.0, 0.5, 0.0), now)
        assertTrue(a.first().key != c.first().key)
    }

    @Test fun `raining now headlines the clearance, and a dry day is said once`() {
        val clears = WeatherAhead.headlines(hours(1.0, 0.0, 0.0), now)
        assertEquals(WeatherAhead.Kind.RAINING_CLEARS, clears.first().kind)
        assertEquals("Clearing by 23:00", clears.first().title)
        val dry = WeatherAhead.headlines(hours(0.0, 0.0, 0.0), now)
        assertEquals(WeatherAhead.Kind.DRY, dry.first().kind)
        assertEquals("dry", dry.first().key)
    }

    @Test fun `the sun rides along as a second headline`() {
        val hs = listOf(
            WeatherAhead.Hour(h0, 0.0, cloudPct = 90.0),
            WeatherAhead.Hour(h0 + hourMs, 0.0, cloudPct = 20.0),
        )
        val out = WeatherAhead.headlines(hs, now)
        assertEquals(2, out.size)
        assertEquals(WeatherAhead.Kind.SUN_SOON, out[1].kind)
        assertEquals("sun@${h0 + hourMs}", out[1].key)
    }
}
