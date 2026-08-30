package com.jollydoddger.waymark

import kotlin.math.asin
import kotlin.math.atan2

/**
 * Where the back camera is pointing, read straight off the rotation matrix.
 *
 * This used to go through `SensorManager.remapCoordinateSystem` and
 * `getOrientation`, which is the recipe everyone quotes for augmented
 * reality — and which put the compass about ninety degrees out on his phone:
 * pointing at the sun in the east, the overlay called it north and drew the
 * sun off to one side. The remap is a permutation of axes described in prose,
 * and prose is exactly the wrong medium for "which way is the camera facing"
 * when a wrong answer still looks like a plausible compass.
 *
 * So the question is asked directly instead. The rotation matrix maps device
 * coordinates to the world's (east, north, up); the back camera looks along
 * the device's negative Z; therefore the camera direction in the world is the
 * third column of that matrix, negated. There is nothing to get subtly
 * backwards, and every case below is checked in `AimTest`.
 */
object Aim {

    /** Camera bearing in degrees, 0 north through 90 east. */
    fun azimuth(r: FloatArray): Double =
        (Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())) + 360.0) % 360.0

    /** How far above the horizon the camera is aimed, negative for below. */
    fun elevation(r: FloatArray): Double =
        Math.toDegrees(asin((-r[8]).toDouble().coerceIn(-1.0, 1.0)))

    /**
     * How far the phone is rolled, clockwise positive as the person holding
     * it sees the screen — which is the amount the overlay's horizon must
     * turn to stay level with the real one.
     */
    fun roll(r: FloatArray): Double =
        Math.toDegrees(atan2(-r[6].toDouble(), r[7].toDouble()))
}
