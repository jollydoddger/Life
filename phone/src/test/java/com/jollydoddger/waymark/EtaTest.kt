package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind "how long until I'm there". A wrong answer is a
 * missed turn on a hillside; the desk is where it gets caught.
 */
class EtaTest {

    private val alongs = doubleArrayOf(0.0, 100.0, 200.0, 300.0, 400.0)
    private val heights = doubleArrayOf(10.0, 50.0, 30.0, 80.0, 20.0)

    @Test fun `cumulative distance runs along the line`() {
        val cum = Eta.cumulative(listOf(En(0.0, 0.0), En(300.0, 400.0), En(300.0, 500.0)))
        assertEquals(0.0, cum[0], 0.001)
        assertEquals(500.0, cum[1], 0.001)
        assertEquals(600.0, cum[2], 0.001)
    }

    @Test fun `climb sums the ups and downs between two points`() {
        val (up, down) = Eta.climbBetween(alongs, heights, 0.0, 400.0)
        assertEquals(90.0, up, 0.001)
        assertEquals(80.0, down, 0.001)
    }

    @Test fun `walking the other way swaps up and down`() {
        val (up, down) = Eta.climbBetween(alongs, heights, 400.0, 0.0)
        assertEquals(80.0, up, 0.001)
        assertEquals(90.0, down, 0.001)
        // A partial stretch, both ways: what is up one way is down the other.
        val fwd = Eta.climbBetween(alongs, heights, 50.0, 250.0)
        val back = Eta.climbBetween(alongs, heights, 250.0, 50.0)
        assertEquals(45.0, fwd.first, 0.001)
        assertEquals(20.0, fwd.second, 0.001)
        assertEquals(fwd.first, back.second, 0.001)
        assertEquals(fwd.second, back.first, 0.001)
    }

    @Test fun `standing still climbs nothing`() {
        val (up, down) = Eta.climbBetween(alongs, heights, 150.0, 150.0)
        assertEquals(0.0, up, 0.001)
        assertEquals(0.0, down, 0.001)
    }

    @Test fun `his usual pace is the median of his real walks`() {
        // 10, 12 and 20 min/km — the median must win, and the 20 must not
        // drag it: one slow pub walk is not his pace.
        val walks = listOf(
            5_000.0 to 50L * 60_000L,
            4_000.0 to 48L * 60_000L,
            3_000.0 to 60L * 60_000L,
        )
        assertEquals(12.0, Eta.paceFromWalks(walks)!!, 0.001)
    }

    @Test fun `broken records cannot poison the pace`() {
        // Too short, zero duration, and absurd paces are all ignored; with
        // nothing left there is no answer, not a made-up one.
        assertNull(
            Eta.paceFromWalks(
                listOf(
                    200.0 to 10L * 60_000L,       // too short
                    5_000.0 to 0L,                // no duration
                    1_000.0 to 300L * 60_000L,    // 300 min/km: parked, not walking
                    10_000.0 to 20L * 60_000L,    // 2 min/km: a car, not a walk
                ),
            ),
        )
    }

    @Test fun `time is distance at his pace plus a minute per ten metres up`() {
        // 2 km at 12 min/km + 50 m of climb = 24 + 5.
        assertEquals(29.0, Eta.minutes(2_000.0, 50.0, 12.0), 0.001)
        // Descent is priced at nothing — it is in the measured pace already.
        assertEquals(24.0, Eta.minutes(2_000.0, 0.0, 12.0), 0.001)
    }
}
