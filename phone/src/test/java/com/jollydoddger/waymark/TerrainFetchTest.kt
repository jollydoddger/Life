package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * The parts of this that were not read off the real GetCapabilities
 * response and are inference rather than fact: the WCS request this
 * builds, reading a refusal's own words back out of it rather than a
 * bare HTTP code, and — the one that would have shipped wrong — what the
 * dataset's advertised bounding box can and cannot tell him.
 *
 * That last one is worth stating plainly, because the first version of
 * this file asserted the opposite of what the code does: Anglesey's
 * easting sits at roughly 240,000, which is comfortably inside the
 * dataset's advertised 80,000–656,000 — Wales is nested entirely within
 * England's own bounding rectangle, so a bbox check can never rule it
 * out. [TerrainFetch.obviouslyOutside] only catches the unambiguous
 * cases; everywhere in between, Wales included, only the fetch itself
 * — coming back either good or entirely empty — tells the truth.
 */
class TerrainFetchTest {

    // isXml and exceptionText are private; reflection keeps the test
    // honest about testing the real function rather than a copy of it.
    private fun call(name: String, vararg args: Any): Any? {
        val m: Method = TerrainFetch::class.java.getDeclaredMethod(
            name, *args.map { it::class.java }.toTypedArray(),
        )
        m.isAccessible = true
        return m.invoke(TerrainFetch, *args)
    }

    @Test fun `the request names the real coverage and the England CRS axes`() {
        val url = TerrainFetch.url(400_000.0, 430_000.0, 405_000.0, 435_000.0)
        assertTrue(url, url.contains("geoservices/datasets/"))
        assertTrue(url, url.contains("Lidar_Composite_Elevation_DTM_2m"))
        assertTrue(url, url.contains("version=2.0.1"))
        assertTrue(url, url.contains("format=image/tiff"))
        assertTrue(url, url.contains("subset=E(400000,405000)"))
        assertTrue(url, url.contains("subset=N(430000,435000)"))
    }

    @Test fun `a fetch is centred, not corner-anchored`() {
        val centre = En(400_000.0, 430_000.0)
        val half = TerrainFetch.SPAN_M / 2
        val url = TerrainFetch.url(
            centre.e - half, centre.n - half, centre.e + half, centre.n + half,
        )
        assertTrue(url, url.contains("subset=E(397500,402500)"))
        assertTrue(url, url.contains("subset=N(427500,432500)"))
    }

    @Test fun `the bbox check only rules out the unambiguous cases`() {
        assertFalse(TerrainFetch.obviouslyOutside(En(400_000.0, 430_000.0))) // Yorkshire
        assertFalse(TerrainFetch.obviouslyOutside(En(350_000.0, 400_000.0))) // Lancashire
        // Wales sits INSIDE England's own bounding rectangle — this must
        // NOT be flagged as obviously outside, because it is not: the
        // rectangle genuinely spans this point, and only the empty-grid
        // check after a real fetch can tell Anglesey from Yorkshire.
        assertFalse(TerrainFetch.obviouslyOutside(En(240_000.0, 380_000.0))) // Anglesey
        assertTrue(TerrainFetch.obviouslyOutside(En(250_000.0, 700_000.0))) // Highlands — north of the box
        assertTrue(TerrainFetch.obviouslyOutside(En(50_000.0, 30_000.0))) // west of the box, open sea
    }

    @Test fun `a grid entirely of no-data is recognised as no coverage here`() {
        // The real check for Wales, Scotland, or the sea: not the bbox,
        // which cannot see England's actual shape, but a well-formed
        // reply where every cell came back empty.
        val empty = Terrain.Grid(240_000.0, 380_000.0, 2.0, 10, 10, FloatArray(100) { Float.NaN })
        assertTrue(empty.heights.all { it.isNaN() })
    }

    @Test fun `a grid straddling the border is not mistaken for empty`() {
        // Partial coverage — real data for the English half of a square
        // near the border, no-data for the rest — is a genuine, useful
        // answer and must not be treated the same as nothing at all.
        val partial = Terrain.Grid(
            240_000.0, 380_000.0, 2.0, 10, 10,
            FloatArray(100) { i -> if (i < 50) 120.0f else Float.NaN },
        )
        assertFalse(partial.heights.all { it.isNaN() })
    }

    @Test fun `a GeoTIFF is not mistaken for a fault report`() {
        assertFalse(call("isXml", byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)) as Boolean)
        assertFalse(call("isXml", byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)) as Boolean)
    }

    @Test fun `an XML fault report is recognised, whitespace and all`() {
        val xml = "\n  <?xml version=\"1.0\"?><ows:ExceptionReport/>"
        assertTrue(call("isXml", xml.toByteArray()) as Boolean)
    }

    @Test fun `the fault's own reason is read back out, not a bare status code`() {
        val body = """
            <?xml version="1.0"?>
            <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows/2.0">
              <ows:Exception exceptionCode="InvalidAxisLabel">
                <ows:ExceptionText>Axis label 'E' is not valid for this coverage</ows:ExceptionText>
              </ows:Exception>
            </ows:ExceptionReport>
        """.trimIndent()
        val text = call("exceptionText", body.toByteArray())
        assertEquals("Axis label 'E' is not valid for this coverage", text)
    }

    @Test fun `no exception text at all is answered honestly, not with a crash`() {
        assertEquals(null, call("exceptionText", "<ows:ExceptionReport/>".toByteArray()))
    }
}
