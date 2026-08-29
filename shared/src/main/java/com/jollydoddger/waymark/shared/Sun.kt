package com.jollydoddger.waymark.shared

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, and when it does things — computed here rather than
 * asked of a server.
 *
 * The NOAA solar position algorithm: accurate to well under a degree for
 * any date this app will see, which is far better than a phone's compass,
 * and it works on a hillside with no signal. That last part is the reason
 * it is local: "when does it get dark" is exactly the question you ask
 * where there is no bar of reception to look it up with.
 *
 * Azimuths are degrees clockwise from **true** north (not grid, not
 * magnetic — the caller converts). Elevation is degrees above the horizon,
 * negative below it.
 */
object Sun {

    data class Position(val azimuth: Double, val elevation: Double)

    /** Sun's centre is this far below the horizon at sunrise/sunset — the
     *  standard allowance for refraction plus the disc's own radius. */
    const val HORIZON = -0.833

    /** The warm hour photographers mean: sun below this but still up. */
    const val GOLDEN = 6.0

    /** Civil twilight: the end of useful walking light. */
    const val CIVIL = -6.0

    fun positionAt(timeMs: Long, lat: Double, lon: Double): Position {
        val jd = timeMs / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0

        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) +
            sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))

        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0
        val e = e0 + 0.00256 * cos(Math.toRadians(omega))
        val decl = Math.toDegrees(
            asin(sin(Math.toRadians(e)) * sin(Math.toRadians(appLong))),
        )

        // Equation of time: the sun is not a clock, and the difference runs
        // to a quarter of an hour.
        val y = tan(Math.toRadians(e / 2)).let { it * it }
        val l0r = Math.toRadians(l0)
        val eo = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val eqTime = Math.toDegrees(
            y * sin(2 * l0r) - 2 * eo * sin(mr) +
                4 * eo * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * eo * eo * sin(2 * mr),
        ) * 4

        val utcMinutes = (timeMs / 60_000.0).mod(1440.0)
        val trueSolar = (utcMinutes + eqTime + 4 * lon).mod(1440.0)
        var ha = trueSolar / 4 - 180
        if (ha < -180) ha += 360

        val har = Math.toRadians(ha)
        val latr = Math.toRadians(lat)
        val declr = Math.toRadians(decl)
        val cosZen = (sin(latr) * sin(declr) + cos(latr) * cos(declr) * cos(har))
            .coerceIn(-1.0, 1.0)
        val zen = acos(cosZen)
        val elevation = 90 - Math.toDegrees(zen)

        val den = cos(latr) * sin(zen)
        val azimuth = if (abs(den) < 1e-9) {
            180.0 // straight overhead or at a pole; the direction is moot
        } else {
            val ratio = ((sin(latr) * cos(zen) - sin(declr)) / den).coerceIn(-1.0, 1.0)
            val a = Math.toDegrees(acos(ratio))
            (if (ha > 0) 180 + a else 180 - a).mod(360.0)
        }
        return Position(azimuth, elevation)
    }

    /**
     * When the sun crosses [targetElevation] on the UTC day beginning at
     * [dayStartMs] — [evening] picks the descending crossing.
     *
     * Null when it never crosses: in a British summer that is only ever a
     * far-northern edge case, but "the sun does not set today" is a real
     * answer and inventing a time for it would be worse than saying so.
     */
    fun event(
        dayStartMs: Long,
        lat: Double,
        lon: Double,
        targetElevation: Double,
        evening: Boolean,
    ): Long? {
        var lo = if (evening) dayStartMs + 12 * 3_600_000L else dayStartMs
        var hi = if (evening) dayStartMs + 24 * 3_600_000L else dayStartMs + 12 * 3_600_000L
        val loAbove = positionAt(lo, lat, lon).elevation > targetElevation
        val hiAbove = positionAt(hi, lat, lon).elevation > targetElevation
        if (loAbove == hiAbove) return null // no crossing in this half of the day
        repeat(50) {
            val mid = lo + (hi - lo) / 2
            val above = positionAt(mid, lat, lon).elevation > targetElevation
            if (above == loAbove) lo = mid else hi = mid
        }
        return lo + (hi - lo) / 2
    }

    /** Midnight UTC of the day containing [timeMs]. */
    fun dayStart(timeMs: Long): Long = timeMs - timeMs.mod(86_400_000L)

    fun sunrise(timeMs: Long, lat: Double, lon: Double): Long? =
        event(dayStart(timeMs), lat, lon, HORIZON, false)

    fun sunset(timeMs: Long, lat: Double, lon: Double): Long? =
        event(dayStart(timeMs), lat, lon, HORIZON, true)

    fun goldenHourStart(timeMs: Long, lat: Double, lon: Double): Long? =
        event(dayStart(timeMs), lat, lon, GOLDEN, true)

    fun civilDusk(timeMs: Long, lat: Double, lon: Double): Long? =
        event(dayStart(timeMs), lat, lon, CIVIL, true)

    private val POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** A bearing as the compass point a person would actually say. */
    fun compass(azimuth: Double): String =
        POINTS[(((azimuth.mod(360.0)) + 11.25) / 22.5).toInt() % 16]
}
