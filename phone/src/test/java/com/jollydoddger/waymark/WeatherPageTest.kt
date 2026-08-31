package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things on the weather page that can be quietly, confidently
 * wrong: what a forecast code means, and which way the wind arrow points.
 */
class WeatherPageTest {

    @Test
    fun `the arrow points where the wind is going, not where it came from`() {
        // This is the one that is wrong in half the apps that draw it. A
        // northerly — reported as 0°, "from the north" — pushes you south,
        // so the arrow points down.
        assertEquals("↓", WeatherActivity.arrow(0.0))
        assertEquals("↑", WeatherActivity.arrow(180.0))
        assertEquals("←", WeatherActivity.arrow(90.0))
        assertEquals("→", WeatherActivity.arrow(270.0))
    }

    @Test
    fun `every arrow direction is a real arrow, all the way round the compass`() {
        var deg = 0.0
        while (deg < 360.0) {
            val a = WeatherActivity.arrow(deg)
            assertTrue("$deg gave '$a'", a in listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖"))
            deg += 7.5
        }
        // And a reading that arrives out of range must not fall off the end.
        assertTrue(WeatherActivity.arrow(360.0).isNotEmpty())
        assertTrue(WeatherActivity.arrow(725.0).isNotEmpty())
    }

    @Test
    fun `weather codes read as a person would say them`() {
        assertEquals("Clear", WeatherActivity.describe(0))
        assertEquals("Fog", WeatherActivity.describe(45))
        assertEquals("Heavy rain", WeatherActivity.describe(65))
        assertEquals("Thunderstorm", WeatherActivity.describe(95))
    }

    @Test
    fun `an unknown code says nothing rather than inventing weather`() {
        // Open-Meteo can add codes. Guessing at one would put a confident
        // wrong word on the screen; a dash is honest.
        assertEquals("—", WeatherActivity.describe(7))
        assertEquals("—", WeatherActivity.describe(-1))
        assertEquals("—", WeatherActivity.describe(999))
    }

    @Test
    fun `rain and snow are never confused for each other`() {
        for (code in listOf(61, 63, 65, 80, 81, 82)) {
            val said = WeatherActivity.describe(code).lowercase()
            assertTrue("$code said '$said'", "rain" in said || "shower" in said)
            assertTrue("$code must not mention snow", "snow" !in said)
        }
        for (code in listOf(71, 73, 75, 85, 86)) {
            assertTrue("snow" in WeatherActivity.describe(code).lowercase())
        }
    }
}
