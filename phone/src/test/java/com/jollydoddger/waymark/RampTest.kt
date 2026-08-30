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
    fun clearSkyLeavesTheMapAloneAndGloomGreysIn() {
        // Clear sky is no ink at all — the map as printed — and the wash
        // only thickens from a quarter cover, never to opaque.
        assertEquals(0L, alpha(Ramp.sky(0.0, 0.0, 0.0, 10_000.0)).toLong())
        assertEquals(0L, alpha(Ramp.sky(20.0, 0.0, 0.0, 10_000.0)).toLong())
        var last = -1
        for (pct in intArrayOf(30, 50, 70, 90, 100)) {
            val a = alpha(Ramp.sky(pct.toDouble(), 0.0, 0.0, 10_000.0))
            assertTrue("gloom at $pct% should be no thinner than below it", a >= last)
            last = a
        }
        assertTrue("overcast must not hide the map", last < 220)
    }

    @Test
    fun highCloudBarelyRegistersAndLowCloudDominates() {
        // A sky of pure cirrus dims a day far less than the same cover of
        // low cloud; the weights say so and the wash must too.
        val high = alpha(Ramp.sky(0.0, 0.0, 100.0, 10_000.0))
        val low = alpha(Ramp.sky(100.0, 0.0, 0.0, 10_000.0))
        assertTrue("low cover must paint heavier than high", low > high + 60)
    }

    @Test
    fun fogIsItsOwnThingWhateverTheSkyAboveSays() {
        // Hill fog under a clear sky is not 0% cloud — it is the condition
        // that turns walking-by-sight into a compass leg.
        val fogClear = Ramp.sky(0.0, 0.0, 0.0, 500.0)
        assertTrue("fog must paint even under clear sky", alpha(fogClear) >= 150)
        // And it must out-paint any plain overcast.
        assertTrue(alpha(fogClear) >= alpha(Ramp.sky(100.0, 100.0, 100.0, 5_000.0)))
        // Unknown visibility is not fog.
        assertEquals(0L, alpha(Ramp.sky(0.0, 0.0, 0.0, Double.NaN)).toLong())
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
