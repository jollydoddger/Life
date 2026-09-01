package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
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
        "living_street" to 1.3, "residential" to 1.6, "unclassified" to 1.7,
        "service" to 1.8,
        // Read each number as "metres of path I would walk to avoid one
        // metre of this". The old table said 12 for a B road and 30 for an
        // A — which is not a preference, it is a refusal wearing a number,
        // and it is why loops would not close. He will need some road and
        // will certainly need to cross one.
        "tertiary" to 2.6, "secondary" to 4.0, "primary" to 6.5,
        // The link classes were named in ROADS and NEVER but never priced,
        // and the builder drops anything COST has no entry for — so every
        // slip road fell out of the graph even when roads were allowed,
        // cutting lanes off from the roads they actually join.
        "tertiary_link" to 2.6, "secondary_link" to 4.0, "primary_link" to 6.5,
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

    /**
     * What "avoid roads" multiplies a road by. Modest on purpose: the cost
     * is already per metre, so crossing an A road costs ten metres' worth
     * and walking a mile along it costs a mile's worth — the distinction
     * he actually cares about falls out of the arithmetic without anything
     * being forbidden. A blanket twenty-five made every road a wall.
     */
    const val ROAD_AVOID_FACTOR = 2.5

    /**
     * What a metre of waymarked, named route costs against a metre of
     * ordinary path — the mirror image of [ROAD_AVOID_FACTOR], and read the
     * same way: he would walk ten metres of anonymous field-edge path to get
     * three metres of the Slate Trail.
     *
     * A discount rather than a rule. A trail that goes the wrong way is
     * still the wrong way, and a loop must never be dragged half a mile out
     * of shape to touch one — which is what a stronger number does.
     */
    const val TRAIL_BONUS = 0.7

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
            /** The same edge priced for someone avoiding roads. Computed
             *  once at build rather than multiplied per relaxation. */
            val avoidCost: Double = cost,
        ) {
            /**
             * Whether this edge carries a named, waymarked route — the Slate
             * Trail, a coast path, a local walking network. Set by
             * [Graph.markTrails] rather than at build time, because which
             * trails matter depends on where he is planning, and the graph
             * itself is cached across plans.
             */
            var onTrail: Boolean = false
        }

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
         * arrive and leave differently.
         *
         * Nothing within reach means **null**, and the caller skips that
         * corner. It used to fall back to the nearest node of any degree,
         * which planted corners on dead ends and — worse — on whatever
         * disconnected fragment happened to be closest, guaranteeing both an
         * unreachable leg and a full-graph search to discover it. A corner
         * we cannot honour is not a corner.
         */
        fun nearestJunction(p: En, withinM: Double): Int? =
            nearestWhere(p, withinM) { edges[it].size >= 3 }

        /**
         * Mark the edges that lie under a real, named walking route.
         *
         * His idea, and the better half of it: rather than offering him a
         * forty-kilometre national trail that can never match "circular,
         * eight kilometres", *use* the trail — walk its waymarked ground
         * where it goes his way, and let the router work out the rest.
         *
         * It works because a route relation is not a picture: it is built
         * from the very same OpenStreetMap ways this graph is built from, so
         * the trail and the network are the same lines and can be matched
         * geometrically. (A map tile genuinely is a picture, and nothing in
         * one can be routed on — that part of the idea cannot be done at
         * any price.)
         *
         * Cleared first, because the graph is cached across plans and last
         * search's trails are not this one's.
         */
        fun markTrails(lines: List<List<En>>, withinM: Double = 15.0) {
            for (list in edges) for (e in list) e.onTrail = false
            if (lines.isEmpty()) return
            // A grid over the trail segments. Testing every edge against
            // every trail point is tens of millions of distance tests on a
            // real network; this makes it a handful per edge.
            val cell = 50.0
            fun key(e: Double, n: Double): Long =
                (Math.floor(e / cell).toLong() shl 32) xor (Math.floor(n / cell).toLong() and 0xffffffffL)
            val index = HashMap<Long, MutableList<Pair<En, En>>>()
            for (line in lines) {
                for (i in 1 until line.size) {
                    val a = line[i - 1]
                    val b = line[i]
                    val seg = a to b
                    // Every cell the segment's own box touches, so a long
                    // segment is findable from anywhere along it.
                    var ce = Math.floor(minOf(a.e, b.e) / cell).toLong()
                    val toE = Math.floor(maxOf(a.e, b.e) / cell).toLong()
                    while (ce <= toE) {
                        var cn = Math.floor(minOf(a.n, b.n) / cell).toLong()
                        val toN = Math.floor(maxOf(a.n, b.n) / cell).toLong()
                        while (cn <= toN) {
                            index.getOrPut((ce shl 32) xor (cn and 0xffffffffL)) { ArrayList() }
                                .add(seg)
                            cn++
                        }
                        ce++
                    }
                }
            }
            for (u in nodes.indices) {
                for (e in edges[u]) {
                    // The midpoint, not an end: two ways meeting a trail at a
                    // junction share that node, and testing ends alone would
                    // mark every side lane hanging off it.
                    val mid = En(
                        (nodes[u].e + nodes[e.to].e) / 2,
                        (nodes[u].n + nodes[e.to].n) / 2,
                    )
                    var near = false
                    var dx = -1
                    while (dx <= 1 && !near) {
                        var dy = -1
                        while (dy <= 1 && !near) {
                            val bucket = index[key(mid.e + dx * cell, mid.n + dy * cell)]
                            if (bucket != null) {
                                for ((a, b) in bucket) {
                                    val q = Geom.nearestOnSegment(mid, a, b)
                                    if (hypot(q.e - mid.e, q.n - mid.n) <= withinM) {
                                        near = true
                                        break
                                    }
                                }
                            }
                            dy++
                        }
                        dx++
                    }
                    if (near) e.onTrail = true
                }
            }
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
        /** Metres on each actual highway class, so a route that used roads
         *  can say which ones rather than lumping them together. */
        val byKind: Map<String, Double> = emptyMap(),
        /** Metres of it running along a named, waymarked route — counted
         *  off the edges actually walked, never estimated, so "3.1 km of it
         *  is on the Slate Trail" is a checkable claim and not a flourish. */
        val trailM: Double = 0.0,
    ) {

        /** "180 m on a B road, 40 m on an A road", or null if it kept off
         *  them entirely. Named the way a walker would name them. */
        fun roadSummary(): String? {
            val names = linkedMapOf(
                "tertiary" to "a minor road", "tertiary_link" to "a minor road",
                "secondary" to "a B road", "secondary_link" to "a B road",
                "primary" to "an A road", "primary_link" to "an A road",
            )
            val out = LinkedHashMap<String, Double>()
            for ((kind, metres) in byKind) {
                val name = names[kind] ?: continue
                out[name] = (out[name] ?: 0.0) + metres
            }
            if (out.isEmpty()) return null
            return out.entries.joinToString(", ") { "${it.value.roundToInt()} m on ${it.key}" }
        }
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
     * The most a graph fetch will ever be asked to cover in one Overpass
     * query, whatever a caller's own arithmetic comes to.
     *
     * The route-relation search clips its geometry and caps its result
     * count (RouteFinder.fromOsm's `out geom($clip) 40`), so it stays small
     * at any radius. This one has no such cap — every matching way comes
     * back in full, at whatever density the ground has — so an unbounded
     * ask (a wide "anywhere on this map" start licence, added on top of a
     * loop's own reach) can run to tens of megabytes on a path-dense area.
     * A free Overpass mirror answers a request like that with a crash
     * (500), a dead upstream (502), or a refusal before it even tries —
     * which is what "OpenStreetMap search failed" turned out to be at
     * Anglesey: not a bad connection, a query too heavy for the servers
     * asked to run it. Capped here, the one choke point every caller
     * passes through, rather than trusted to each caller's own sums.
     */
    const val MAX_GRAPH_RADIUS_M = 15_000.0

    /**
     * The network round a point, reusing the last one when it covers the
     * ask. Fetching and parsing several megabytes of Overpass JSON is the
     * bulk of a plan, and re-planning a few hundred metres away used to pay
     * it again from scratch.
     */
    fun buildCached(centre: En, radiusM: Double): Graph {
        // Clamped before the cache comparison, not just before the fetch —
        // otherwise a graph actually built to the cap would still record
        // the caller's larger ask as cachedRadius, and the next plan a
        // little further off would wrongly read the cache as covering
        // ground that was never fetched.
        val r = radiusM.coerceAtMost(MAX_GRAPH_RADIUS_M)
        val c = cachedCentre
        val g = cachedGraph
        if (c != null && g != null) {
            val moved = hypot(centre.e - c.e, centre.n - c.n)
            if (moved + r <= cachedRadius) {
                // Last plan's trails are not this plan's, and a cached graph
                // outlives both. Handing one back still carrying yesterday's
                // discounts would quietly bend a walk toward a trail nobody
                // fetched — and the walk would then claim metres on it.
                g.markTrails(emptyList())
                return g
            }
        }
        val fresh = build(centre, r)
        cachedGraph = fresh
        cachedCentre = centre
        cachedRadius = r
        return fresh
    }

    /** Let the network go when the system is short of memory. */
    fun trim() {
        cachedGraph = null
        cachedCentre = null
    }

    fun build(centre: En, radiusM: Double): Graph {
        // A second, cheap clamp: build() is public and GeoTools calls it
        // directly as well as through buildCached, so the cap has to hold
        // here too rather than only on the cached path.
        val r = radiusM.coerceAtMost(MAX_GRAPH_RADIUS_M)
        val (lat, lon) = Bng.toWgs84(centre)
        val at = "%.5f,%.5f".format(lat, lon)
        val kinds = COST.keys.joinToString("|")
        val end = "${'$'}"
        // Clip the geometry to the area actually being planned in — the
        // same reasoning as RouteFinder.fromOsm's clip box. Without it a
        // single long B-road with one node inside the radius drags its
        // whole length, sometimes kilometres beyond the edge, into the
        // reply: exactly the kind of oversized response a free mirror
        // answers with a crash rather than data.
        val (south, west) = Bng.toWgs84(En(centre.e - r, centre.n - r))
        val (north, east) = Bng.toWgs84(En(centre.e + r, centre.n + r))
        val clip = "%.5f,%.5f,%.5f,%.5f".format(south, west, north, east)
        val query = "[out:json][timeout:60];" +
            "way[\"highway\"~\"^($kinds)$end\"]" +
            "[\"foot\"!~\"^(no|private)$end\"]" +
            "[\"access\"!~\"^(no|private)$end\"]" +
            "(around:${r.toInt()},$at);" +
            "out geom($clip);"
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
                        val plain = d * cost
                        val avoid = if (isRoad) plain * ROAD_AVOID_FACTOR else plain
                        edges[prev].add(Graph.Edge(idx, d, plain, kind, isRoad, avoid))
                        edges[idx].add(Graph.Edge(prev, d, plain, kind, isRoad, avoid))
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
        avoidRoads: Boolean = false,
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
                var step = if (avoidRoads) e.avoidCost else e.cost
                if (e.onTrail) step *= TRAIL_BONUS
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
        val byKind = HashMap<String, Double>()
        val seen = HashMap<Long, Double>()
        var trail = 0.0
        for (i in 1 until walk.size) {
            val e = g.edges[walk[i - 1]].firstOrNull { it.to == walk[i] } ?: continue
            total += e.metres
            byGroup[group(e.kind)] = (byGroup[group(e.kind)] ?: 0.0) + e.metres
            byKind[e.kind] = (byKind[e.kind] ?: 0.0) + e.metres
            if (e.onTrail) trail += e.metres
            val key = edgeKey(walk[i - 1], walk[i])
            if (seen.put(key, e.metres) != null) repeated += e.metres
        }
        return Planned(
            pts, total, byGroup, if (total > 0) repeated / total else 0.0, byKind, trail,
        )
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
     * A circular walk of about [targetM] — the best of [loops].
     */
    fun loop(
        g: Graph,
        start: En,
        targetM: Double,
        deadlineMs: Long = Long.MAX_VALUE,
        avoidRoads: Boolean = true,
        startSlackM: Double = START_SLACK_M,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Planned? =
        loops(g, start, targetM, deadlineMs, avoidRoads, startSlackM, 1, isCancelled, onProgress)
            .firstOrNull()

    /** How alike two circuits may be and still both be offered: share more
     *  ground than this and the second is the first wearing a hat. */
    private const val DISTINCT_OVERLAP = 0.5

    /** Near-identical: the same candidate found again down a different
     *  branch of the search, kept once at its better score. */
    private const val SAME_OVERLAP = 0.9

    private class Candidate(val planned: Planned, val edges: HashSet<Long>, val score: Double)

    /**
     * Up to [wanted] genuinely different circular walks of about [targetM],
     * best first.
     *
     * Two changes of heart over the single-answer version, both his.
     *
     * "Doesn't quite hit the mark" was partly the scoring: a candidate was
     * judged on its length and how much it doubled back, and on nothing
     * else — so a loop that measured right but spent a third of itself on
     * lanes beat a prettier one 10% long. The score now reads the ground
     * off the walk itself: road metres count against it, waymarked-trail
     * metres count for it, both measured the way the road summary already
     * is, never estimated.
     *
     * And it used to stop at the first candidate that cleared the bar, with
     * most of its ninety seconds unspent, throwing away every other circuit
     * it had closed on the way. Now everything that closes is kept, scored,
     * and deduplicated by the ground it covers — two loops sharing most of
     * their edges are one loop found twice — and the search only stops
     * early once it holds [wanted] good ones that are genuinely different
     * walks. "Go to an area and have loads of walks" starts here: one
     * network fetch, several answers.
     *
     * Corners still hang on junctions, re-walked edges still cost tenfold,
     * and whatever doubles back is still pruned — the things that make
     * "circular" mean a circuit are unchanged.
     */
    fun loops(
        g: Graph,
        start: En,
        targetM: Double,
        deadlineMs: Long = Long.MAX_VALUE,
        avoidRoads: Boolean = true,
        startSlackM: Double = START_SLACK_M,
        wanted: Int = 3,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): List<Planned> {
        val startNodes = startsFor(g, start, startSlackM)
        if (startNodes.isEmpty()) return emptyList()

        var base = targetM / (2 * Math.PI)
        val kept = ArrayList<Candidate>()
        var tried = 0

        fun outOfTime() = System.currentTimeMillis() > deadlineMs || isCancelled()

        fun overlap(a: HashSet<Long>, b: HashSet<Long>): Double {
            val small = if (a.size < b.size) a else b
            val large = if (a.size < b.size) b else a
            if (small.isEmpty()) return 0.0
            var both = 0
            for (k in small) if (k in large) both++
            return both.toDouble() / small.size
        }

        fun isGood(c: Candidate): Boolean {
            val err = abs(c.planned.metres - targetM) / targetM
            return err < GOOD_ERROR && c.planned.repeatFraction < GOOD_REPEAT
        }

        /** Best-first greedy pick of circuits that share under half their
         *  ground — the list that is actually returned. */
        fun distinct(): List<Candidate> {
            val ranked = kept.sortedBy { it.score }
            val out = ArrayList<Candidate>()
            for (c in ranked) {
                if (out.size >= wanted) break
                if (out.none { overlap(it.edges, c.edges) > DISTINCT_OVERLAP }) out.add(c)
            }
            return out
        }

        fun keep(planned: Planned, edges: HashSet<Long>): Candidate {
            val err = abs(planned.metres - targetM) / targetM
            // The ground, read off the walk: roads count against a
            // candidate, a waymarked trail counts for it. The weights are
            // stated here because they are a judgement — a loop would give
            // up ~8% of length accuracy to lose 10% of itself from roads.
            val score = err +
                planned.repeatFraction * 1.5 +
                (planned.roadMetres() / planned.metres) * 0.8 -
                (planned.trailM / planned.metres) * 0.3
            val c = Candidate(planned, edges, score)
            val twin = kept.firstOrNull { overlap(it.edges, edges) > SAME_OVERLAP }
            if (twin != null) {
                if (score < twin.score) { kept.remove(twin); kept.add(c) }
            } else {
                kept.add(c)
            }
            if (kept.size > 40) {
                kept.sortBy { it.score }
                while (kept.size > 24) kept.removeAt(kept.size - 1)
            }
            return c
        }

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
                                    val leg = path(g, cursor, wp, used, avoidRoads) ?: continue
                                    for (i in 1 until leg.size) used.add(edgeKey(leg[i - 1], leg[i]))
                                    if (walk.isEmpty()) walk.add(leg.first())
                                    walk.addAll(leg.drop(1))
                                    cursor = wp
                                }
                                if (walk.isEmpty()) continue
                                val home = path(g, cursor, startNode, used, avoidRoads) ?: continue
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
                                // Absurdly long is not "the wrong length",
                                // it is the wrong walk. A four-corner shape
                                // on a wandering network can route to many
                                // times the polygon it was aimed at, and
                                // scoring alone let a 50 km answer through
                                // to a 5-15 km ask because nothing else had
                                // closed. Short is still kept — a real
                                // circuit under the ask beats none at all —
                                // so the guard is deliberately one-sided.
                                if (planned.metres > targetM * MAX_OVERSHOOT) continue
                                val keys = HashSet<Long>(circuit.size)
                                for (i in 1 until circuit.size) {
                                    keys.add(edgeKey(circuit[i - 1], circuit[i]))
                                }
                                val c = keep(planned, keys)
                                if (c.score < roundScore) { roundScore = c.score; round = planned }
                                // Early out only once there are enough GOOD
                                // circuits that are genuinely different
                                // walks — one nearly-right answer no longer
                                // ends a search with a minute in hand.
                                if (isGood(c)) {
                                    val goodDistinct = distinct().count { isGood(it) }
                                    if (goodDistinct >= wanted) {
                                        enough = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (enough) break
            val r = round
            if (r == null) {
                onProgress("Round ${attempt + 1}: nothing closed yet, trying a different shape…")
                base *= 0.8
                continue
            }
            val err = abs(r.metres - targetM) / targetM
            onProgress(
                "Round ${attempt + 1}: ${"%.1f".format(r.metres / 1000)} km" +
                    (if (r.repeatFraction > 0.12) ", still doubling back…" else "…"),
            )
            if (err < GOOD_ERROR && r.repeatFraction < GOOD_REPEAT && kept.size >= wanted) break
            base *= (targetM / r.metres).coerceIn(0.55, 1.8)
        }
        if (kept.isEmpty() && tried > 0) {
            onProgress("Tried $tried shapes; none of them came home.")
        }
        // Best seen, always — loops of the wrong length, stated honestly,
        // beat two minutes ending in "couldn't".
        return distinct().map { it.planned }
    }

    /** Loops shorter than this are noise, not walks. */
    private const val MIN_LOOP_M = 300.0

    /** How many times the asked length a candidate may reach before it is a
     *  different walk rather than an imprecise one. */
    private const val MAX_OVERSHOOT = 2.2

    /** How far from him a walk may start — his own licence, and the default
     *  when he has not said otherwise. */
    const val START_SLACK_M = 500.0

    /** Past this, the licence is a *region* rather than a doorstep, and the
     *  starts have to be spread across it rather than taken nearest-first. */
    private const val REGION_SLACK_M = 800.0

    /**
     * Where a walk may begin. He said his start "can be anywhere within
     * 500 m", which is a licence worth spending: a junction a street away
     * often closes a circuit his exact doorstep cannot.
     *
     * "Anywhere on this screen" is the same licence written large, and it
     * needs different arithmetic. Nearest-first would hand back a dozen
     * junctions all within a hundred metres of the middle of the map —
     * technically inside the region, and not what a person means by
     * anywhere on it. Past [REGION_SLACK_M] the candidates are gathered
     * from points spread around the region instead, so the whole of it is
     * genuinely in play.
     */
    fun startsFor(g: Graph, start: En, slackM: Double): List<Int> {
        val out = ArrayList<Int>()
        g.nearest(start)?.let { out.add(it) }
        for (j in g.junctionsNear(start, minOf(slackM, REGION_SLACK_M), 3)) {
            if (j !in out) out.add(j)
        }
        if (slackM > REGION_SLACK_M) {
            for (k in 0 until 6) {
                val a = k * Math.PI / 3
                // 0.55 out and 0.4 of reach: they sum to less than one, so
                // every start this returns is genuinely inside the licence
                // he gave. Ring plus reach adding up to more than the
                // licence would quietly plan walks starting outside it.
                val p = En(start.e + sin(a) * slackM * 0.55, start.n + cos(a) * slackM * 0.55)
                val j = g.nearestJunction(p, slackM * 0.4) ?: continue
                if (j !in out) out.add(j)
            }
        }
        return out
    }

    /**
     * There and back: out to something worth turning round at, then home
     * the way you came.
     *
     * A deliberately different search from [loop], because they are
     * different walks. A loop wants corners and hates retracing; an
     * out-and-back retraces all of itself on purpose and wants only a good
     * turning point at about half the distance. That difference is also why
     * this one answers on ground the loop-finder cannot: a dead-end lane out
     * to a headland is a fine walk, and [Graph.nearestJunction] refuses a
     * dead end by design, so no circuit search will ever offer it.
     */
    fun outAndBack(
        g: Graph,
        start: En,
        targetM: Double,
        deadlineMs: Long = Long.MAX_VALUE,
        avoidRoads: Boolean = true,
        startSlackM: Double = START_SLACK_M,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Planned? {
        // The same licence as a loop's: where he is willing to begin. A
        // there-and-back is even more sensitive to it, because the whole
        // walk is decided by the one direction it sets off in.
        val froms = startsFor(g, start, startSlackM)
        if (froms.isEmpty()) return null
        val half = targetM / 2
        var best: Planned? = null
        var bestErr = Double.MAX_VALUE
        val spin = Math.random() * 2 * Math.PI
        // The turning point is aimed *nearer* than half the distance,
        // because a path never goes where a straight line goes: crow-flies
        // to the turn is always shorter than the walk to it.
        for (reach in doubleArrayOf(0.8, 0.6, 0.95)) {
            for (from in froms) {
                // Bearings are taken from the node the walk would actually
                // leave, not from the middle of the licence: aiming a
                // turning point relative to a place he isn't standing puts
                // half of them behind him.
                val origin = g.nodes[from]
                for (b in 0 until 8) {
                    if (System.currentTimeMillis() > deadlineMs || isCancelled()) return best
                    val ang = spin + b * Math.PI / 4
                    val aim = En(
                        origin.e + sin(ang) * half * reach,
                        origin.n + cos(ang) * half * reach,
                    )
                    // Any connected node will do. Unlike a loop's corner, a
                    // dead end is a perfectly good place to turn round —
                    // often the best one there is.
                    val to = g.nearestWhere(aim, half * 0.35 + 300.0) { g.edges[it].isNotEmpty() }
                        ?: continue
                    if (to == from) continue
                    val walk = path(g, from, to, emptySet(), avoidRoads) ?: continue
                    val out = measure(g, walk)
                    if (out.metres < MIN_LOOP_M / 2) continue
                    val err = abs(out.metres * 2 - targetM) / targetM
                    if (err < bestErr) {
                        bestErr = err
                        val doubled = doubleBack(out)
                        best = doubled
                        onProgress(
                            "Found a there-and-back of %.1f km\u2026".format(doubled.metres / 1000),
                        )
                    }
                    if (bestErr < GOOD_ERROR) return best
                }
            }
        }
        return best
    }

    /** One leg walked twice. Its repeat fraction is 1.0 and that is not a
     *  failure — it is the shape he asked for, and anything downstream that
     *  reads the number should say "there and back", never "retraces". */
    private fun doubleBack(out: Planned): Planned = Planned(
        points = out.points + out.points.reversed().drop(1),
        metres = out.metres * 2,
        byGroup = out.byGroup.mapValues { it.value * 2 },
        repeatFraction = 1.0,
        byKind = out.byKind.mapValues { it.value * 2 },
        trailM = out.trailM * 2,
    )

    /** Point-to-point on the same graph, so the same rules apply. */
    fun between(g: Graph, from: En, to: En, avoidRoads: Boolean = true): Planned? {
        val a = g.nearest(from) ?: return null
        val b = g.nearest(to) ?: return null
        val walk = path(g, a, b, emptySet(), avoidRoads) ?: return null
        return measure(g, walk)
    }

}
