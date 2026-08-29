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

    /**
     * The way back: grid metres → WGS84 degrees. Every external service the
     * assistant talks to — Overpass, the routing engine, the weather — speaks
     * lat/lon, and the app speaks grid; this is the bridge out. Inverse
     * transverse Mercator (OS guide C.3, iterating the meridional arc) then
     * the Helmert reversed. Round-trips with [fromWgs84] to millimetres
     * (BngTest proves it).
     */
    fun toWgs84(en: En): Pair<Double, Double> {
        val e2 = 1 - (B * B) / (A * A)
        val n = (A - B) / (A + B)

        // Iterate latitude until the meridional arc matches the northing.
        var lat = LAT0
        var m = 0.0
        do {
            lat += (en.n - N0 - m) / (A * F0)
            val dLat = lat - LAT0
            val pLat = lat + LAT0
            m = B * F0 * (
                (1 + n + 1.25 * n * n + 1.25 * n * n * n) * dLat -
                    (3 * n + 3 * n * n + 21.0 / 8 * n * n * n) * sin(dLat) * cos(pLat) +
                    (15.0 / 8 * n * n + 15.0 / 8 * n * n * n) * sin(2 * dLat) * cos(2 * pLat) -
                    35.0 / 24 * n * n * n * sin(3 * dLat) * cos(3 * pLat)
                )
        } while (kotlin.math.abs(en.n - N0 - m) >= 1e-5)

        val sinL = sin(lat)
        val cosL = cos(lat)
        val tanL = tan(lat)
        val nu = A * F0 / sqrt(1 - e2 * sinL * sinL)
        val rho = A * F0 * (1 - e2) / (1 - e2 * sinL * sinL).pow(1.5)
        val eta2 = nu / rho - 1

        val vii = tanL / (2 * rho * nu)
        val viii = tanL / (24 * rho * nu.pow(3)) * (5 + 3 * tanL * tanL + eta2 - 9 * tanL * tanL * eta2)
        val ix = tanL / (720 * rho * nu.pow(5)) * (61 + 90 * tanL * tanL + 45 * tanL.pow(4))
        val x = 1 / (cosL * nu)
        val xi = 1 / (cosL * 6 * nu.pow(3)) * (nu / rho + 2 * tanL * tanL)
        val xii = 1 / (cosL * 120 * nu.pow(5)) * (5 + 28 * tanL * tanL + 24 * tanL.pow(4))
        val xiia = 1 / (cosL * 5040 * nu.pow(7)) *
            (61 + 662 * tanL * tanL + 1320 * tanL.pow(4) + 720 * tanL.pow(6))

        val dE = en.e - E0
        val latO = lat - vii * dE * dE + viii * dE.pow(4) - ix * dE.pow(6)
        val lonO = LON0 + x * dE - xi * dE.pow(3) + xii * dE.pow(5) - xiia * dE.pow(7)

        // OSGB36 geodetic → cartesian → reverse Helmert → WGS84 geodetic.
        val sl = sin(latO)
        val cl = cos(latO)
        val nuA = A / sqrt(1 - e2 * sl * sl)
        val cx = nuA * cl * cos(lonO)
        val cy = nuA * cl * sin(lonO)
        val cz = (1 - e2) * nuA * sl

        val s = -20.4894e-6
        val rx = -Math.toRadians(-0.1502 / 3600)
        val ry = -Math.toRadians(-0.2470 / 3600)
        val rz = -Math.toRadians(-0.8421 / 3600)
        val x2 = 446.448 + (1 + s) * cx - rz * cy + ry * cz
        val y2 = -125.157 + rz * cx + (1 + s) * cy - rx * cz
        val z2 = 542.060 - ry * cx + rx * cy + (1 + s) * cz

        val b84 = A84 * (1 - F84)
        val e2w = 1 - (b84 * b84) / (A84 * A84)
        val p = hypot(x2, y2)
        var lat84 = atan2(z2, p * (1 - e2w))
        repeat(8) {
            val nu84 = A84 / sqrt(1 - e2w * sin(lat84) * sin(lat84))
            lat84 = atan2(z2 + e2w * nu84 * sin(lat84), p)
        }
        return Math.toDegrees(lat84) to Math.toDegrees(atan2(y2, x2))
    }

    /**
     * The National Grid reference for a point — "SH 51792 71523" — which is
     * the "where am I" a paper map, a signpost, or mountain rescue actually
     * uses. Null outside the lettered grid.
     */
    fun gridRef(en: En, digits: Int = 5): String? {
        val e100k = kotlin.math.floor(en.e / 100_000).toInt()
        val n100k = kotlin.math.floor(en.n / 100_000).toInt()
        if (e100k < 0 || e100k > 6 || n100k < 0 || n100k > 12) return null
        val letters = "ABCDEFGHJKLMNOPQRSTUVWXYZ" // no I, as on the maps
        val idx1 = (19 - n100k) - (19 - n100k) % 5 + (e100k + 10) / 5
        val idx2 = (19 - n100k) * 5 % 25 + e100k % 5
        val scale = 10.0.pow(5 - digits)
        val ee = ((en.e % 100_000) / scale).toInt()
        val nn = ((en.n % 100_000) / scale).toInt()
        return "%s%s %0${digits}d %0${digits}d".format(letters[idx1], letters[idx2], ee, nn)
    }
}
