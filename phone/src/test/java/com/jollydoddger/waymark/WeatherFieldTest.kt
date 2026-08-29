package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The forecast grid's one piece of arithmetic that can lie: which hour of
 * data a moment on the scrubber is described by.
 */
class WeatherFieldTest {

    private val hour = 3_600_000L

    private fun field(base: Long, hours: Int): Weather.Field {
        val n = Weather.GRID * Weather.GRID
        fun grid() = Array(hours) { DoubleArray(n) { 10.0 } }
        return Weather.Field(
            timesMs = LongArray(hours) { base + it * hour },
            lat = DoubleArray(n), lon = DoubleArray(n),
            temp = grid(), rain = grid(), cloud = grid(),
            windSpeed = grid(), windDir = grid(),
            south = 53.0, west = -4.6, north = 53.4, east = -4.0,
        )
    }

    @Test fun `picks the nearest hour`() {
        val f = field(0L, 6)
        assertEquals(0L, f.hourIndex(0L).toLong())
        assertEquals(2L, f.hourIndex(2 * hour + 5 * 60_000L).toLong())
        // Twenty-five past leans to the hour it is nearer, not the one before.
        assertEquals(3L, f.hourIndex(2 * hour + 35 * 60_000L).toLong())
    }

    @Test fun `a radar frame off the hour still lands on one`() {
        val f = field(0L, 6)
        // Radar publishes on ten-minute marks, so every frame is within
        // half an hour of an hour and must always resolve.
        for (m in 0 until 6 * 60 step 10) {
            assertEquals(true, f.hourIndex(m * 60_000L) >= 0)
        }
    }

    @Test fun `a moment outside the data is no hour at all`() {
        val f = field(0L, 3)
        // Five hours past the end used to come back as the last hour, drawn
        // on the map and labelled with the frame's own time.
        assertEquals(-1L, f.hourIndex(2 * hour + 5 * hour).toLong())
        assertEquals(-1L, f.hourIndex(-5 * hour).toLong())
    }

    @Test fun `an empty field describes nothing`() {
        assertEquals(-1L, field(0L, 0).hourIndex(0L).toLong())
    }
}
