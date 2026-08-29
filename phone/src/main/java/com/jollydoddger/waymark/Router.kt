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
    }

    data class Planned(
        val points: List<En>,
        val metres: Double,
        /** Metres walked on each of "path" / "lane" / "road". */
        val byGroup: Map<String, Double>,
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
        val json = Net.post(
            "https://overpass-api.de/api/interpreter",
            "data=" + Net.encode(query),
            "application/x-www-form-urlencoded",
            timeoutMs = 70_000,
        )

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
                val step = if (key in penalise) e.cost * 4 else e.cost
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

    /** Turn a node walk into points and per-kind distances. */
    private fun measure(g: Graph, walk: List<Int>): Planned {
        val pts = walk.map { g.nodes[it] }
        var total = 0.0
        val byGroup = HashMap<String, Double>()
        for (i in 1 until walk.size) {
            val e = g.edges[walk[i - 1]].firstOrNull { it.to == walk[i] } ?: continue
            total += e.metres
            byGroup[group(e.kind)] = (byGroup[group(e.kind)] ?: 0.0) + e.metres
        }
        return Planned(pts, total, byGroup)
    }

    /**
     * A circular walk of about [targetM], starting and finishing at [start].
     *
     * Three waypoints are hung on a circle and joined by A*; if the result
     * comes back long or short the circle is rescaled and it goes again.
     * That loop is the "make it shorter" lever a general router could not
     * give — the length is converged on, not accepted.
     */
    fun loop(
        g: Graph,
        start: En,
        targetM: Double,
        onProgress: (String) -> Unit = {},
    ): Planned? {
        val startNode = g.nearest(start) ?: return null
        var radius = targetM / (2 * Math.PI)
        var best: Planned? = null

        repeat(3) { attempt ->
            val spin = Math.random() * 2 * Math.PI
            var round: Planned? = null
            for (seed in 0 until 3) {
                val bearing0 = spin + seed * 2 * Math.PI / 3
                val walk = ArrayList<Int>()
                val used = HashSet<Long>()
                var cursor = startNode
                var ok = true
                for (k in 0..2) {
                    val b = bearing0 + k * 2 * Math.PI / 3
                    val ideal = En(start.e + radius * sin(b), start.n + radius * cos(b))
                    val wp = g.nearest(ideal) ?: continue
                    if (wp == cursor) continue
                    val leg = path(g, cursor, wp, used)
                    if (leg == null) { ok = false; break }
                    for (i in 1 until leg.size) used.add(edgeKey(leg[i - 1], leg[i]))
                    if (walk.isEmpty()) walk.addAll(leg) else walk.addAll(leg.drop(1))
                    cursor = wp
                }
                if (!ok) continue
                val home = path(g, cursor, startNode, used) ?: continue
                walk.addAll(home.drop(1))
                if (walk.size < 4) continue
                val planned = measure(g, walk)
                if (planned.metres < targetM * 0.3) continue
                if (round == null || abs(planned.metres - targetM) < abs(round!!.metres - targetM)) {
                    round = planned
                }
            }
            val r = round ?: return@repeat
            if (best == null || abs(r.metres - targetM) < abs(best!!.metres - targetM)) best = r
            val err = abs(r.metres - targetM) / targetM
            onProgress("Loop ${attempt + 1}: ${"%.1f".format(r.metres / 1000)} km…")
            if (err < 0.12) return best
            // Too long or too short: pull the circle in or push it out and
            // go again. This is the lever the old planner did not have.
            radius *= (targetM / r.metres).coerceIn(0.55, 1.8)
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
