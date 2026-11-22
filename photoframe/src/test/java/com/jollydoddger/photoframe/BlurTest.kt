package com.jollydoddger.photoframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {
    private fun r(c: Int) = (c shr 16) and 0xFF

    @Test fun flatImageStaysFlat() {
        val px = IntArray(20 * 10) { 0xFF808080.toInt() }
        BitmapLoader.boxBlur(px, 20, 10, 3, horizontal = true)
        BitmapLoader.boxBlur(px, 20, 10, 3, horizontal = false)
        assertTrue(px.all { it == 0xFF808080.toInt() })
    }

    @Test fun brightPixelSpreadsEvenly() {
        val w = 15; val h = 1
        val px = IntArray(w) { 0xFF000000.toInt() }
        px[7] = 0xFFFF0000.toInt() // red 255
        BitmapLoader.boxBlur(px, w, h, 2, horizontal = true)
        // radius 2 → 5 neighbours share it: 255/5 = 51
        assertEquals(listOf(0, 0, 0, 0, 0, 51, 51, 51, 51, 51, 0, 0, 0, 0, 0), px.map { r(it) })
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }
}
