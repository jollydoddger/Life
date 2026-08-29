package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import kotlin.math.hypot

/**
 * Line-and-point arithmetic in grid metres, shared by the route planner's
 * scoring and the walks-near-me search. Pure functions; nothing here touches
 * a network or a store.
 */
object Geom {

    fun nearestOnSegment(p: En, a: En, b: En): En {
        val dx = b.e - a.e
        val dy = b.n - a.n
        val len2 = dx * dx + dy * dy
        if (len2 <= 0) return a
        val t = (((p.e - a.e) * dx + (p.n - a.n) * dy) / len2).coerceIn(0.0, 1.0)
        return En(a.e + t * dx, a.n + t * dy)
    }

    /** Closest distance from [p] to the polyline through [pts], in metres. */
    fun closestApproach(p: En, pts: List<En>): Double {
        if (pts.isEmpty()) return Double.MAX_VALUE
        if (pts.size == 1) return hypot(pts[0].e - p.e, pts[0].n - p.n)
        var best = Double.MAX_VALUE
        for (i in 1 until pts.size) {
            val q = nearestOnSegment(p, pts[i - 1], pts[i])
            val d = hypot(q.e - p.e, q.n - p.n)
            if (d < best) best = d
        }
        return best
    }

    /** Length of the polyline through [pts], in metres. */
    fun length(pts: List<En>): Double {
        var total = 0.0
        for (i in 1 until pts.size) {
            total += hypot(pts[i].e - pts[i - 1].e, pts[i].n - pts[i - 1].n)
        }
        return total
    }
}
