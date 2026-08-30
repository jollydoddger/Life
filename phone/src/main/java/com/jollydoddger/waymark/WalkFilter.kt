package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Narrowing a pile of found walks to the ones that were actually asked for.
 * Pure arithmetic — no Context, no network — because "south east, 4 to 6
 * miles" turning into the wrong candidates is exactly the kind of quiet
 * failure that only a unit test catches.
 */
object WalkFilter {

    /** Degrees clockwise from north, 0..360. */
    fun bearingDeg(from: En, to: En): Double =
        (Math.toDegrees(atan2(to.e - from.e, to.n - from.n)) + 360.0) % 360.0

    private val COMPASS = mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
        "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
        "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )

    /**
     * Whether [deg] lies within ±45° of the compass word — generous on
     * purpose: "east" from a person planning a drive means the eastish half
     * of the world, not a surveyed sector.
     */
    fun compassMatches(word: String, deg: Double): Boolean {
        val want = COMPASS[normalise(word)] ?: return true
        val diff = ((deg - want + 540.0) % 360.0) - 180.0
        return kotlin.math.abs(diff) <= 45.0
    }

    /** "south-east", "South East", "se" → "SE". An unknown word matches
     *  everything rather than failing: a filter that cannot parse must not
     *  become a filter that hides every result. */
    private fun normalise(word: String): String {
        val w = word.trim().uppercase().replace("-", " ")
        if (w in COMPASS) return w
        val initials = StringBuilder()
        for (part in w.split(" ")) {
            when (part) {
                "" -> {}
                "NORTH" -> initials.append('N')
                "SOUTH" -> initials.append('S')
                "EAST" -> initials.append('E')
                "WEST" -> initials.append('W')
                else -> return w
            }
        }
        return initials.toString()
    }

    /** The walk's closest point to [p] — the honest anchor for "which way
     *  is it from here". */
    fun nearestPoint(p: En, lines: List<List<En>>): En {
        var best = p
        var bestD = Double.MAX_VALUE
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                val q = Geom.nearestOnSegment(p, line[i], line[i + 1])
                val d = hypot(q.e - p.e, q.n - p.n)
                if (d < bestD) { bestD = d; best = q }
            }
            if (line.size == 1) {
                val d = hypot(line[0].e - p.e, line[0].n - p.n)
                if (d < bestD) { bestD = d; best = line[0] }
            }
        }
        return best
    }

    /** Under this, direction-to-nearest-point is degenerate — a route whose
     *  line passes your feet is in every direction at once, and must not be
     *  filtered out for being in the "wrong" one. */
    private const val ANY_BEARING_M = 1_000.0

    fun filter(
        walks: List<RouteFinder.FoundWalk>,
        near: En,
        bearing: String?,
        minM: Double,
        maxM: Double,
    ): List<RouteFinder.FoundWalk> = walks.filter { w ->
        val lengthOk = w.lengthM >= minM && (maxM <= 0 || w.lengthM <= maxM)
        val bearingOk = bearing.isNullOrBlank() ||
            w.closestM <= ANY_BEARING_M ||
            compassMatches(bearing, bearingDeg(near, nearestPoint(near, w.lines)))
        lengthOk && bearingOk
    }
}
