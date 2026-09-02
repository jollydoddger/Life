package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The point forecast the walk is read from, parsed. A null in the
 * probability column is Open-Meteo's "not known", not zero, and an hour
 * with no rain figure at all is not an hour.
 */
class WeatherPointTest {

    private val body = """
        {"hourly":{
          "time":[1699999200,1700002800,1700006400],
          "temperature_2m":[9.1,8.4,null],
          "precipitation":[0.0,0.6,null],
          "precipitation_probability":[10,null,80],
          "cloud_cover":[30,95,100],
          "wind_speed_10m":[12,15,18],
          "wind_gusts_10m":[20,38,44]
        }}
    """.trimIndent()

    @Test fun `hours arrive in order with the figures they carry`() {
        val hs = Weather.parsePoint(body)
        assertEquals(2, hs.size) // the hour with no rain figure is dropped
        assertEquals(1_699_999_200_000L, hs[0].timeMs)
        assertEquals(10, hs[0].rainProb)
        assertEquals(0.6, hs[1].rainMm, 1e-9)
        assertEquals(-1, hs[1].rainProb)
        assertTrue(hs[1].tempC == 8.4)
        assertEquals(38.0, hs[1].gustMph, 1e-9)
    }

    @Test fun `the words come straight off the parse`() {
        WeatherAhead.clock = { ms ->
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(ms))
        }
        val s = WeatherAhead.describe(Weather.parsePoint(body), 1_700_000_000_000L)
        assertTrue(s, s.startsWith("Dry until 23:00, then rain for about an hour"))
    }
}
