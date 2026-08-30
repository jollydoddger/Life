package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The compass behind the sun overlay, in both matrix conventions.
 *
 * He pointed the phone at a sun he could plainly see and was told north;
 * after the first fix, roughly south whatever he did — the signature of the
 * matrix arriving inverted. So the code measures the convention against
 * gravity (Aim.vote) and these tests build fixtures in *both* conventions,
 * asserting the right answer pops out of the right branch.
 */
class AimTest {

    /**
     * The documented (device→world) rotation matrix for a phone whose back
     * camera points along [bearing], tilted [tilt] degrees above the
     * horizon, rolled [roll] degrees clockwise as the holder sees the
     * screen. Built the long way round from the geometry, so the test and
     * the code under test do not share an argument.
     */
    private fun matrix(bearing: Double, tilt: Double = 0.0, roll: Double = 0.0): FloatArray {
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )
        val b = Math.toRadians(bearing)
        val t = Math.toRadians(tilt)
        val p = Math.toRadians(roll)
        val cam = doubleArrayOf(sin(b) * cos(t), cos(b) * cos(t), sin(t))
        val rightRaw = cross(cam, doubleArrayOf(0.0, 0.0, 1.0))
        val len = sqrt(rightRaw[0] * rightRaw[0] + rightRaw[1] * rightRaw[1] + rightRaw[2] * rightRaw[2])
        val right = DoubleArray(3) { rightRaw[it] / len }
        val up = cross(right, cam)
        val rr = DoubleArray(3) { right[it] * cos(p) - up[it] * sin(p) }
        val uu = DoubleArray(3) { right[it] * sin(p) + up[it] * cos(p) }
        val z = DoubleArray(3) { -cam[it] }
        return floatArrayOf(
            rr[0].toFloat(), uu[0].toFloat(), z[0].toFloat(),
            rr[1].toFloat(), uu[1].toFloat(), z[1].toFloat(),
            rr[2].toFloat(), uu[2].toFloat(), z[2].toFloat(),
        )
    }

    /** The same attitude as the inverse matrix a misbehaving stack hands over. */
    private fun inverse(r: FloatArray) = floatArrayOf(
        r[0], r[3], r[6],
        r[1], r[4], r[7],
        r[2], r[5], r[8],
    )

    /** Gravity in device coordinates for an attitude: world up expressed in
     *  the device frame — the documented matrix's third row. */
    private fun gravityFor(r: FloatArray) = floatArrayOf(r[6] * 9.81f, r[7] * 9.81f, r[8] * 9.81f)

    @Test fun `documented matrix reads its bearing on the documented branch`() {
        for (b in intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)) {
            assertEquals(b.toDouble(), Aim.azimuth(matrix(b.toDouble()), mirrored = false), 0.01)
        }
    }

    @Test fun `inverse matrix reads its bearing on the mirrored branch`() {
        for (b in intArrayOf(0, 90, 135, 225, 315)) {
            assertEquals(b.toDouble(), Aim.azimuth(inverse(matrix(b.toDouble())), mirrored = true), 0.01)
        }
    }

    @Test fun `his exact case, in both conventions`() {
        // The sun 11 degrees up in the east at a quarter to eight, the phone
        // raised to it. One build said north, the next said south.
        val r = matrix(bearing = 90.0, tilt = 11.0)
        assertEquals(90.0, Aim.azimuth(r, mirrored = false), 0.01)
        assertEquals(11.0, Aim.elevation(r), 0.01)
        val inv = inverse(r)
        assertEquals(90.0, Aim.azimuth(inv, mirrored = true), 0.01)
        assertEquals(11.0, Aim.elevation(inv), 0.01)
    }

    @Test fun `elevation is convention-proof`() {
        // r[8] is the diagonal both conventions share — which is why the
        // height was always right on the phone while the bearing was not.
        for (t in intArrayOf(-20, 0, 30, 89)) {
            assertEquals(t.toDouble(), Aim.elevation(matrix(120.0, t.toDouble())), 0.01)
            assertEquals(t.toDouble(), Aim.elevation(inverse(matrix(120.0, t.toDouble()))), 0.01)
        }
    }

    @Test fun `roll follows the holder in both conventions`() {
        assertEquals(25.0, Aim.roll(matrix(0.0, 0.0, 25.0), mirrored = false), 0.01)
        assertEquals(-25.0, Aim.roll(matrix(0.0, 0.0, -25.0), mirrored = false), 0.01)
        assertEquals(25.0, Aim.roll(inverse(matrix(0.0, 0.0, 25.0)), mirrored = true), 0.01)
        // Rolling the phone must not swing the compass with it, either way.
        assertEquals(90.0, Aim.azimuth(matrix(90.0, 0.0, 40.0), mirrored = false), 0.01)
        assertEquals(90.0, Aim.azimuth(inverse(matrix(90.0, 0.0, 40.0)), mirrored = true), 0.01)
    }

    @Test fun `gravity votes for the convention that made the matrix`() {
        for (b in intArrayOf(0, 45, 90, 270, 315)) {
            for (t in intArrayOf(0, 25)) {
                val r = matrix(b.toDouble(), t.toDouble())
                val g = gravityFor(r)
                assertEquals(1L, Aim.vote(r, g[0], g[1], g[2]).toLong())
                assertEquals(-1L, Aim.vote(inverse(r), g[0], g[1], g[2]).toLong())
            }
        }
    }

    @Test fun `facing south the conventions coincide and the vote abstains`() {
        // At due south the matrix is its own inverse — the one attitude
        // where the two conventions are genuinely the same matrix. A sample
        // there must abstain; the running majority resolves it as soon as
        // the phone moves.
        val r = matrix(180.0)
        val g = gravityFor(r)
        assertEquals(0L, Aim.vote(r, g[0], g[1], g[2]).toLong())
    }

    @Test fun `free fall and degenerate attitudes abstain`() {
        val r = matrix(90.0)
        assertEquals(0L, Aim.vote(r, 0f, 0f, 0f).toLong())
        // Flat on a table, top north, the matrix is the identity — row and
        // column agree exactly, and the vote must abstain, not guess. (The
        // fixture cannot build this attitude: its right-vector cross
        // degenerates with the camera straight down, so it is written out.)
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(0L, Aim.vote(flat, 0f, 0f, 9.81f).toLong())
    }
}
