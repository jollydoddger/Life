package com.jollydoddger.waymark

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the back camera is pointing, read off the rotation matrix — under
 * whichever convention this phone actually delivers it in.
 *
 * Two rounds of being wrong bought this design. The documented recipe
 * (`remapCoordinateSystem` + `getOrientation`) read ninety degrees low.
 * Reading the camera direction directly off the matrix's third column —
 * correct under the documented device-to-world convention, and proven so in
 * tests — then read wrong on the real phone in exactly the way the
 * *inverse* matrix (world-to-device) would produce: bearing stuck near
 * south whenever the phone was upright, while the elevation stayed right,
 * because the one element both conventions share is the diagonal `r[8]`.
 *
 * So the convention is no longer assumed; it is measured. Gravity in device
 * coordinates is unambiguous — flat on a table it is (0, 0, +g) on every
 * Android phone — and equals world-up expressed in the device frame, which
 * is the third **row** of the documented matrix and the third **column** of
 * its inverse. [vote] compares the live gravity vector against both and
 * says which matrix this phone is actually handing over; the aim formulas
 * then read the camera direction (device −Z, in world) from the right
 * place. Every branch has cases in `AimTest`, built in both conventions.
 */
object Aim {

    /** Camera bearing in degrees, 0 north through 90 east.
     *  [mirrored] = the matrix arrives inverted (world→device). */
    fun azimuth(r: FloatArray, mirrored: Boolean): Double {
        val east = if (mirrored) -r[6] else -r[2]
        val north = if (mirrored) -r[7] else -r[5]
        return (Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360.0) % 360.0
    }

    /** How far above the horizon the camera is aimed, negative for below.
     *  The diagonal element is the same under both conventions — which is
     *  why the height was always right while the bearing was not. */
    fun elevation(r: FloatArray): Double =
        Math.toDegrees(asin((-r[8]).toDouble().coerceIn(-1.0, 1.0)))

    /** How far the phone is rolled, clockwise positive as the person
     *  holding it sees the screen. */
    fun roll(r: FloatArray, mirrored: Boolean): Double {
        val a = if (mirrored) -r[2] else -r[6]
        val b = if (mirrored) r[5] else r[7]
        return Math.toDegrees(atan2(a.toDouble(), b.toDouble()))
    }

    /**
     * One observation's verdict on the convention: +1 documented, −1
     * mirrored, 0 too close to call (gravity near a degenerate attitude, or
     * the phone in free fall). Callers keep a running majority — a single
     * noisy sample must not flip the compass mid-walk.
     *
     * The genuine blind spot: facing due south, the matrix equals its own
     * inverse and the two conventions are the same matrix, so no sample
     * there can tell them apart. The majority resolves it the moment the
     * phone points anywhere else.
     */
    fun vote(r: FloatArray, gx: Float, gy: Float, gz: Float): Int {
        val m = sqrt(gx * gx + gy * gy + gz * gz)
        if (m < 1f) return 0
        val row = (gx * r[6] + gy * r[7] + gz * r[8]) / m
        val col = (gx * r[2] + gy * r[5] + gz * r[8]) / m
        return when {
            row - col > 0.2f -> 1
            col - row > 0.2f -> -1
            else -> 0
        }
    }
}
