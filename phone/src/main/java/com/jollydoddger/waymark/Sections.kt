package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Joining an official route where it goes past, rather than where it begins.
 *
 * His ask, and it is a different question from the one the app was already
 * answering: *"identify official walks that go close to my location on part
 * of their route — doesn't have to start at me or start near me."*
 *
 * The search already finds those. `RouteFinder` asks OpenStreetMap by
 * geometry, so a walk qualifies because its **line** passes nearby, not
 * because a pin marks its start nearby. What it could not then do was
 * *offer* one: the Calderdale Way passing three hundred metres away is
 * fifty kilometres long and gets thrown out of a "eight to fifteen"
 * shortlist, and the near-miss fallback could only hand it over whole and
 * say so. Which is true, and useless — nobody walks fifty kilometres
 * because it went past the door.
 *
 * So a section is cut instead: start where the route passes closest, walk
 * out along it for half the length asked, and come back down it. That is
 * what a person actually does with a long-distance path that runs near
 * their house, and it is honest — every metre of it is the real route,
 * surveyed by somebody else, not an invention.
 *
 * Pure. No Context, no network.
 */
object Sections {

    /**
     * How close two member ways' ends must be to count as joined.
     *
     * An OpenStreetMap relation's members repeat the shared node exactly,
     * so nought would nearly do; the tolerance is for the ways the clip box
     * cut and for relations whose mappers left a metre of slack.
     */
    const val JOIN_M = 30.0

    /**
     * A relation's member ways stitched into continuous runs, longest first.
     *
     * They arrive unordered and pointing in whichever direction they were
     * drawn — `FoundWalk.routePoints()` admits as much by flattening them
     * and warning that the result jumps about. Flattened is fine for
     * measuring how close a route comes; it is no good at all for walking
     * along one, because "the next point" has to actually be the next point.
     */
    fun chains(lines: List<List<En>>, joinM: Double = JOIN_M): List<List<En>> {
        val pool = ArrayList(lines.filter { it.size >= 2 })
        val out = ArrayList<List<En>>()
        fun meets(a: En, b: En) = hypot(a.e - b.e, a.n - b.n) <= joinM
        while (pool.isNotEmpty()) {
            // ArrayDeque so growing at the head is not a copy of the whole
            // chain: a national trail is tens of thousands of points, and
            // prepending to a list that long, once per member, is the sort
            // of arithmetic that makes an app look broken.
            val chain = ArrayDeque(pool.removeAt(pool.size - 1))
            var grew = true
            while (grew && pool.isNotEmpty()) {
                grew = false
                for (i in pool.indices) {
                    val c = pool[i]
                    val attached = when {
                        meets(chain.last(), c.first()) -> {
                            for (k in 1 until c.size) chain.addLast(c[k]); true
                        }
                        meets(chain.last(), c.last()) -> {
                            for (k in c.size - 2 downTo 0) chain.addLast(c[k]); true
                        }
                        meets(chain.first(), c.last()) -> {
                            for (k in c.size - 2 downTo 0) chain.addFirst(c[k]); true
                        }
                        meets(chain.first(), c.first()) -> {
                            for (k in 1 until c.size) chain.addFirst(c[k]); true
                        }
                        else -> false
                    }
                    if (!attached) continue
                    pool.removeAt(i)
                    grew = true
                    break
                }
            }
            out.add(chain.toList())
        }
        return out.sortedByDescending { Geom.length(it) }
    }

    /**
     * A run of about [wantM] metres of [chain], leaving from wherever it
     * passes closest to [at] and heading whichever way gets nearer the
     * length asked — which is the way with enough route left on it.
     *
     * The last step is trimmed rather than overshot: an offer of "7.4 km"
     * that is really 7.4 plus however far the next vertex happened to be is
     * a length nobody can plan a day around.
     */
    fun runFrom(chain: List<En>, at: En, wantM: Double): List<En> {
        if (chain.size < 2 || wantM <= 0) return emptyList()
        var idx = 0
        var bestD = Double.MAX_VALUE
        for (i in chain.indices) {
            val d = hypot(chain[i].e - at.e, chain[i].n - at.n)
            if (d < bestD) { bestD = d; idx = i }
        }

        fun run(step: Int): List<En> {
            val out = ArrayList<En>()
            out.add(chain[idx])
            var total = 0.0
            var i = idx
            while (true) {
                val j = i + step
                if (j < 0 || j >= chain.size) break
                val seg = hypot(chain[j].e - chain[i].e, chain[j].n - chain[i].n)
                if (total + seg >= wantM) {
                    val left = wantM - total
                    val f = if (seg > 0) left / seg else 0.0
                    out.add(
                        En(
                            chain[i].e + (chain[j].e - chain[i].e) * f,
                            chain[i].n + (chain[j].n - chain[i].n) * f,
                        ),
                    )
                    return out
                }
                total += seg
                out.add(chain[j])
                i = j
            }
            return out
        }

        val forward = run(1)
        val backward = run(-1)
        val lf = Geom.length(forward)
        val lb = Geom.length(backward)
        return if (abs(lf - wantM) <= abs(lb - wantM)) forward else backward
    }

    /**
     * The walks that pass nearby but are too long to offer whole, each cut
     * to a there-and-back of the length asked.
     *
     * Doubled rather than one way on purpose: joining a linear route at the
     * point it passes you and walking on leaves you however many kilometres
     * from your own front door, which is not a walk, it is half a walk and
     * a lift home.
     */
    fun near(
        found: List<RouteFinder.FoundWalk>,
        at: En,
        minM: Double,
        maxM: Double,
        /**
         * How close the route has to pass before joining it "from here" is
         * a true sentence. Load-bearing: the search widens to a dozen
         * kilometres when nothing is on the doorstep, and cutting a section
         * of something that passes twelve kilometres away and calling it
         * "from here" would be a straight lie about where the walk starts.
         */
        withinM: Double,
        limit: Int = 4,
    ): List<RouteFinder.FoundWalk> {
        if (minM <= 0 || maxM < minM) return emptyList()
        // Out and back, so the leg is half of the middle of the range.
        val half = (minM + maxM) / 4
        val out = ArrayList<RouteFinder.FoundWalk>()
        for (w in found.sortedBy { it.closestM }) {
            if (out.size >= limit) break
            // One that already fits should be offered whole; a section of it
            // would be the same walk, shortened, for no reason.
            if (w.lengthM <= maxM) continue
            if (w.closestM > withinM) continue
            val chain = chains(w.lines).minByOrNull { Geom.closestApproach(at, it) } ?: continue
            val leg = runFrom(chain, at, half)
            if (leg.size < 2) continue
            val doubled = leg + leg.asReversed().drop(1)
            val length = Geom.length(doubled)
            // The route ran out before it could be the walk he asked for.
            // Offering it anyway would answer a question he did not ask.
            if (length < minM) continue
            out.add(
                w.copy(
                    name = "${w.name} — ${Brief.fmtKm(length)} of it from here, there and back",
                    lines = listOf(doubled),
                    lengthM = length,
                    closestM = Geom.closestApproach(at, leg),
                    // The GPX or relation behind it is the whole route;
                    // re-reading it on adoption would hand back all fifty
                    // kilometres and call it this.
                    uri = null,
                ),
            )
        }
        return out
    }
}
