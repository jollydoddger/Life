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

    /** The inverse against the same worked example, both directions. */
    @Test
    fun inverseRoundTrips() {
        // BNG → WGS84 → BNG closes to under a centimetre.
        val en = En(651409.903, 313177.270)
        val (lat, lon) = Bng.toWgs84(en)
        val back = Bng.fromWgs84(lat, lon)
        assertEquals(en.e, back.e, 0.01)
        assertEquals(en.n, back.n, 0.01)

        // WGS84 → BNG → WGS84 (Anglesey) closes to a few centimetres.
        val start = Bng.fromWgs84(53.222, -4.208)
        val (lat2, lon2) = Bng.toWgs84(start)
        assertEquals(53.222, lat2, 1e-6)
        assertEquals(-4.208, lon2, 1e-6)
    }

    @Test
    fun gridRefLettersAndDigits() {
        // OS worked example square: TG.
        assertEquals("TG 51409 13177", Bng.gridRef(En(651409.903, 313177.270)))
        // Anglesey: SH.
        assertEquals("SH 31517 71671", Bng.gridRef(En(231517.0, 371671.0)))
        // Off the lettered grid.
        assertEquals(null, Bng.gridRef(En(-300000.0, 50000.0)))
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
