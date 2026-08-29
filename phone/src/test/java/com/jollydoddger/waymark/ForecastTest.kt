package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forecast field mixes arithmetic that must not drift apart: the lattice
 * is sampled where the renderer will put the pixels back, drizzle must be
 * drawn visibly rather than politely, and the label's words follow rules
 * worth pinning — "dry" said with a 60% rain chance would be the timeline
 * lying in its own caption.
 */
class ForecastTest {

    private val hour = 3_600_000L
    private val t0 = 1_700_000_000_000L

    private fun field(
        rain: Float = 0f,
        prob: Float = 0f,
        wind: Float = Float.NaN,
        dir: Float = Float.NaN,
        hours: Int = 3,
    ): Forecast.Field {
        val cells = Forecast.COLS * Forecast.ROWS
        return Forecast.Field(
            west = -4.6, south = 53.0, east = -4.0, north = 53.4,
            timesMs = LongArray(hours) { t0 + it * hour },
            rain = Array(hours) { FloatArray(cells) { rain } },
            probPct = Array(hours) { FloatArray(cells) { prob } },
            windMph = Array(hours) { FloatArray(cells) { wind } },
            windFromDeg = Array(hours) { FloatArray(cells) { dir } },
            fetchedAtMs = t0,
        )
    }

    @Test
    fun dryAirIsDrawnAsNothingAtAll() {
        assertEquals(0, Forecast.colourFor(0f))
        assertEquals(0, Forecast.colourFor(0.02f))
        assertEquals(0, Forecast.colourFor(Float.NaN))
    }

    @Test
    fun drizzleArrivesAlreadyVisible() {
        // The radar layer's hard-won lesson: light rain over pale OS paper
        // is exactly the rain worth warning about.
        val alpha = Forecast.colourFor(0.2f) ushr 24
        assertTrue("drizzle drawn at alpha $alpha — a polite wash vanishes", alpha >= 95)
    }

    @Test
    fun heavierRainDrawsHeavier() {
        val steps = listOf(0.1f, 0.5f, 2f, 8f).map { Forecast.colourFor(it) ushr 24 }
        for (i in 1 until steps.size) {
            assertTrue("alpha fell from ${steps[i - 1]} to ${steps[i]}", steps[i] > steps[i - 1])
        }
    }

    @Test
    fun anHourIsMatchedOnlyWithinTolerance() {
        val times = LongArray(3) { t0 + it * hour }
        assertEquals(0, Forecast.hourIndex(times, t0))
        assertEquals(1, Forecast.hourIndex(times, t0 + hour + 25 * 60_000L))
        assertEquals(2, Forecast.hourIndex(times, t0 + 2 * hour + 44 * 60_000L))
        // Past the slop the model has nothing to say about the moment.
        assertEquals(-1, Forecast.hourIndex(times, t0 + 2 * hour + 50 * 60_000L))
        assertEquals(-1, Forecast.hourIndex(times, t0 - hour))
        assertEquals(-1, Forecast.hourIndex(LongArray(0), t0))
    }

    @Test
    fun sampleAndRenderAgreeOnEveryCell() {
        // The lattice is sampled where the warped bitmap will put each pixel
        // back. If these two ever disagree, every cell's rain sits slightly
        // north or south of where the model said it falls.
        val f = field()
        for (r in 0 until Forecast.ROWS) {
            for (c in 0 until Forecast.COLS) {
                val lat = Forecast.latOfRow(f.south, f.north, r)
                val lon = Forecast.lonOfCol(f.west, f.east, c)
                assertTrue("row $r centre $lat outside the field", lat > f.south && lat < f.north)
                assertEquals(
                    "cell ($r,$c) does not round-trip",
                    r * Forecast.COLS + c,
                    Forecast.cellFor(f, lat, lon),
                )
            }
        }
        // Row 0 is the northmost, matching pixel row 0 of the bitmap.
        assertTrue(
            Forecast.latOfRow(f.south, f.north, 0) >
                Forecast.latOfRow(f.south, f.north, Forecast.ROWS - 1),
        )
    }

    @Test
    fun aPointOutsideTheFieldIsNobodysCell() {
        val f = field()
        assertEquals(-1, Forecast.cellFor(f, 52.0, -4.3))
        assertEquals(-1, Forecast.cellFor(f, 53.2, -3.0))
        assertNull(Forecast.describe(f, t0, 52.0, -4.3))
    }

    @Test
    fun theLabelSaysDryPossibleOrHowMuch() {
        assertEquals("dry", Forecast.describe(field(rain = 0f, prob = 10f), t0, 53.2, -4.3))
        assertEquals(
            "rain possible (60%)",
            Forecast.describe(field(rain = 0f, prob = 60f), t0, 53.2, -4.3),
        )
        assertEquals(
            "rain 0.6 mm (80%)",
            Forecast.describe(field(rain = 0.6f, prob = 80f), t0, 53.2, -4.3),
        )
        // A missing probability is left out, never invented.
        assertEquals(
            "rain 2.0 mm",
            Forecast.describe(field(rain = 2f, prob = Float.NaN), t0, 53.2, -4.3),
        )
        // A cell the model returned nothing for says nothing at all.
        assertNull(Forecast.describe(field(rain = Float.NaN), t0, 53.2, -4.3))
        // A moment past the model's reach likewise.
        assertNull(Forecast.describe(field(rain = 1f), t0 + 9 * hour, 53.2, -4.3))
    }

    @Test
    fun windArrowsSkipMissingDataAndThinTheLattice() {
        assertTrue(Forecast.arrows(field(wind = Float.NaN), t0).isEmpty())
        val arrows = Forecast.arrows(field(wind = 12f, dir = 270f), t0)
        // Every other lattice point: nine over a 6×6 field.
        assertEquals(9, arrows.size)
        for (a in arrows) {
            assertEquals(12.0, a.speedMph, 0.001)
            assertEquals(270.0, a.fromDeg, 0.001)
        }
    }
}
