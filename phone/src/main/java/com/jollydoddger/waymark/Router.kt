package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import java.util.PriorityQueue
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
    )

    /** Never routed on foot, whatever the setting: no pavement, no business. */
    private val NEVER = setOf("motorway", "motorway_link", "trunk", "trunk_link")

    /** Excluded as well when he asks to avoid roads — the A and B classes. */
    private val ROADS = setOf("primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link")

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
        class Edge(val to: Int, val metres: Double, val cost: Double, val kind: String)

        fun nearest(p: En): Int? {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in nodes.indices) {
                val d = hypot(nodes[i].e - p.e, nodes[i].n - p.n)
                if (d < bestD) { bestD = d; best = i }
            }
            return if (best >= 0) best else null
        }

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
    fun build(centre: En, radiusM: Double, avoidRoads: Boolean): Graph {
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
            if (avoidRoads && kind in ROADS) continue
            val cost = COST[kind] ?: continue
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
                        edges[prev].add(Graph.Edge(idx, d, d * cost, kind))
                        edges[idx].add(Graph.Edge(prev, d, d * cost, kind))
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
    fun path(g: Graph, from: Int, to: Int, penalise: Set<Long> = emptySet()): List<Int>? {
        val n = g.nodes.size
        if (from >= n || to >= n) return null
        val best = DoubleArray(n) { Double.MAX_VALUE }
        val cameFrom = IntArray(n) { -1 }
        val done = BooleanArray(n)
        val target = g.nodes[to]
        fun heuristic(i: Int) = hypot(g.nodes[i].e - target.e, g.nodes[i].n - target.n)

        val queue = PriorityQueue<DoubleArray>(compareBy { it[0] })
        best[from] = 0.0
        queue.add(doubleArrayOf(heuristic(from), from.toDouble()))
        while (queue.isNotEmpty()) {
            val top = queue.poll()
            val u = top[1].toInt()
            if (done[u]) continue
            done[u] = true
            if (u == to) break
            for (e in g.edges[u]) {
                if (done[e.to]) continue
                val key = edgeKey(u, e.to)
                val step = if (key in penalise) e.cost * REUSE_PENALTY else e.cost
                val alt = best[u] + step
                if (alt < best[e.to]) {
                    best[e.to] = alt
                    cameFrom[e.to] = u
                    queue.add(doubleArrayOf(alt + heuristic(e.to), e.to.toDouble()))
                }
            }
        }
        if (best[to] == Double.MAX_VALUE) return null
        val out = ArrayList<Int>()
        var cur = to
        while (cur != -1) { out.add(cur); cur = cameFrom[cur] }
        return out.reversed()
    }

    private fun edgeKey(a: Int, b: Int): Long =
        (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()

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
        onProgress: (String) -> Unit = {},
    ): Planned? {
        val startNode = g.nearest(start) ?: return null
        var base = targetM / (2 * Math.PI)
        var best: Planned? = null
        var bestScore = Double.MAX_VALUE

        repeat(3) { attempt ->
            val spin = Math.random() * 2 * Math.PI
            var round: Planned? = null
            var roundScore = Double.MAX_VALUE
            var enough = false

            for (mult in doubleArrayOf(1.0, 0.72, 1.35)) {
                if (enough) break
                val radius = base * mult
                for (seed in 0 until 2) {
                    if (enough) break
                    for (corners in intArrayOf(4, 3)) {
                        if (enough) break
                        for (direction in intArrayOf(1, -1)) {
                            val bearing0 = spin + seed * (Math.PI / corners)
                            val walk = ArrayList<Int>()
                            val used = HashSet<Long>()
                            var cursor = startNode
                            for (k in 0 until corners) {
                                val b = bearing0 + direction * k * 2 * Math.PI / corners
                                val ideal = En(start.e + radius * sin(b), start.n + radius * cos(b))
                                val wp = g.nearestJunction(ideal, radius * 0.6) ?: continue
                                if (wp == cursor) continue
                                // A corner in another piece of the network is
                                // a corner missed, not a loop lost: three
                                // sides of a square still comes home.
                                val leg = path(g, cursor, wp, used) ?: continue
                                for (i in 1 until leg.size) used.add(edgeKey(leg[i - 1], leg[i]))
                                if (walk.isEmpty()) walk.addAll(leg) else walk.addAll(leg.drop(1))
                                cursor = wp
                            }
                            if (walk.isEmpty()) continue
                            val home = path(g, cursor, startNode, used) ?: continue
                            walk.addAll(home.drop(1))

                            // Pruning cannot open a closed walk — the first
                            // and last nodes are the two it never removes —
                            // so what survives is either a circuit or
                            // nothing. Nothing means the shape was a pure
                            // out-and-back, which is not a circular walk
                            // whatever its length, so it is dropped rather
                            // than offered.
                            val circuit = prune(walk)
                            if (circuit.size < 4) continue
                            val planned = measure(g, circuit)
                            if (planned.metres < targetM * 0.25) continue
                            val err = abs(planned.metres - targetM) / targetM
                            val score = err + planned.repeatFraction * 1.5
                            if (score < roundScore) { roundScore = score; round = planned }
                            if (err < GOOD_ERROR && planned.repeatFraction < GOOD_REPEAT) {
                                enough = true
                                break
                            }
                        }
                    }
                }
            }

            val r = round ?: return@repeat
            if (roundScore < bestScore) { bestScore = roundScore; best = r }
            val err = abs(r.metres - targetM) / targetM
            onProgress(
                "Loop ${attempt + 1}: ${"%.1f".format(r.metres / 1000)} km" +
                    (if (r.repeatFraction > 0.12) ", still doubling back…" else "…"),
            )
            if (err < GOOD_ERROR && r.repeatFraction < GOOD_REPEAT) return best
            // Too long or too short: pull the circle in or push it out and go
            // again. This is the lever the old planner did not have.
            base *= (targetM / r.metres).coerceIn(0.55, 1.8)
        }
        return best
    }

    /** Point-to-point on the same graph, so the same rules apply. */
    fun between(g: Graph, from: En, to: En): Planned? {
        val a = g.nearest(from) ?: return null
        val b = g.nearest(to) ?: return null
        val walk = path(g, a, b) ?: return null
        return measure(g, walk)
    }
}
