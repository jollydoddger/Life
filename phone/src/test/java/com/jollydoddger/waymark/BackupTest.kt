package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPX that leaves the app is the one thing a lost phone leaves him
 * with, so it has to be a file every other app reads: a track for a walk
 * that happened, a route for one that is to happen, names escaped, and
 * coordinates back in WGS84 at the door.
 */
class BackupTest {

    private val pts = listOf(
        Bng.fromWgs84(53.25, -4.40),
        Bng.fromWgs84(53.26, -4.39),
    )

    @Test fun `a route is a route and a walk is a track`() {
        val route = Backup.gpx("Tower Hill", "", pts)
        assertTrue(route.contains("<rte>") && route.contains("<rtept lat=\"53.25"))
        assertTrue(!route.contains("<trk>"))
        val walk = Backup.gpx("Tower Hill", "", pts, asTrack = true, whenMs = 1_700_000_000_000L)
        assertTrue(walk.contains("<trkseg>") && walk.contains("<trkpt lat=\"53.26"))
        assertTrue("the start time travels", walk.contains("<time>2023-11-14T22:13:20Z</time>"))
    }

    @Test fun `names are escaped and the file name is safe`() {
        val g = Backup.gpx("Warley <Common> & back", "", pts)
        assertTrue(g.contains("<name>Warley &lt;Common&gt; &amp; back</name>"))
        assertEquals("Warley_Common__back.gpx", Backup.fileName("Warley <Common> & back", "route"))
        assertEquals("route.gpx", Backup.fileName("///", "route"))
    }

    @Test fun `coordinates round-trip through the door`() {
        val g = Backup.gpx("x", "", listOf(Bng.fromWgs84(54.0, -2.0)))
        val m = Regex("lat=\"([-0-9.]+)\" lon=\"([-0-9.]+)\"").find(g)!!
        assertEquals(54.0, m.groupValues[1].toDouble(), 1e-4)
        assertEquals(-2.0, m.groupValues[2].toDouble(), 1e-4)
    }

    @Test fun `a decimal point is a point whatever the locale`() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val g = Backup.gpx("x", "", listOf(En(300_000.0, 400_000.0)))
            assertTrue(g, !g.contains("lat=\"5,"))
        } finally {
            java.util.Locale.setDefault(was)
        }
    }
}
