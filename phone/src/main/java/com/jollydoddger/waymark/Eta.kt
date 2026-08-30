package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import kotlin.math.hypot

/**
 * The arithmetic behind "how long until I'm there": distance along the
 * route, climb between two points of it, and a time built from *his* pace
 * rather than a textbook's. Pure functions, because a wrong answer here is
 * a missed turn on a hillside and only a unit test catches it at a desk.
 */
object Eta {

    /** Cumulative metres along a polyline, one entry per point. */
    fun cumulative(points: List<En>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + hypot(
                points[i].e - points[i - 1].e, points[i].n - points[i - 1].n,
            )
        }
        return cum
    }

    /** Index of the polyline point nearest [p]. */
    fun nearestIndex(points: List<En>, p: En): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in points.indices) {
            val d = hypot(points[i].e - p.e, points[i].n - p.n)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * Metres of up and of down walking from [fromM] to [toM] along a route
     * whose elevation was sampled at [alongs] metres (ascending) with
     * heights [heights]. Walking the other way simply swaps the two — which
     * falls out of walking the samples in the travel direction rather than
     * being special-cased.
     */
    fun climbBetween(
        alongs: DoubleArray,
        heights: DoubleArray,
        fromM: Double,
        toM: Double,
    ): Pair<Double, Double> {
        if (alongs.size < 2 || alongs.size != heights.size) return 0.0 to 0.0
        fun heightAt(m: Double): Double {
            if (m <= alongs.first()) return heights.first()
            if (m >= alongs.last()) return heights.last()
            var i = 1
            while (alongs[i] < m) i++
            val span = alongs[i] - alongs[i - 1]
            val t = if (span <= 0) 0.0 else (m - alongs[i - 1]) / span
            return heights[i - 1] + t * (heights[i] - heights[i - 1])
        }
        val a = minOf(fromM, toM)
        val b = maxOf(fromM, toM)
        // Heights strictly inside the span, bracketed by the endpoints'
        // interpolated heights.
        val walk = ArrayList<Double>()
        walk.add(heightAt(if (fromM <= toM) fromM else toM))
        for (i in alongs.indices) {
            if (alongs[i] > a && alongs[i] < b) walk.add(heights[i])
        }
        walk.add(heightAt(if (fromM <= toM) toM else fromM))
        if (fromM > toM) walk.reverse()
        var up = 0.0
        var down = 0.0
        for (i in 1 until walk.size) {
            val d = walk[i] - walk[i - 1]
            if (d > 0) up += d else down -= d
        }
        return up to down
    }

    /** Naismith's flat pace, the fallback when he has no history. */
    const val DEFAULT_PACE_MIN_PER_KM = 12.0

    /** The climb surcharge: a minute per ten metres up. Descent is free —
     *  Naismith thought so too, and his own downhill data will fold into
     *  the measured pace anyway. */
    const val MIN_PER_10M_CLIMB = 1.0

    /**
     * His normal pace from his own recorded walks: the median of gross pace
     * (stops included — his walks contain his real faffing, and so will
     * this one). Walks too short or with silly paces are ignored rather
     * than letting one broken record poison the number.
     */
    fun paceFromWalks(walks: List<Pair<Double, Long>>): Double? {
        val paces = walks.mapNotNull { (distM, durMs) ->
            if (distM < 800.0 || durMs <= 0) return@mapNotNull null
            val p = (durMs / 60_000.0) / (distM / 1000.0)
            if (p in 6.0..40.0) p else null
        }.sorted()
        if (paces.isEmpty()) return null
        return paces[paces.size / 2]
    }

    /** Minutes to cover [distM] with [ascentM] of climb at [paceMinPerKm]. */
    fun minutes(distM: Double, ascentM: Double, paceMinPerKm: Double): Double =
        distM / 1000.0 * paceMinPerKm + ascentM / 10.0 * MIN_PER_10M_CLIMB
}
