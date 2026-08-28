package com.jollydoddger.waymark.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class BngTest {

    private fun dms(d: Int, m: Int, s: Double) = d + m / 60.0 + s / 3600.0

    /**
     * The worked example from OS's "A guide to coordinate systems in Great
     * Britain": OSGB36 52°39′27.2531″N 1°43′4.5177″E → E 651409.903 N 313177.270.
     * This exercises the projection alone, with OS's own published answer.
     */
    @Test
    fun projectionMatchesOsWorkedExample() {
        val en = Bng.project(
            Math.toRadians(dms(52, 39, 27.2531)),
            Math.toRadians(dms(1, 43, 4.5177)),
        )
        assertEquals(651409.903, en.e, 0.01)
        assertEquals(313177.270, en.n, 0.01)
    }

    /**
     * The same physical point given in WGS84 (the published transformed pair
     * for the worked example), through the full Helmert + projection chain.
     * The Helmert itself is ~3 m accurate nationally; against this reference
     * pair it lands within centimetres.
     */
    @Test
    fun fullChainMatchesReferencePair() {
        val en = Bng.fromWgs84(dms(52, 39, 28.723), dms(1, 42, 57.787))
        assertEquals(651409.903, en.e, 0.10)
        assertEquals(313177.270, en.n, 0.10)
    }

    @Test
    fun convergenceSignAndMagnitude() {
        // On the central meridian there is none.
        assertEquals(0.0, Bng.convergenceDeg(53.0, -2.0), 1e-9)
        // East of it, positive; ~3° out on the Norfolk coast.
        val c = Bng.convergenceDeg(52.66, 1.72)
        assertEquals(2.96, c, 0.05)
    }
}
