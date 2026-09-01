package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import kotlin.math.hypot

/**
 * Drawing a walk by tapping the map, with the line following real paths
 * between the taps.
 *
 * The point he made is the whole design: on OS Maps "the lines don't go
 * from A to B, they snap to footpaths". A straight line between two taps is
 * not a walk — it is a bearing across somebody's field. So each leg is
 * routed on the same OpenStreetMap walking network the loop planner uses,
 * which means the same road pricing, the same trail discount, and the same
 * honest measurement of what was actually walked on.
 *
 * The state is anchors plus the legs between them, kept apart on purpose.
 * Anchors are what he tapped and what he can drag or delete; legs are what
 * the router made of them. Moving one anchor re-routes at most the two legs
 * touching it, never the whole walk — which is what keeps editing a
 * twenty-point route quick, and what stops the far end of the line quietly
 * changing shape because he nudged the start.
 *
 * Pure: no Context, no network, no map. The routing arrives as a function
 * so the whole thing can be tested at a desk with straight lines standing
 * in for paths — and the undo stack, which is the part that ruins a
 * drawing if it is wrong.
 */
class RouteEdit(
    /** Route between two points, or null when it cannot. Straight-line
     *  fallback is the caller's decision, not this class's. */
    private val leg: (En, En, Snap) -> List<En>?,
) {

    enum class Snap {
        /** Paths, tracks and bridleways strongly preferred; roads priced but
         *  not forbidden, because he has to cross them. */
        PATHS,

        /** Anything walkable, roads included and unpenalised — for when the
         *  lane genuinely is the way. */
        ANY,

        /** No routing at all: point to point, as the crow flies. For open
         *  access land and beaches, where there is no path to snap to and
         *  pretending otherwise invents one. */
        STRAIGHT,
    }

    /** One tapped point and the routed line that reaches it from the one
     *  before. The first anchor's [from] is empty — nothing precedes it. */
    class Anchor(val at: En, var from: List<En> = emptyList(), var snapped: Boolean = false)

    private val anchors = ArrayList<Anchor>()
    private val undo = ArrayList<List<Anchor>>()

    var snap: Snap = Snap.PATHS

    fun count(): Int = anchors.size
    fun anchorPoints(): List<En> = anchors.map { it.at }
    fun canUndo(): Boolean = undo.isNotEmpty()

    /** True when any leg fell back to a straight line while snapping was
     *  asked for — the UI says so rather than drawing a lie quietly. */
    fun hasUnsnapped(): Boolean =
        snap != Snap.STRAIGHT && anchors.drop(1).any { !it.snapped }

    /**
     * The whole walk as one polyline: every leg end to end. This is what is
     * drawn, measured and saved, and it is the routed line rather than the
     * anchors — the difference between the two being the entire point.
     */
    fun line(): List<En> {
        if (anchors.isEmpty()) return emptyList()
        val out = ArrayList<En>()
        out.add(anchors[0].at)
        for (a in anchors.drop(1)) {
            val leg = a.from
            if (leg.isEmpty()) {
                out.add(a.at)
            } else {
                // The leg already starts at the previous anchor.
                for (i in 1 until leg.size) out.add(leg[i])
            }
        }
        return out
    }

    fun metres(): Double = Geom.length(line())

    private fun snapshot() {
        undo.add(anchors.map { Anchor(it.at, it.from, it.snapped) })
        if (undo.size > 40) undo.removeAt(0)
    }

    fun undo(): Boolean {
        val last = undo.removeLastOrNull() ?: return false
        anchors.clear()
        anchors.addAll(last)
        return true
    }

    /** Route the leg arriving at [i], recording whether it really snapped. */
    private fun route(i: Int) {
        if (i <= 0 || i >= anchors.size) return
        val a = anchors[i - 1].at
        val b = anchors[i].at
        if (snap == Snap.STRAIGHT) {
            anchors[i].from = listOf(a, b)
            anchors[i].snapped = false
            return
        }
        val routed = leg(a, b, snap)
        if (routed != null && routed.size >= 2) {
            anchors[i].from = routed
            anchors[i].snapped = true
        } else {
            // No path between them. Drawn straight and flagged, never
            // silently — a straight leg across a field looks exactly like a
            // routed one and would be walked as though it were.
            anchors[i].from = listOf(a, b)
            anchors[i].snapped = false
        }
    }

    fun add(p: En) {
        snapshot()
        anchors.add(Anchor(p))
        route(anchors.size - 1)
    }

    /** The anchor within [withinM] of [p], or -1 — for tap-to-delete. */
    fun anchorNear(p: En, withinM: Double): Int {
        var best = -1
        var bestD = withinM
        for (i in anchors.indices) {
            val d = hypot(anchors[i].at.e - p.e, anchors[i].at.n - p.n)
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Remove an anchor and re-route only the leg that closes the gap. */
    fun removeAt(i: Int): Boolean {
        if (i < 0 || i >= anchors.size) return false
        snapshot()
        anchors.removeAt(i)
        // The leg that now spans the hole is the one arriving at what used
        // to be i + 1 and is now i. The first anchor has no leg to mend.
        if (i in 1 until anchors.size) route(i)
        if (i == 0 && anchors.isNotEmpty()) anchors[0].from = emptyList()
        return true
    }

    fun moveTo(i: Int, p: En): Boolean {
        if (i < 0 || i >= anchors.size) return false
        snapshot()
        anchors[i] = Anchor(p)
        route(i)
        if (i + 1 < anchors.size) route(i + 1)
        return true
    }

    /** Close the walk by routing back to where it began. */
    fun closeLoop(): Boolean {
        if (anchors.size < 3) return false
        add(anchors.first().at)
        return true
    }

    fun clear() {
        snapshot()
        anchors.clear()
    }

    /** Re-route every leg — after he changes what to stick to. */
    fun resnapAll() {
        snapshot()
        for (i in 1 until anchors.size) route(i)
    }

    fun load(points: List<En>) {
        snapshot()
        anchors.clear()
        if (points.isEmpty()) return
        // An imported line becomes one straight-through anchor chain: its
        // shape is already real, so it is kept verbatim as a single leg
        // rather than re-routed into something the file never said.
        anchors.add(Anchor(points.first()))
        anchors.add(Anchor(points.last(), points, snapped = true))
    }
}
