package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GeoTIFF reader, against a real one.
 *
 * [FIXTURE] is a genuine LZW-compressed, tiled, single-band float32 GeoTIFF
 * built to the same recipe the Environment Agency publish — 20x20 cells in
 * 16x16 tiles, so the right and bottom edges are padded exactly as a 2500
 * wide square padded to 128-cell tiles is, with one no-data cell and a
 * British National Grid tiepoint. Heights are `row * 10 + col`, so every
 * cell states where it belongs and nothing can be transposed, flipped or
 * shifted without a test failing.
 *
 * The reader was developed against a real 5 km square (SE03se, 2 m, 2022):
 * 400 of 400 tiles, 6,250,000 cells, min/max/mean 197.81 / 419.63 / 315.87
 * matching the statistics the file carries about itself. This fixture is
 * what keeps it there.
 */
class TerrainTiffTest {

    private val FIXTURE: ByteArray = java.util.Base64.getDecoder().decode(
            "SUkqAHIEAACAACBQNAD+BkCBECEABAQtQQtgQtwQsAEGBBCLAAQRkYRkgRkoRkwRk4RmNwKOwmQS" +
            "KSRlARlERlIRlMRlQRlURlYRlcRlgRlkTacTqeT6gRloRlsRlwRl0Rl4Rl8RkAEKBASrAAEVkGVm" +
            "owKpwOs1iBVuBV2L1kKVkMVkOVkQVkSVkUVkWVkYVkaXC5XS7Xi9VkcVkeVkgVkiVkkVkmVkoVkq" +
            "VksVkuYrGY7IZIhP9/n9/wIwVkyVk0Vk2Vk4Vk6Vk8Vk+VlAVlBajVazXbDZVlCVlDVlEVlFVlGV" +
            "lHVlIVlJVlKVlLcLicbkcrmVlMVlNVlOVlPVlQVlRVlSVlTVlUVlVdzveDxeTzVlWVlXVlYVlZVl" +
            "aVlbVlcVldVleKyXz7Pw/T+P9ACsmArJhKyYismMrJkKyZSsmYrJnKyaCsmlCEJQpC0MQ0rJqKya" +
            "ysmwrJtKybism8rJwKycSsnIrJzRVFkXRhGUaKydCsnUrJ2KydyvKyeSsnorJ7KyfCsn1IiBSMgU" +
            "kIFJSwSarJ+KyfysgAIaBADLwAAFMIBzCAkwgLMIDTCA8wzGgUyqvNE1TZMIETCBMwgVMIFzCBkw" +
            "gbMIHTCB8wghMIIz9QFBUJQ1ETCCUwgnMIKTCCswgtMILzCDEwgzMINTCDdMU1TlPVBUUwg5MIOz" +
            "CD0wg/MIQTCEMwhFMIRzCEkwhKIaAoAAAAgCDAkRBQAkIQmIRAodD4hEYlE4pD2hCGxCHBCHRDYr" +
            "H5BIQAECFAgpJQAGJQHJRIpdL4cOJQPJQQJQRJbMJ1IDBKDJKDRKDZOZ3RYkhJQhpQiJQiqJRqhA" +
            "kxKE1KE5KE9T6jRlZKFdKFhKFlWq3O2BKGFKGJKGNZLLMGpKGtKGxKG1brfLnRKHVKHZKHdeLzIX" +
            "5KH9LSHAgDicHRgRjATjAVjAXjMbOwljAnjApjArlsvMA5jA7jA9jA/oNDLhNjBPjBRjBTqtXH4C" +
            "gAAAAgIcCEMFAAihAjhAkhAlhAmhAnhAohAphAqhArhAshAthAuhAvjMbjsfkMjhAwhAxhAyhAzh" +
            "A0hA1hA2hA3hA4hA5mc1m85nc9hA6hA7hA8hA9hA+hA/hBAhBBhBChBDptPqNTqtXhBEhBFhBGhB" +
            "HhBIhBJhBKhBLhBMhBNhECul1u13vF5vV7vl9v1/wGBwWDwmFw2HxGJxWLxmNx2PyGRyWTymVy2X" +
            "xcBAgAAAAwIcCGMFAAyhAzhECh0PiERiUTikPHUIHcIHkIHsNisfkEhABEhBFhBGhBHj0ilktABO" +
            "hBPhBQhBRlcunE5nU7nk9n0/oFBoVDolFo1HpFJpVLplNp1PqFRAEBAIAAAAFwIAAOUCAACTAwAA" +
            "DwIAAM4AAACuAAAAXwAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAguBhBAAAAAOCMGkEAAAAAAAAAAC0zLjQwMjgyMzQ2NjM4NTI4ODZlKzM4ABAA" +
            "AAEDAAEAAAAUAAAAAQEDAAEAAAAUAAAAAgEDAAEAAAAgAAAAAwEDAAEAAAAFAAAABgEDAAEAAAAB" +
            "AAAAFQEDAAEAAAABAAAAHAEDAAEAAAABAAAAPQEDAAEAAAABAAAAQgEDAAEAAAAQAAAAQwEDAAEA" +
            "AAAQAAAARAEEAAQAAADyAwAARQEEAAQAAAACBAAAUwEDAAEAAAADAAAADoMMAAMAAAASBAAAgoQM" +
            "AAYAAAAqBAAAgaQCABgAAABaBAAAAAAAAA=="
    )

    private fun grid() = TerrainTiff.read(FIXTURE)

    @Test fun `the square is read at the size and place it claims`() {
        val g = grid()
        assertEquals(20, g.cols)
        assertEquals(20, g.rows)
        assertEquals(2.0, g.cellSize, 0.0)
        // Tiepoint is the top-left; south is that less the height.
        assertEquals(405_000.0, g.west, 1e-6)
        assertEquals(435_000.0, g.north, 1e-6)
        assertEquals(435_000.0 - 20 * 2.0, g.south, 1e-6)
    }

    @Test fun `every cell lands where it belongs`() {
        // Heights encode their own position, so a transpose, a flip or an
        // off-by-one row would fail here rather than look like countryside.
        val g = grid()
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                if (r == 5 && c == 5) continue
                assertEquals("cell $r,$c", (r * 10 + c).toFloat(), g.at(r, c), 0.001f)
            }
        }
    }

    @Test fun `no-data is nothing, not a height`() {
        assertTrue(grid().at(5, 5).isNaN())
    }

    @Test fun `a tile's edge padding is discarded, not believed`() {
        // 20 cells in 16-cell tiles leaves 12 columns and 12 rows of zero
        // padding in the last tile row and column. Kept, a band of 0 m down
        // the east edge of a Pennine square shades as a sea cliff.
        val g = grid()
        assertEquals(20, g.cols)
        assertEquals(20, g.rows)
        // The last real column is 19, and it holds its own value, not zero.
        assertEquals((0 * 10 + 19).toFloat(), g.at(0, 19), 0.001f)
        assertEquals((19 * 10 + 19).toFloat(), g.at(19, 19), 0.001f)
    }

    @Test fun `the LZW dictionary survives the code it has not built yet`() {
        // The one branch that is not a straight lookup, and the one that
        // decodes plausibly for a while before going wrong. If the first
        // byte were prepended rather than appended, the ramp above breaks.
        val g = grid()
        assertEquals(0f, g.at(0, 0), 0.001f)
        assertEquals(209f, g.at(19, 19), 0.001f)
    }

    @Test fun `a file that is not a TIFF says so in words`() {
        val e = runCatching { TerrainTiff.read("not a tiff at all".toByteArray()) }.exceptionOrNull()
        assertTrue(e is TerrainTiff.Unreadable)
        assertTrue(e!!.message!!.contains("TIFF"))
    }

    @Test fun `the grid reads back through the terrain helpers`() {
        val g = grid()
        val shade = Terrain.hillshade(g)
        assertEquals(g.cols * g.rows, shade.size)
        // The no-data cell is transparent, everything else is lit.
        assertEquals(-1, shade[5 * g.cols + 5])
        assertTrue(shade.count { it >= 0 } == g.cols * g.rows - 1)
    }
}
