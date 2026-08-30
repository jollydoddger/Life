package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ramps decide what he believes about the sky at a glance, so they are
 * held to the properties that make them readable: monotone where the reading
 * is, clear where there is nothing, and never invisible where there is rain.
 */
class RampTest {

    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    @Test
    fun dryGroundIsClearAndAnyRainAtAllIsVisible() {
        assertEquals(0L, Ramp.rain(0.0).toLong())
        assertEquals(0L, Ramp.rain(0.01).toLong())
        // The original complaint: drizzle you cannot see is drizzle that
        // might as well not be drawn.
        assertTrue("drizzle too faint", alpha(Ramp.rain(0.15)) >= 120)
        assertTrue("steady rain too faint", alpha(Ramp.rain(2.0)) >= 190)
        assertTrue("downpour too faint", alpha(Ramp.rain(30.0)) >= 220)
    }

    @Test
    fun heavierRainIsNeverMoreTransparentThanLighter() {
        var previous = 0
        var mm = 0.05
        while (mm < 40) {
            val a = alpha(Ramp.rain(mm))
            assertTrue("alpha dipped at $mm mm/h", a >= previous)
            previous = a
            mm *= 1.3
        }
    }

    @Test
    fun clearSkyLeavesTheMapAloneAndCloudGreysIn() {
        // This used to assert the opposite — a gold wash marking clear sky.
        // It was replaced because cloud is under a quarter over most of a
        // viewport most of the time, so the gold was almost never a finding
        // and almost always a film over the whole map, sitting on the
        // contours and path lines being navigated by.
        assertEquals(0L, alpha(Ramp.cloud(0.0)).toLong())
        assertEquals(0L, alpha(Ramp.cloud(24.9)).toLong())
        // From there it only thickens, and never becomes opaque: the map
        // underneath is the thing he is actually walking on.
        var last = -1
        for (pct in intArrayOf(25, 40, 60, 80, 100)) {
            val a = alpha(Ramp.cloud(pct.toDouble()))
            assertTrue("cloud at $pct% should be no thinner than below it", a >= last)
            last = a
        }
        assertTrue("overcast must not hide the map", last < 220)
        val dull = Ramp.cloud(100.0)
        fun red(c: Int) = (c shr 16) and 0xFF
        fun blue(c: Int) = c and 0xFF
        assertTrue("overcast should read neutral", kotlin.math.abs(red(dull) - blue(dull)) < 40)
    }

    @Test
    fun temperatureRunsColdToWarmAndClampsOutsideIt() {
        fun red(c: Int) = (c shr 16) and 0xFF
        fun blue(c: Int) = c and 0xFF
        assertTrue("freezing should read cold", blue(Ramp.temperature(0.0)) > red(Ramp.temperature(0.0)))
        assertTrue("a hot day should read warm", red(Ramp.temperature(28.0)) > blue(Ramp.temperature(28.0)))
        assertEquals(Ramp.temperature(-40.0).toLong(), Ramp.temperature(-8.0).toLong())
        assertEquals(Ramp.temperature(60.0).toLong(), Ramp.temperature(32.0).toLong())
        assertEquals(255L, alpha(Ramp.temperature(12.0)).toLong())
    }

    // --- the smoothing -------------------------------------------------------

    @Test
    fun cornersComeBackExactlyAndTheMiddleIsTheMean() {
        val v = doubleArrayOf(
            0.0, 10.0,
            20.0, 30.0,
        )
        assertEquals(0.0, Ramp.bilinear(v, 2, 0.0, 0.0), 1e-9)
        assertEquals(10.0, Ramp.bilinear(v, 2, 0.0, 1.0), 1e-9)
        assertEquals(20.0, Ramp.bilinear(v, 2, 1.0, 0.0), 1e-9)
        assertEquals(30.0, Ramp.bilinear(v, 2, 1.0, 1.0), 1e-9)
        assertEquals(15.0, Ramp.bilinear(v, 2, 0.5, 0.5), 1e-9)
    }

    @Test
    fun aMissingReadingIsCarriedByItsNeighboursNotTreatedAsZero() {
        val v = doubleArrayOf(
            10.0, 10.0,
            10.0, Double.NaN,
        )
        // Halfway between: three tens and a hole should still be ten, not 7.5.
        assertEquals(10.0, Ramp.bilinear(v, 2, 0.5, 0.5), 1e-9)
        // Sitting exactly on the hole is the one place with no answer.
        assertTrue(Ramp.bilinear(v, 2, 1.0, 1.0).isNaN())
    }

    @Test
    fun aGridOfNothingGivesNothing() {
        val v = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        assertTrue(Ramp.bilinear(v, 2, 0.5, 0.5).isNaN())
        assertTrue(Ramp.bilinear(doubleArrayOf(1.0), 3, 0.5, 0.5).isNaN())
    }
}
