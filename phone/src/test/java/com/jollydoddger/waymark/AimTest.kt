package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The compass behind the sun overlay. He pointed the phone at a sun he could
 * plainly see in the east and was told he was facing north — so "which way is
 * this camera pointing" is now a function with cases, rather than an axis
 * permutation nobody can check by reading it.
 */
class AimTest {

    /**
     * The rotation matrix a phone would report when its back camera points
     * along [bearing], tilted [tilt] degrees above the horizon and rolled
     * [roll] degrees clockwise as the holder sees the screen.
     *
     * Built the long way round from the geometry — camera direction, then the
     * right and up edges of the phone perpendicular to it — so that the test
     * and the code under test do not share an argument.
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
        // The camera direction in the world's east, north, up.
        val cam = doubleArrayOf(sin(b) * cos(t), cos(b) * cos(t), sin(t))
        val rightRaw = cross(cam, doubleArrayOf(0.0, 0.0, 1.0))
        val len = sqrt(rightRaw[0] * rightRaw[0] + rightRaw[1] * rightRaw[1] + rightRaw[2] * rightRaw[2])
        val right = DoubleArray(3) { rightRaw[it] / len }
        val up = cross(right, cam)
        // Roll turns the phone's right and up edges about the camera axis.
        val rr = DoubleArray(3) { right[it] * cos(p) - up[it] * sin(p) }
        val uu = DoubleArray(3) { right[it] * sin(p) + up[it] * cos(p) }
        val z = DoubleArray(3) { -cam[it] }
        // Row-major, columns being the device's x, y and z axes in the world.
        return floatArrayOf(
            rr[0].toFloat(), uu[0].toFloat(), z[0].toFloat(),
            rr[1].toFloat(), uu[1].toFloat(), z[1].toFloat(),
            rr[2].toFloat(), uu[2].toFloat(), z[2].toFloat(),
        )
    }

    @Test fun `the camera bearing is the bearing it is pointed at`() {
        for (b in intArrayOf(0, 45, 90, 135, 180, 225, 270, 315)) {
            assertEquals(b.toDouble(), Aim.azimuth(matrix(b.toDouble())), 0.01)
        }
    }

    @Test fun `pointing east at a sun in the east reads east`() {
        // His case exactly: the sun 11 degrees up in the east at a quarter to
        // eight, phone raised to it. The overlay said north.
        val r = matrix(bearing = 90.0, tilt = 11.0)
        assertEquals(90.0, Aim.azimuth(r), 0.01)
        assertEquals(11.0, Aim.elevation(r), 0.01)
    }

    @Test fun `tilt reads as height above the horizon`() {
        assertEquals(0.0, Aim.elevation(matrix(0.0, 0.0)), 0.01)
        assertEquals(30.0, Aim.elevation(matrix(90.0, 30.0)), 0.01)
        assertEquals(-20.0, Aim.elevation(matrix(180.0, -20.0)), 0.01)
        // Straight up is the one place bearing stops meaning anything; the
        // height must still be right.
        assertEquals(89.0, Aim.elevation(matrix(270.0, 89.0)), 0.01)
    }

    @Test fun `roll is clockwise as the holder sees it`() {
        assertEquals(0.0, Aim.roll(matrix(0.0)), 0.01)
        assertEquals(25.0, Aim.roll(matrix(0.0, 0.0, 25.0)), 0.01)
        assertEquals(-25.0, Aim.roll(matrix(0.0, 0.0, -25.0)), 0.01)
        // Rolling the phone must not swing the compass with it.
        assertEquals(90.0, Aim.azimuth(matrix(90.0, 0.0, 40.0)), 0.01)
    }

    @Test fun `bearing wraps rather than going negative`() {
        // 350 must not come back as -10: the overlay subtracts bearings and
        // wraps the result itself.
        assertEquals(350.0, Aim.azimuth(matrix(350.0)), 0.01)
        assertEquals(0.0, Aim.azimuth(matrix(360.0)), 0.01)
    }
}
