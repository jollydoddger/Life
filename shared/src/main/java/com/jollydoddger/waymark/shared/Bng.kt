package com.jollydoddger.waymark.shared

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in British National Grid metres (easting / northing). */
data class En(val e: Double, val n: Double)

/**
 * WGS84 → OSGB36 / British National Grid, straight from Ordnance Survey's
 * "A guide to coordinate systems in Great Britain": geodetic → cartesian on
 * WGS84, a Helmert transformation onto the OSGB36 datum, then the Airy 1830
 * transverse Mercator projection. Verified to the millimetre against OS's own
 * worked example (see BngTest). The single Helmert is good to ~3 m nationally,
 * which is well inside a walker's GPS error.
 *
 * The map, the route and the arrow all live in these coordinates; WGS84 exists
 * only at the edges (a GPS fix, a GPX file) and is converted once on the way in.
 */
object Bng {

    // Airy 1830 ellipsoid and National Grid projection constants.
    private const val A = 6377563.396
    private const val B = 6356256.909
    private const val F0 = 0.9996012717
    private val LAT0 = Math.toRadians(49.0)
    private val LON0 = Math.toRadians(-2.0)
    private const val E0 = 400000.0
    private const val N0 = -100000.0

    // WGS84 ellipsoid.
    private const val A84 = 6378137.0
    private const val F84 = 1.0 / 298.257223563

    /** Degrees in, National Grid metres out. */
    fun fromWgs84(latDeg: Double, lonDeg: Double): En {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        // Geodetic → cartesian on WGS84 (height 0 — irrelevant at map scale).
        val b84 = A84 * (1 - F84)
        val e2w = 1 - (b84 * b84) / (A84 * A84)
        val sl = sin(lat)
        val cl = cos(lat)
        val nu84 = A84 / sqrt(1 - e2w * sl * sl)
        var x = nu84 * cl * cos(lon)
        var y = nu84 * cl * sin(lon)
        var z = (1 - e2w) * nu84 * sl

        // Helmert onto OSGB36 (OS's published small-angle transformation).
        val s = 20.4894e-6
        val rx = Math.toRadians(-0.1502 / 3600)
        val ry = Math.toRadians(-0.2470 / 3600)
        val rz = Math.toRadians(-0.8421 / 3600)
        val x2 = -446.448 + (1 + s) * x - rz * y + ry * z
        val y2 = 125.157 + rz * x + (1 + s) * y - rx * z
        val z2 = -542.060 - ry * x + rx * y + (1 + s) * z

        // Cartesian → geodetic on Airy 1830.
        val e2 = 1 - (B * B) / (A * A)
        val p = hypot(x2, y2)
        var latO = atan2(z2, p * (1 - e2))
        repeat(8) {
            val nu = A / sqrt(1 - e2 * sin(latO) * sin(latO))
            latO = atan2(z2 + e2 * nu * sin(latO), p)
        }
        val lonO = atan2(y2, x2)

        return project(latO, lonO)
    }

    /** OSGB36 radians in, National Grid metres out (the OS worked example runs through here). */
    internal fun project(lat: Double, lon: Double): En {
        val e2 = 1 - (B * B) / (A * A)
        val n = (A - B) / (A + B)
        val sinL = sin(lat)
        val cosL = cos(lat)
        val tanL = tan(lat)
        val nu = A * F0 / sqrt(1 - e2 * sinL * sinL)
        val rho = A * F0 * (1 - e2) / (1 - e2 * sinL * sinL).pow(1.5)
        val eta2 = nu / rho - 1

        val dLat = lat - LAT0
        val pLat = lat + LAT0
        val m = B * F0 * (
            (1 + n + 1.25 * n * n + 1.25 * n * n * n) * dLat -
                (3 * n + 3 * n * n + 21.0 / 8 * n * n * n) * sin(dLat) * cos(pLat) +
                (15.0 / 8 * n * n + 15.0 / 8 * n * n * n) * sin(2 * dLat) * cos(2 * pLat) -
                35.0 / 24 * n * n * n * sin(3 * dLat) * cos(3 * pLat)
            )

        val i = m + N0
        val ii = nu / 2 * sinL * cosL
        val iii = nu / 24 * sinL * cosL.pow(3) * (5 - tanL * tanL + 9 * eta2)
        val iiia = nu / 720 * sinL * cosL.pow(5) * (61 - 58 * tanL * tanL + tanL.pow(4))
        val iv = nu * cosL
        val v = nu / 6 * cosL.pow(3) * (nu / rho - tanL * tanL)
        val vi = nu / 120 * cosL.pow(5) *
            (5 - 18 * tanL * tanL + tanL.pow(4) + 14 * eta2 - 58 * tanL * tanL * eta2)

        val dl = lon - LON0
        return En(
            e = E0 + iv * dl + v * dl.pow(3) + vi * dl.pow(5),
            n = i + ii * dl * dl + iiia * dl.pow(6) + iii * dl.pow(4),
        )
    }

    /**
     * Grid convergence at a WGS84 position, in degrees: (λ−λ0)·sinφ, positive
     * east of the 2°W central meridian, where true north lies west of grid
     * north (up to ~3° at the edges of GB). A true-north compass heading
     * becomes a grid bearing by SUBTRACTING this. The first-order formula is
     * good to well under a tenth of a degree.
     */
    fun convergenceDeg(latDeg: Double, lonDeg: Double): Double {
        val c = Math.toRadians(lonDeg - -2.0) * sin(Math.toRadians(latDeg))
        return Math.toDegrees(c)
    }
}
