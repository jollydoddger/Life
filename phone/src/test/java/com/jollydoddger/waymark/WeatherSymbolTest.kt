package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The symbol on the map's weather readout is derived from the grid, not
 * fetched, so the derivation is the thing that can lie. The bands here are
 * the ones the forecast page names in words; a symbol that disagreed with
 * the page one tap away would be worse than no symbol.
 */
class WeatherSymbolTest {

    private fun code(t: Double = 12.0, rain: Double = 0.0, cloud: Double = 20.0, vis: Double = 20_000.0) =
        Weather.symbolCode(t, rain, cloud, vis)

    @Test fun `a clear sky is the sun`() = assertEquals(0, code(cloud = 5.0))

    @Test fun `cloud thickens through the bands`() {
        assertEquals(1, code(cloud = 30.0))
        assertEquals(2, code(cloud = 60.0))
        assertEquals(3, code(cloud = 95.0))
    }

    @Test fun `rain outranks cloud and grows with the rate`() {
        assertEquals(51, code(rain = 0.2, cloud = 95.0))
        assertEquals(61, code(rain = 0.5, cloud = 95.0))
        assertEquals(63, code(rain = 2.0, cloud = 95.0))
        assertEquals(65, code(rain = 5.0, cloud = 95.0))
    }

    @Test fun `a shower under an open sky keeps its sun`() {
        assertEquals(80, code(rain = 0.5, cloud = 30.0))
        assertEquals(82, code(rain = 5.0, cloud = 30.0))
    }

    @Test fun `freezing rain is snow`() {
        assertEquals(71, code(t = -1.0, rain = 0.5))
        assertEquals(75, code(t = 0.0, rain = 3.0))
        // Just above freezing it is still rain.
        assertEquals(61, code(t = 2.0, rain = 0.5, cloud = 95.0))
    }

    @Test fun `fog is under a kilometre and only when it is dry`() {
        assertEquals(45, code(vis = 600.0))
        assertTrue(code(rain = 1.0, vis = 600.0) != 45)
    }

    @Test fun `an empty hour has no symbol`() {
        assertEquals(-1, Weather.symbolCode(Double.NaN, Double.NaN, Double.NaN, Double.NaN))
        // A missing cloud figure alone still gives a picture.
        assertTrue(Weather.symbolCode(10.0, 0.0, Double.NaN, Double.NaN) >= 0)
    }
}
