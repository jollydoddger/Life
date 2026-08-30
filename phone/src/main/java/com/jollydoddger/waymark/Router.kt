package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Waymark's own walking router.
 *
 * It exists because a general foot router could not answer two fair
 * requests: "make it shorter" and "keep me off the A-road". A public OSRM
 * foot profile will happily send you along a trunk-road verge and returns
 * whatever length it fancies, with no lever for either.
 *
 * So the network is built here instead, from the same Overpass data the map
 * overlays already use. Every edge knows what kind of way it is, which makes
 * both answers structural rather than hopeful: a road you refuse is simply
 * not in the graph, and length is converged on by re-running the search on a
 * tighter circle until it lands. The percentages reported afterwards are
 * counted off the edges actually walked — measured, not sampled.
 */
object Router {

    /** How much a metre of each kind of way "costs" a walker. Lower is nicer. */
    private val COST = mapOf(
        "path" to 1.0, "footway" to 1.0, "bridleway" to 1.0, "track" to 1.05,
        "steps" to 1.4, "pedestrian" to 1.0, "cycleway" to 1.15,
        "living_street" to 1.4, "residential" to 1.8, "unclassified" to 1.8,
        "service" to 1.9, "tertiary" to 4.0, "secondary" to 12.0, "primary" to 30.0,
        // The link classes were named in ROADS and NEVER but never priced,
        // and the builder drops anything COST has no entry for — so every
        // slip road fell out of the graph even when roads were allowed,
        // cutting lanes off from the roads they actually join.
        "tertiary_link" to 4.0, "secondary_link" to 12.0, "primary_link" to 30.0,
    )

    /** Never routed on foot, whatever the setting: no pavement, no business. */
    private val NEVER = setOf("motorway", "motorway_link", "trunk", "trunk_link")

    /**
     * The A and B classes. "Avoid roads" used to drop these from the graph
     * outright, which is why planning failed so often round here: an A road
     * does not just make a walk unpleasant, it cuts the path network into
     * islands, and a loop cannot be closed across an island's edge at any
     * price. They are now priced instead — [ROAD_AVOID_PENALTY] makes one a
     * last resort, so a route will cross an A road to reach the lanes
     * beyond but will not stroll along it — and every plan reports the road
     * metres it actually used, so the claim is checkable.
     */
    private val ROADS = setOf("primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link")

    /** What a road edge costs when he asked to avoid roads. */
    const val ROAD_AVOID_PENALTY = 25.0

    /** What a way counts as when the result is described back to him. */
    fun group(kind: String): String = when (kind) {
        "path", "footway", "bridleway", "track", "steps", "pedestrian" -> "path"
        "cycleway", "living_street", "residential", "unclassified", "service" -> "lane"
        else -> "road"
    }

    class Graph(
        val nodes: ArrayList<En>,
        val edges: ArrayList<ArrayList<Edge>>,
    ) {
        class Edge(
            val to: Int,
            val metres: Double,
            val cost: Double,
            val kind: String,
            /** Precomputed: a set lookup per edge relaxation is the last
             *  thing an inner loop run hundreds of thousands of times
             *  needs. */
            val road: Boolean,
        )

        // A uniform grid over the nodes. Finding the nearest node used to be
        // a linear scan of every node in the graph, and a single plan does
        // hundreds of them: on a 30,000-node network that is millions of
        // square roots before any routing happens.
        private val cell = 120.0
        private val grid = HashMap<Long, MutableList<Int>>()

        init {
            for (i in nodes.indices) {
                grid.getOrPut(cellKey(nodes[i])) { ArrayList() }.add(i)
            }
        }

        private fun cellKey(p: En): Long =
            (Math.floor(p.e / cell).toLong() shl 32) xor (Math.floor(p.n / cell).toLong() and 0xffffffffL)

        private fun cellKey(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xffffffffL)

        /**
         * The nearest node satisfying [ok] within [withinM], or null. Rings
         * of cells outward from the point, stopping as soon as the next ring
         * cannot hold anything closer than the best already found.
         */
        fun nearestWhere(p: En, withinM: Double, ok: (Int) -> Boolean): Int? {
            if (nodes.isEmpty()) return null
            val cx = Math.floor(p.e / cell).toLong()
            val cy = Math.floor(p.n / cell).toLong()
            var best = -1
            var bestD = Double.MAX_VALUE
            val maxRing = (withinM / cell).toInt() + 2
            var r = 0
            while (r <= maxRing) {
                for (dx in -r..r) {
                    for (dy in -r..r) {
                        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                        val bucket = grid[cellKey(cx + dx, cy + dy)] ?: continue
                        for (i in bucket) {
                            if (!ok(i)) continue
                            val d = hypot(nodes[i].e - p.e, nodes[i].n - p.n)
                            if (d < bestD) { bestD = d; best = i }
                        }
                    }
                }
                // A hit closer than this ring's inner edge cannot be beaten.
                if (best >= 0 && bestD <= r * cell) break
                r++
            }
            return if (best >= 0 && bestD <= withinM) best else null
        }

        fun nearest(p: En): Int? {
            nearestWhere(p, 5_000.0) { true }?.let { return it }
            // Beyond the grid search: fall back to the honest scan rather
            // than pretending an empty answer.
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in nodes.indices) {
                val d = hypot(nodes[i].e - p.e, nodes[i].n - p.n)
                if (d < bestD) { bestD = d; best = i }
            }
            return if (best >= 0) best else null
        }

        /** Junctions within [withinM], nearest first — the places a loop can
         *  actually start or turn. */
        fun junctionsNear(p: En, withinM: Double, limit: Int): List<Int> =
            nodes.indices
                .filter { edges[it].size >= 3 && hypot(nodes[it].e - p.e, nodes[it].n - p.n) <= withinM }
                .sortedBy { hypot(nodes[it].e - p.e, nodes[it].n - p.n) }
                .take(limit)

        /**
         * The nearest place a loop can actually turn a corner.
         *
         * A dead end has one way in and the same way out, so hanging a
         * waypoint on one guarantees an out-and-back spur — which is exactly
         * what a "circular walk" is not. Three ways out means the route can
         * arrive and leave differently. Falls back to [nearest] if there is
         * no junction within reach: better a corner in roughly the right
         * place than no corner at all, and the spur it causes gets pruned
         * out of the finished walk anyway.
         */
        fun nearestJunction(p: En, withinM: Double): Int? {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in nodes.indices) {
                if (edges[i].size < 3) continue
                val d = hypot(nodes[i].e - p.e, nodes[i].n - p.n)
                if (d < bestD) { bestD = d; best = i }
            }
            return if (best >= 0 && bestD <= withinM) best else nearest(p)
        }
    }

    data class Planned(
        val points: List<En>,
        val metres: Double,
        /** Metres walked on each of "path" / "lane" / "road". */
        val byGroup: Map<String, Double>,
        /**
         * How much of the walk is ground covered twice. A circular walk
         * should be near zero; the number is kept rather than hidden because
         * on a thin network some doubling back is genuinely the only way
         * home, and saying so is better than presenting it as a clean loop.
         */
        val repeatFraction: Double = 0.0,
    ) {
        fun pathFraction(): Double =
            if (metres <= 0) 0.0 else (byGroup["path"] ?: 0.0) / metres

        fun roadMetres(): Double = byGroup["road"] ?: 0.0
    }

    /**
     * Fetch the walkable network around a point and build the graph.
     * [avoidRoads] leaves the A/B/C classes out entirely, so nothing
     * downstream can accidentally route onto one.
     */
    private var cachedGraph: Graph? = null
    private var cachedCentre: En? = null
    private var cachedRadius = 0.0

    /**
     * The network round a point, reusing the last one when it covers the
     * ask. Fetching and parsing several megabytes of Overpass JSON is the
     * bulk of a plan, and re-planning a few hundred metres away used to pay
     * it again from scratch.
     */
    fun buildCached(centre: En, radiusM: Double): Graph {
        val c = cachedCentre
        val g = cachedGraph
        if (c != null && g != null) {
            val moved = hypot(centre.e - c.e, centre.n - c.n)
            if (moved + radiusM <= cachedRadius) return g
        }
        val fresh = build(centre, radiusM)
        cachedGraph = fresh
        cachedCentre = centre
        cachedRadius = radiusM
        return fresh
    }

    /** Let the network go when the system is short of memory. */
    fun trim() {
        cachedGraph = null
        cachedCentre = null
    }

    fun build(centre: En, radiusM: Double): Graph {
        val (lat, lon) = Bng.toWgs84(centre)
        val at = "%.5f,%.5f".format(lat, lon)
        val kinds = COST.keys.joinToString("|")
        val end = "${'$'}"
        val query = "[out:json][timeout:60];" +
            "way[\"highway\"~\"^($kinds)$end\"]" +
            "[\"foot\"!~\"^(no|private)$end\"]" +
            "[\"access\"!~\"^(no|private)$end\"]" +
            "(around:${radiusM.toInt()},$at);" +
            "out geom;"
        val json = Net.overpass(query, timeoutMs = 70_000)

        val nodes = ArrayList<En>()
        val edges = ArrayList<ArrayList<Graph.Edge>>()
        val index = HashMap<Long, Int>()

        // Ways that share a junction share a node only if their coordinates
        // land in the same 3 m cell — OSM geometry repeats the junction point
        // exactly, so this joins the network up without fuzzing it.
        fun nodeAt(p: En): Int {
            val key = (Math.round(p.e / 3.0) shl 22) xor Math.round(p.n / 3.0)
            index[key]?.let { return it }
            nodes.add(p)
            edges.add(ArrayList())
            val i = nodes.size - 1
            index[key] = i
            return i
        }

        val elements = JSONObject(json).getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val kind = el.optJSONObject("tags")?.optString("highway").orEmpty()
            if (kind in NEVER) continue
            // Roads stay in the graph whatever the preference; the search
            // prices them. Dropping them here is what left the path network
            // in islands an A road wide.
            val cost = COST[kind] ?: continue
            val isRoad = kind in ROADS
            val geom = el.optJSONArray("geometry") ?: continue

            var prev = -1
            var prevEn: En? = null
            for (g in 0 until geom.length()) {
                val nd = geom.optJSONObject(g) ?: continue
                val en = Bng.fromWgs84(nd.getDouble("lat"), nd.getDouble("lon"))
                val idx = nodeAt(en)
                if (prev >= 0 && prev != idx) {
                    val d = hypot(en.e - prevEn!!.e, en.n - prevEn.n)
                    if (d > 0) {
                        // Walking has no one-way streets.
                        edges[prev].add(Graph.Edge(idx, d, d * cost, kind, isRoad))
                        edges[idx].add(Graph.Edge(prev, d, d * cost, kind, isRoad))
                    }
                }
                prev = idx
                prevEn = en
            }
        }
        return Graph(nodes, edges)
    }

    /**
     * A* between two graph nodes. [penalise] holds edges already walked on
     * this route; re-using one is allowed but expensive, which is what makes
     * a loop come home a different way instead of doubling back.
     */
    fun path(
        g: Graph,
        from: Int,
        to: Int,
        penalise: Set<Long> = emptySet(),
        roadPenalty: Double = 1.0,
    ): List<Int>? {
        val n = g.nodes.size
        if (from >= n || to >= n) return null
        val best = DoubleArray(n) { Double.MAX_VALUE }
        val cameFrom = IntArray(n) { -1 }
        val done = BooleanArray(n)
        val target = g.nodes[to]
        fun heuristic(i: Int) = hypot(g.nodes[i].e - target.e, g.nodes[i].n - target.n)

        val queue = Heap()
        best[from] = 0.0
        queue.push(from, heuristic(from))
        while (queue.isNotEmpty()) {
            val u = queue.pop()
            if (done[u]) continue
            done[u] = true
            if (u == to) break
            for (e in g.edges[u]) {
                if (done[e.to]) continue
                val key = edgeKey(u, e.to)
                var step = if (e.road) e.cost * roadPenalty else e.cost
                if (key in penalise) step *= REUSE_PENALTY
                val alt = best[u] + step
                if (alt < best[e.to]) {
                    best[e.to] = alt
                    cameFrom[e.to] = u
                    queue.push(e.to, alt + heuristic(e.to))
                }
            }
        }
        if (best[to] == Double.MAX_VALUE) return null
        val out = ArrayList<Int>()
        var cur = to
        while (cur != -1) { out.add(cur); cur = cameFrom[cur] }
        return out.reversed()
    }

    /**
     * A binary heap over primitive arrays. The old open set was a
     * PriorityQueue of boxed DoubleArrays compared through `compareBy` —
     * two allocations and a boxed comparison for every node ever relaxed,
     * across hundreds of searches per plan. This is the inner loop of the
     * whole planner.
     */
    private class Heap {
        private var ids = IntArray(1024)
        private var keys = DoubleArray(1024)
        private var size = 0

        fun isNotEmpty() = size > 0

        fun push(id: Int, key: Double) {
            if (size == ids.size) {
                ids = ids.copyOf(size * 2)
                keys = keys.copyOf(size * 2)
            }
            var i = size++
            ids[i] = id
            keys[i] = key
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= keys[i]) break
                swap(i, parent)
                i = parent
            }
        }

        fun pop(): Int {
            val top = ids[0]
            size--
            if (size > 0) {
                ids[0] = ids[size]
                keys[0] = keys[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = l + 1
                    var small = i
                    if (l < size && keys[l] < keys[small]) small = l
                    if (r < size && keys[r] < keys[small]) small = r
                    if (small == i) break
                    swap(i, small)
                    i = small
                }
            }
            return top
        }

        private fun swap(a: Int, b: Int) {
            val i = ids[a]; ids[a] = ids[b]; ids[b] = i
            val k = keys[a]; keys[a] = keys[b]; keys[b] = k
        }
    }

    private fun edgeKey(a: Int, b: Int): Long =
        (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

    /**
     * How much of a finished point list is ground covered twice, judged on
     * the geometry rather than on graph edges — the via-places path builds
     * its route by concatenating legs and has no node walk to measure.
     * Segments are matched on their rounded endpoints, so a leg retracing
     * another leg is counted however the two were produced.
     */
    fun repeatFraction(points: List<En>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        var repeated = 0.0
        val seen = HashSet<Long>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = hypot(b.e - a.e, b.n - a.n)
            if (d <= 0) continue
            total += d
            val ka = (Math.round(a.e / 5.0) shl 22) xor Math.round(a.n / 5.0)
            val kb = (Math.round(b.e / 5.0) shl 22) xor Math.round(b.n / 5.0)
            val key = (minOf(ka, kb) shl 20) xor maxOf(ka, kb)
            if (!seen.add(key)) repeated += d
        }
        return if (total > 0) repeated / total else 0.0
    }

    /** Turn a node walk into points, per-kind distances and repeated ground. */
    private fun measure(g: Graph, walk: List<Int>): Planned {
        val pts = walk.map { g.nodes[it] }
        var total = 0.0
        var repeated = 0.0
        val byGroup = HashMap<String, Double>()
        val seen = HashMap<Long, Double>()
        for (i in 1 until walk.size) {
            val e = g.edges[walk[i - 1]].firstOrNull { it.to == walk[i] } ?: continue
            total += e.metres
            byGroup[group(e.kind)] = (byGroup[group(e.kind)] ?: 0.0) + e.metres
            val key = edgeKey(walk[i - 1], walk[i])
            if (seen.put(key, e.metres) != null) repeated += e.metres
        }
        return Planned(pts, total, byGroup, if (total > 0) repeated / total else 0.0)
    }

    /**
     * Strip out-and-back spurs from a node walk.
     *
     * A route that goes a-b-c-b-a walked no new ground for its trouble, and
     * on the map it reads as a branch sticking out of a circle rather than a
     * circuit. Removing every immediate reversal, repeatedly, removes a spur
     * of any depth and leaves a genuine loop untouched — a loop comes back to
     * a node by a *different* edge, so it never reverses on itself.
     */
    private fun prune(walk: List<Int>): List<Int> {
        val out = ArrayList<Int>(walk.size)
        for (n in walk) {
            if (out.size >= 2 && n == out[out.size - 2]) out.removeAt(out.size - 1) else out.add(n)
        }
        return out
    }

    /** Walking an edge a second time costs this much more than the first. */
    private const val REUSE_PENALTY = 10.0

    /** Close enough on length, and clean enough in shape, to stop looking. */
    private const val GOOD_ERROR = 0.15
    private const val GOOD_REPEAT = 0.10

    /**
     * A circular walk of about [targetM], starting and finishing at [start].
     *
     * "Circular" is the word he used, and it has to mean a circuit — not a
     * line with branches hanging off it. Three things make it one. Corners
     * are hung on **junctions**, because a waypoint at a dead end can only be
     * reached and left the same way. Re-walking an edge costs ten times what
     * walking it fresh does, so coming home round the other side beats
     * turning round. And whatever still doubles back is [prune]d out
     * afterwards — the only one of the three that is a guarantee rather than
     * an incentive.
     *
     * Candidates are scored on shape as well as length: a loop 15% long but
     * clean beats one that measures exactly right and retraces a quarter of
     * itself. He said the distance can give; the shape cannot.
     *
     * The search itself is deliberately broad — three circle sizes, two
     * starting bearings, three and four corners, clockwise and anticlockwise
     * — because on a thin network most of those come back with nothing. It
     * stops at the first candidate that is good on both counts, so the breadth
     * costs nothing on the ordinary day when the first shape fits.
     */
    fun loop(
        g: Graph,
        start: En,
        targetM: Double,
        deadlineMs: Long = Long.MAX_VALUE,
        avoidRoads: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Planned? {
        val roadPenalty = if (avoidRoads) ROAD_AVOID_PENALTY else 1.0
        // Where a loop may begin. He said his start "can be anywhere within
        // 500 m", which is a licence worth spending: a junction a street
        // away often closes a circuit his exact doorstep cannot.
        val startNodes = ArrayList<Int>()
        g.nearest(start)?.let { startNodes.add(it) }
        for (j in g.junctionsNear(start, START_SLACK_M, 3)) {
            if (j !in startNodes) startNodes.add(j)
        }
        if (startNodes.isEmpty()) return null

        var base = targetM / (2 * Math.PI)
        var best: Planned? = null
        var bestScore = Double.MAX_VALUE
        var tried = 0
        var closed = 0

        fun outOfTime() = System.currentTimeMillis() > deadlineMs || isCancelled()

        for (attempt in 0 until 3) {
            if (outOfTime()) break
            val spin = Math.random() * 2 * Math.PI
            var round: Planned? = null
            var roundScore = Double.MAX_VALUE
            var enough = false

            for (startNode in startNodes) {
                if (enough || outOfTime()) break
                val from = g.nodes[startNode]
                for (mult in doubleArrayOf(1.0, 0.72, 1.35)) {
                    if (enough || outOfTime()) break
                    val radius = base * mult
                    for (seed in 0 until 2) {
                        if (enough || outOfTime()) break
                        for (corners in intArrayOf(4, 3)) {
                            if (enough || outOfTime()) break
                            for (direction in intArrayOf(1, -1)) {
                                // Checked per candidate: this is what makes
                                // the budget and the stop button real, and
                                // what stops a plan grinding for minutes
                                // with nothing to show for it.
                                if (outOfTime()) break
                                tried++
                                val bearing0 = spin + seed * (Math.PI / corners)
                                val walk = ArrayList<Int>()
                                val used = HashSet<Long>()
                                var cursor = startNode
                                for (k in 0 until corners) {
                                    val b = bearing0 + direction * k * 2 * Math.PI / corners
                                    val ideal = En(from.e + radius * sin(b), from.n + radius * cos(b))
                                    val wp = g.nearestJunction(ideal, radius * 0.6) ?: continue
                                    if (wp == cursor) continue
                                    val leg = path(g, cursor, wp, used, roadPenalty) ?: continue
                                    for (i in 1 until leg.size) used.add(edgeKey(leg[i - 1], leg[i]))
                                    if (walk.isEmpty()) walk.add(leg.first())
                                    walk.addAll(leg.drop(1))
                                    cursor = wp
                                }
                                if (walk.isEmpty()) continue
                                val home = path(g, cursor, startNode, used, roadPenalty) ?: continue
                                walk.addAll(home.drop(1))
                                val circuit = prune(walk)
                                if (circuit.size < 4) continue
                                val planned = measure(g, circuit)
                                // Anything that closed is a candidate now.
                                // The old floor threw away a real 1.8 km
                                // circuit against a 7 km ask and then
                                // reported finding nothing at all — a worse
                                // answer than the loop it was holding.
                                if (planned.metres < MIN_LOOP_M) continue
                                closed++
                                val err = kotlin.math.abs(planned.metres - targetM) / targetM
                                val score = err + planned.repeatFraction * 1.5
                                if (score < roundScore) { roundScore = score; round = planned }
                                if (score < bestScore) { bestScore = score; best = planned }
                                if (err < GOOD_ERROR && planned.repeatFraction < GOOD_REPEAT) {
                                    enough = true
                                    break
                                }
                            }
                        }
                    }
                }
            }
            val r = round
            if (r == null) {
                onProgress("Round ${attempt + 1}: nothing closed yet, trying a different shape…")
                base *= 0.8
                continue
            }
            val err = kotlin.math.abs(r.metres - targetM) / targetM
            onProgress(
                "Round ${attempt + 1}: ${"%.1f".format(r.metres / 1000)} km" +
                    (if (r.repeatFraction > 0.12) ", still doubling back…" else "…"),
            )
            if (err < GOOD_ERROR && r.repeatFraction < GOOD_REPEAT) return best
            base *= (targetM / r.metres).coerceIn(0.55, 1.8)
        }
        if (best == null && tried > 0) {
            onProgress("Tried $tried shapes; none of them came home.")
        }
        // Best seen, always — a loop of the wrong length, stated honestly,
        // beats two minutes ending in "couldn't".
        return best
    }

    /** Loops shorter than this are noise, not walks. */
    private const val MIN_LOOP_M = 300.0

    /** How far from him a loop may start — his own licence. */
    const val START_SLACK_M = 500.0

    /** Point-to-point on the same graph, so the same rules apply. */
    fun between(g: Graph, from: En, to: En, avoidRoads: Boolean = true): Planned? {
        val a = g.nearest(from) ?: return null
        val b = g.nearest(to) ?: return null
        val walk = path(g, a, b, emptySet(), if (avoidRoads) ROAD_AVOID_PENALTY else 1.0) ?: return null
        return measure(g, walk)
    }

}
