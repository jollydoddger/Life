package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * "Has anybody actually walked this?" — a route held against the public
 * record of where people's GPS units have really been.
 *
 * His problem, in his words: *"I've been on too many walks where the path
 * ends or it's inaccessible."* No amount of curation fixes that. A walk
 * rated five stars in 2019 does not know the stile became a fence, and a
 * path drawn on the map is a claim about the ground, not a report from it.
 * [Traces] already holds the one dataset that *is* a report from it —
 * OpenStreetMap's public GPS traces, uploaded by people who were there —
 * and until now it was a scatter of dots to squint at. This turns it into
 * an answer about a specific route, naming the specific metres.
 *
 * **The honesty rule this file exists to keep:** no traces is not the same
 * as no path. OSM's trace archive is thin in plenty of real places, and a
 * cell nobody has uploaded to is a gap in the record, never a verdict on
 * the ground. So the report separates three states that a naive check
 * would collapse into one — ground with tracks on it, ground that has been
 * *looked up* and genuinely has none, and ground not yet looked up at all —
 * and refuses to describe the third as anything but unknown. That is
 * `Capacity.UNKNOWN` reasoning borrowed wholesale: unknown must never be
 * treated as bad, or the feature starts crying wolf and gets ignored,
 * which is worse than not having it.
 */
object TraceCheck {

    /** A trace point this close to the route counts as walking it. GPS
     *  under tree cover wanders; tighter than this flags honest tracks. */
    const val NEAR_M = 25.0

    /** The route is measured at this spacing, whatever its own point
     *  density — a planner route has a point every few metres and an
     *  imported GPX can have one every two hundred, and a run length in
     *  metres has to mean the same thing for both. */
    const val STEP_M = 20.0

    /** Shorter gaps than this are not reported: a bridge, a car park, a
     *  stretch of open access land people cross differently every time. */
    const val MIN_RUN_M = 150.0

    /**
     * One run of route with no recorded tracks along it. Carries its own
     * geometry so the map can draw the doubtful bit rather than only
     * describing it — "340 m at SE 062 281" is an answer you have to go
     * and find, and a dashed line over the moor is one you can see.
     */
    class Stretch(
        /** Distance along the route where it starts and ends, in metres. */
        val fromM: Double,
        val toM: Double,
        /** The stretch itself, in grid metres. */
        val line: List<En>,
    ) {
        val metres: Double get() = toM - fromM

        /** Where it starts on the ground, for a grid reference. */
        val at: En get() = line.first()
    }

    /**
     * What the check found. [checkedM] is the length of route that sits on
     * ground actually looked up; the difference between it and [routeM] is
     * ground nobody has asked about yet, and is never counted against the
     * route.
     */
    class Report(
        val routeM: Double,
        val checkedM: Double,
        val walkedM: Double,
        val stretches: List<Stretch>,
        /** Cells covering the route that have never been fetched. */
        val cellsMissing: Int,
        /** Cells fetched and genuinely holding nothing. */
        val cellsEmpty: Int,
        /** Cells fetched and holding tracks. */
        val cellsWithData: Int,
    ) {
        val cellsKnown: Int get() = cellsEmpty + cellsWithData

        /** Nothing looked up at all — the check could not run. */
        val blind: Boolean get() = cellsKnown == 0

        /** Looked up, and the whole area is genuinely bare of uploads. */
        val areaBare: Boolean get() = cellsWithData == 0 && cellsEmpty > 0

        /** Share of the route that has been checked at all, 0..1. */
        val coverage: Double get() = if (routeM <= 0) 0.0 else (checkedM / routeM).coerceIn(0.0, 1.0)

        /**
         * The finding, in a sentence he can act on — which means naming the
         * metres and where they are, never a score out of ten. A grid
         * reference because that is what is useful when you are stood at
         * the gate wondering whether to climb it.
         */
        fun words(): String {
            if (routeM <= 0) return "No route to check."
            if (blind) {
                return "No trace data for this area yet — nothing has been looked up, " +
                    "so this is not a verdict on the paths."
            }
            if (areaBare) {
                return "Nobody has publicly recorded a GPS track anywhere near this route. " +
                    "That is a gap in OpenStreetMap's archive, not a judgement on the ground " +
                    "— plenty of good paths have never been uploaded."
            }
            val gap = if (coverage < 0.9) {
                " (${((1 - coverage) * 100).roundToInt()}% of it not looked up yet)"
            } else {
                ""
            }
            if (stretches.isEmpty()) {
                return "Every part of this route has GPS tracks along it$gap — people " +
                    "have really walked all of it."
            }
            val worst = stretches.maxByOrNull { it.metres }!!
            val where = Bng.gridRef(worst.at, 3)?.let { " at $it" } ?: ""
            val lead = "${worst.metres.roundToInt()} m with no recorded tracks$where, " +
                "${fmt(worst.fromM)} into the walk"
            val rest = when (stretches.size) {
                1 -> "."
                2 -> ", and one other stretch like it."
                else -> ", and ${stretches.size - 1} other stretches like it."
            }
            return "Worth a look before you go: $lead$rest$gap"
        }

        private fun fmt(m: Double): String =
            if (m >= 1000) "%.1f km".format(java.util.Locale.UK, m / 1000) else "${m.roundToInt()} m"
    }

    /**
     * The route at an even [stepM] spacing, so a run length means the same
     * thing whatever the source's own point density.
     */
    fun resample(points: List<En>, stepM: Double = STEP_M): List<En> {
        if (points.size < 2) return points
        val out = ArrayList<En>(points.size)
        out.add(points.first())
        var carry = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = hypot(b.e - a.e, b.n - a.n)
            if (d <= 0.0) continue
            // The next sample falls this far into the segment; [carry] is
            // how much ground has passed since the last one was emitted, so
            // a run of very short segments accumulates instead of each one
            // being rounded away to nothing.
            var t = stepM - carry
            while (t <= d) {
                val f = t / d
                out.add(En(a.e + (b.e - a.e) * f, a.n + (b.n - a.n) * f))
                t += stepM
            }
            carry = (carry + d) % stepM
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    /**
     * A lookup grid over the trace points. A route of a few hundred samples
     * against a hundred thousand trace points is ten million distance
     * checks done naively, on the main thread, while he waits — so the
     * points go into [NEAR_M] buckets once and each sample looks at the
     * nine buckets around it.
     */
    private class Near(cells: List<FloatArray>, private val withinM: Double) {
        private val cell = withinM
        private val grid = HashMap<Long, MutableList<Float>>()

        init {
            for (c in cells) {
                var i = 0
                while (i + 1 < c.size) {
                    val k = key(c[i].toDouble(), c[i + 1].toDouble())
                    val bucket = grid.getOrPut(k) { ArrayList(8) }
                    bucket.add(c[i])
                    bucket.add(c[i + 1])
                    i += 2
                }
            }
        }

        val isEmpty: Boolean get() = grid.isEmpty()

        private fun key(e: Double, n: Double): Long =
            (Math.floor(e / cell).toLong() shl 32) xor (Math.floor(n / cell).toLong() and 0xffffffffL)

        fun has(p: En): Boolean {
            val ce = Math.floor(p.e / cell).toLong()
            val cn = Math.floor(p.n / cell).toLong()
            for (de in -1..1) {
                for (dn in -1..1) {
                    val bucket = grid[((ce + de) shl 32) xor ((cn + dn) and 0xffffffffL)] ?: continue
                    var i = 0
                    while (i + 1 < bucket.size) {
                        if (hypot(bucket[i] - p.e, bucket[i + 1] - p.n) <= withinM) return true
                        i += 2
                    }
                }
            }
            return false
        }
    }

    /**
     * Hold [route] against [traces], counting only the parts of it that sit
     * on ground already looked up.
     *
     * [known] answers "has this point's cell been fetched at all" — the
     * whole difference between "nobody walks here" and "nobody has told us
     * yet", and the reason this takes a function rather than assuming.
     */
    fun check(
        route: List<En>,
        traces: List<FloatArray>,
        known: (En) -> Boolean,
        cellsMissing: Int = 0,
        cellsEmpty: Int = 0,
        cellsWithData: Int = 0,
        withinM: Double = NEAR_M,
        minRunM: Double = MIN_RUN_M,
    ): Report {
        val pts = resample(route)
        if (pts.size < 2) return Report(0.0, 0.0, 0.0, emptyList(), cellsMissing, cellsEmpty, cellsWithData)
        val near = Near(traces, withinM)

        var routeM = 0.0
        var checkedM = 0.0
        var walkedM = 0.0
        val stretches = ArrayList<Stretch>()
        var runFrom = -1.0
        var runTo = 0.0
        var runLine = ArrayList<En>()

        fun closeRun() {
            if (runFrom >= 0 && runTo - runFrom >= minRunM && runLine.size >= 2) {
                stretches.add(Stretch(runFrom, runTo, runLine))
            }
            runFrom = -1.0
            runLine = ArrayList()
        }

        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = hypot(b.e - a.e, b.n - a.n)
            val startM = routeM
            routeM += d
            // A segment is only judged where both ends sit on looked-up
            // ground; anything else is unknown and is left out of the sums
            // entirely rather than counted either way.
            if (!known(a) || !known(b)) {
                closeRun()
                continue
            }
            checkedM += d
            if (near.has(a) || near.has(b)) {
                walkedM += d
                closeRun()
            } else {
                if (runFrom < 0) {
                    runFrom = startM
                    runLine.add(a)
                }
                runLine.add(b)
                runTo = routeM
            }
        }
        closeRun()
        return Report(routeM, checkedM, walkedM, stretches, cellsMissing, cellsEmpty, cellsWithData)
    }
}
