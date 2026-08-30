package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Poi
import com.jollydoddger.waymark.shared.PoiStore
import com.jollydoddger.waymark.shared.Route
import com.jollydoddger.waymark.shared.RouteStore
import com.jollydoddger.waymark.shared.Gpx
import com.jollydoddger.waymark.shared.Sun
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The facts behind the assistant, every one deterministic: grid arithmetic
 * the app already does, and real databases — OSM's Overpass, the Toilet Map,
 * the FOSSGIS foot router, Open-Meteo, Nominatim. Claude decides *which* of
 * these to ask and puts the answer into words; nothing in here is generated.
 *
 * Everything external speaks WGS84; Bng.toWgs84 / fromWgs84 bridge at each
 * edge. Results that belong on the map (POIs, a planned route) are saved by
 * these functions themselves — a tool's effect must be real, not narrated.
 */
class GeoTools(
    private val ctx: Context,
    /** The freshest GPS fix the activity holds, or null. */
    private val fix: () -> En?,
    /** Age of that fix in ms, so "where am I" can admit staleness. */
    private val fixAgeMs: () -> Long = { 0L },
    /** Progress for the slow ones — planning is several calls, not one. */
    private val progress: (String) -> Unit = {},
    /**
     * Whether he has pressed stop. The assistant's own cancellation lands
     * between tools; a plan is the one tool that can run for a minute and a
     * half, so it has to be able to hear the button itself.
     */
    private val cancelled: () -> Boolean = { false },
) {

    private fun route(): Route? = RouteStore.load(ctx)

    /** A downloaded GPX bigger than this is not a walking route. */
    private val MAX_GPX_BYTES = 5 * 1024 * 1024

    /**
     * The line every download failure ends with: the browser can get past
     * the cookie walls, scripts and logins this app never will, and a GPX
     * he downloads there imports into Waymark by opening or sharing the
     * file — so a failed fetch becomes his one tap, not a dead end.
     */
    private fun handItOver(pageUrl: String): String =
        "Give him this link to open in his browser: $pageUrl — a GPX downloaded " +
            "there and opened or shared with Waymark imports straight onto the map."

    /** The .gpx links a walk page holds, resolved against the page's own
     *  URL, deduplicated, capped — enough to choose from, never a sitemap. */
    private fun gpxLinks(html: String, pageUrl: String): List<String> {
        val re = Regex("""href\s*=\s*["']([^"']*\.gpx[^"']*)["']""", RegexOption.IGNORE_CASE)
        val base = java.net.URL(pageUrl)
        return re.findAll(html)
            .mapNotNull { m ->
                runCatching { java.net.URL(base, m.groupValues[1]).toString() }.getOrNull()
            }
            .distinct()
            .take(6)
            .toList()
    }


    private fun km(m: Double) = "%.1f km".format(m / 1000)

    private fun distanceAndBearing(from: En, to: En): Pair<Double, String> {
        val d = hypot(to.e - from.e, to.n - from.n)
        val deg = (Math.toDegrees(atan2(to.e - from.e, to.n - from.n)) + 360) % 360
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return d to dirs[((deg + 22.5) / 45).toInt()]
    }

    // --- route arithmetic ----------------------------------------------------

    fun routeInfo(): String {
        val r = route() ?: return "No route is loaded. Import a GPX or plan one."
        var total = 0.0
        for (i in 1 until r.points.size) {
            total += hypot(
                r.points[i].e - r.points[i - 1].e,
                r.points[i].n - r.points[i - 1].n,
            )
        }
        val sb = StringBuilder("Route \"${r.name}\": ${km(total)} long (measured along the line).")
        val here = fix()
        if (here != null) {
            // Nearest point on the route to the fix, and distance along from it.
            var bestDist = Double.MAX_VALUE
            var alongAtBest = 0.0
            var along = 0.0
            for (i in 1 until r.points.size) {
                val a = r.points[i - 1]
                val b = r.points[i]
                val seg = hypot(b.e - a.e, b.n - a.n)
                if (seg > 0) {
                    val t = (((here.e - a.e) * (b.e - a.e) + (here.n - a.n) * (b.n - a.n)) / (seg * seg))
                        .coerceIn(0.0, 1.0)
                    val px = a.e + t * (b.e - a.e)
                    val py = a.n + t * (b.n - a.n)
                    val d = hypot(here.e - px, here.n - py)
                    if (d < bestDist) {
                        bestDist = d
                        alongAtBest = along + seg * t
                    }
                }
                along += seg
            }
            sb.append(
                " You are ${bestDist.roundToInt()} m from the route line; " +
                    "${km(alongAtBest)} of it is behind that point and " +
                    "${km(total - alongAtBest)} ahead (following the route's own direction).",
            )
        } else {
            sb.append(" No GPS fix yet, so no remaining-distance figure.")
        }
        return sb.toString()
    }

    fun routeProfile(): String {
        val r = route() ?: return "No route is loaded."
        // Sample every ~200 m, capped at 90 points (Open-Meteo batch limit is 100).
        var total = 0.0
        for (i in 1 until r.points.size) {
            total += hypot(r.points[i].e - r.points[i - 1].e, r.points[i].n - r.points[i - 1].n)
        }
        val step = maxOf(200.0, total / 90)
        val samples = ArrayList<En>()
        var next = 0.0
        var along = 0.0
        samples.add(r.points.first())
        for (i in 1 until r.points.size) {
            val a = r.points[i - 1]
            val b = r.points[i]
            val seg = hypot(b.e - a.e, b.n - a.n)
            while (seg > 0 && next + step <= along + seg) {
                next += step
                val t = (next - along) / seg
                samples.add(En(a.e + t * (b.e - a.e), a.n + t * (b.n - a.n)))
            }
            along += seg
        }
        samples.add(r.points.last())

        val lats = StringBuilder()
        val lons = StringBuilder()
        samples.forEachIndexed { i, en ->
            val (lat, lon) = Bng.toWgs84(en)
            if (i > 0) { lats.append(','); lons.append(',') }
            lats.append("%.5f".format(lat)); lons.append("%.5f".format(lon))
        }
        val json = Net.get("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons")
        val elev = JSONObject(json).getJSONArray("elevation")
        var ascent = 0.0
        var descent = 0.0
        var high = Double.MIN_VALUE
        var low = Double.MAX_VALUE
        var prev = elev.getDouble(0)
        for (i in 0 until elev.length()) {
            val e = elev.getDouble(i)
            if (e > high) high = e
            if (e < low) low = e
            val d = e - prev
            if (d > 0) ascent += d else descent -= d
            prev = e
        }
        return "Route \"${r.name}\": ${km(total)}, about ${ascent.roundToInt()} m of ascent and " +
            "${descent.roundToInt()} m of descent; highest point ~${high.roundToInt()} m, " +
            "lowest ~${low.roundToInt()} m. (Elevations from Open-Meteo's terrain model, ~90 m grid.)"
    }

    // --- finding places ------------------------------------------------------

    private val osmKinds = mapOf(
        "toilets" to "amenity=toilets",
        "cafe" to "amenity=cafe",
        "pub" to "amenity=pub",
        "bin" to "amenity=waste_basket",
        "water" to "amenity=drinking_water",
        "parking" to "amenity=parking",
        "defibrillator" to "emergency=defibrillator",
        "bus_stop" to "highway=bus_stop",
        "bench" to "amenity=bench",
    )

    fun findPlaces(kind: String, nearRoute: Boolean): String {
        val tag = osmKinds[kind] ?: return "Failed: unknown kind \"$kind\". " +
            "Kinds: ${osmKinds.keys.joinToString()}."
        val here = fix()
        val r = route()
        val anchor = here ?: r?.points?.firstOrNull()
            ?: return "No GPS fix and no route — nowhere to search around."

        // Search shape: circles along the route (sampled every ~800 m), or one
        // circle around the fix.
        val centres = if (nearRoute && r != null) {
            sampleAlong(r.points, 800.0).map { Bng.toWgs84(it) }
        } else {
            listOf(Bng.toWgs84(anchor))
        }
        val radius = if (nearRoute) 600 else 1500
        val around = centres.joinToString("") { (lat, lon) ->
            "nwr[$tag](around:$radius,%.5f,%.5f);".format(lat, lon)
        }
        val query = "[out:json][timeout:20];($around);out center 60;"
        val json = Net.overpass(query)

        val found = LinkedHashMap<String, Poi>()
        val elements = JSONObject(json).getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val lat = if (el.has("lat")) el.getDouble("lat") else el.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (el.has("lon")) el.getDouble("lon") else el.optJSONObject("center")?.optDouble("lon") ?: continue
            val name = el.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotBlank() }
                ?: kind.replaceFirstChar { it.uppercase() }
            val en = Bng.fromWgs84(lat, lon)
            found["%.0f,%.0f".format(en.e, en.n)] = Poi(name, kind, en)
        }

        // Toilets get a second, better UK source: the Toilet Map. Best-effort —
        // if their schema shifts, OSM still answered.
        var toiletMapCount = 0
        if (kind == "toilets") {
            runCatching {
                val (lat, lon) = Bng.toWgs84(anchor)
                val gql = JSONObject()
                    .put("query", "query(\$lat: Float!, \$lng: Float!, \$radius: Int!) { loosByProximity(from: { lat: \$lat, lng: \$lng, maxDistance: \$radius }) { name location { lat lng } } }")
                    .put("variables", JSONObject().put("lat", lat).put("lng", lon).put("radius", 3000))
                val resp = JSONObject(Net.post("https://www.toiletmap.org.uk/api", gql.toString(), "application/json"))
                val loos = resp.getJSONObject("data").getJSONArray("loosByProximity")
                for (i in 0 until loos.length()) {
                    val loo = loos.getJSONObject(i)
                    val at = loo.getJSONObject("location")
                    val en = Bng.fromWgs84(at.getDouble("lat"), at.getDouble("lng"))
                    found["%.0f,%.0f".format(en.e, en.n)] =
                        Poi(loo.optString("name").ifBlank { "Toilets" }, kind, en)
                    toiletMapCount++
                }
            }
        }

        val pois = found.values.toList().sortedBy { poi ->
            here?.let { hypot(poi.at.e - it.e, poi.at.n - it.n) } ?: 0.0
        }.take(40)
        PoiStore.save(ctx, pois)

        if (pois.isEmpty()) {
            return "Nothing tagged as $kind found ${if (nearRoute) "along the route" else "nearby"} in " +
                "OpenStreetMap${if (kind == "toilets") " or the Toilet Map" else ""}. " +
                "That is a statement about the databases, not the ground — coverage is patchy, " +
                "especially for bins."
        }
        val listing = pois.take(8).joinToString("\n") { poi ->
            val where = here?.let { val (d, dir) = distanceAndBearing(it, poi.at); "${d.roundToInt()} m $dir" }
                ?: Bng.gridRef(poi.at, 3).orEmpty()
            "- ${poi.name} ($where)"
        }
        val src = if (toiletMapCount > 0) "OpenStreetMap + the Toilet Map" else "OpenStreetMap"
        return "Marked ${pois.size} on the map (data: $src). Nearest:\n$listing"
    }

    private fun sampleAlong(pts: List<En>, stepM: Double): List<En> {
        val out = ArrayList<En>()
        out.add(pts.first())
        var next = stepM
        var along = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val seg = hypot(b.e - a.e, b.n - a.n)
            while (seg > 0 && next <= along + seg) {
                val t = (next - along) / seg
                out.add(En(a.e + t * (b.e - a.e), a.n + t * (b.n - a.n)))
                next += stepM
            }
            along += seg
        }
        return out
    }

    // --- routing -------------------------------------------------------------

    /**
     * Plan a walk, on Waymark's own network rather than a public router.
     *
     * Two things he asked for that a general foot router cannot do: hold a
     * length, and stay off the A-roads. Both are answered in [Router] — the
     * roads are left out of the graph, and the loop is re-run on a tighter
     * circle until the distance lands. What comes back is measured off the
     * edges actually walked, so the percentages here are counted, not
     * estimated.
     */
    fun planRoute(
        placeNames: List<String>,
        circularKm: Double,
        avoidRoads: Boolean = true,
        startPlace: String = "",
    ): String {
        // Planning from the sofa for tomorrow is as real a use as planning
        // on the doorstep, so the start can be a named place instead of here.
        val here = if (startPlace.isNotBlank()) {
            val g = geocode(startPlace)
                ?: return "Failed: couldn't find \"$startPlace\" to start from (Nominatim)."
            Bng.fromWgs84(g.first, g.second)
        } else {
            fix() ?: return "No GPS fix yet — either wait for one, or give me a place to start from."
        }

        if (placeNames.isNotEmpty()) {
            val targets = ArrayList<En>()
            for (name in placeNames) {
                val g = geocode(name) ?: return "Failed: couldn't find \"$name\" on the map (Nominatim)."
                targets.add(Bng.fromWgs84(g.first, g.second))
            }
            val span = targets.maxOf { hypot(it.e - here.e, it.n - here.n) }
            if (span > 15_000) {
                // Too far to hold a local network in memory; the public
                // router still answers, and the reply says which was used.
                progress("That's a long way — using the public router…")
                val waypoints = ArrayList<Pair<Double, Double>>()
                waypoints.add(Bng.toWgs84(here))
                targets.forEach { waypoints.add(Bng.toWgs84(it)) }
                if (circularKm > 0) waypoints.add(Bng.toWgs84(here))
                val routed = routeVia(waypoints)
                    ?: return "The router found no walkable route between those points."
                return adopt(
                    routed, "Planned walk",
                    "Over 15 km across, so this used the public foot router — which " +
                        "means no road-avoidance guarantee. Check the road casings on the map.",
                    source = "routed by the public FOSSGIS foot router",
                )
            }
            progress("Reading the paths and lanes round here…")
            val graph = Router.buildCached(here, span + 2_000)
            if (graph.nodes.size < 20) return noNetwork(avoidRoads)
            val points = ArrayList<En>()
            var metres = 0.0
            val byGroup = HashMap<String, Double>()
            var cursor = here
            for ((i, t) in targets.withIndex()) {
                progress("Leg ${i + 1} of ${targets.size}…")
                val leg = Router.between(graph, cursor, t, avoidRoads)
                    ?: return "No walkable way to \"${placeNames[i]}\" that keeps to the rules — " +
                        "try again with avoid_roads false, or a nearer place."
                if (points.isEmpty()) points.addAll(leg.points) else points.addAll(leg.points.drop(1))
                metres += leg.metres
                leg.byGroup.forEach { (k, v) -> byGroup[k] = (byGroup[k] ?: 0.0) + v }
                cursor = t
            }
            if (circularKm > 0) {
                Router.between(graph, cursor, here, avoidRoads)?.let { back ->
                    points.addAll(back.points.drop(1))
                    metres += back.metres
                    back.byGroup.forEach { (k, v) -> byGroup[k] = (byGroup[k] ?: 0.0) + v }
                }
            }
            // repeatFraction was left at its default here, so a
            // via-places route always claimed a clean circuit however much
            // of itself it retraced. Measured now, like the loops.
            val planned = Router.Planned(points, metres, byGroup, Router.repeatFraction(points))
            return adopt(planned.points to planned.metres, "Planned walk", describe(planned, null, avoidRoads))
        }

        if (circularKm <= 0) return "Failed: give either place names or a circular distance."

        val target = circularKm * 1000

        // Real walks first — established, named, actually walked — gathered
        // while the network downloads costs nothing extra and answers the
        // question better than an invented loop can.
        val real = runCatching {
            RouteFinder.find(ctx, here, 12_000.0).walks
        }.getOrDefault(emptyList()).let { found ->
            WalkFilter.filter(found, here, null, target * 0.65, target * 1.35)
        }.take(6)

        progress("Reading the paths and lanes round here…")
        val graph = runCatching {
            Router.buildCached(here, target / (2 * PI) * 1.9 + 900)
        }.getOrElse {
            return offer(real, emptyList(), target,
                "Couldn't reach OpenStreetMap's servers to read the paths (${it.message ?: "no connection"}).")
        }
        if (graph.nodes.size < 20) {
            return offer(real, emptyList(), target, noNetwork(avoidRoads))
        }

        progress("Building a loop…")
        val deadline = System.currentTimeMillis() + PLAN_BUDGET_MS
        val loop = Router.loop(
            graph, here, target, deadline, avoidRoads, cancelled,
        ) { note -> progress(note) }

        val planned = listOfNotNull(loop)
        if (planned.isEmpty() && real.isEmpty()) {
            return "Couldn't close a loop from here on " +
                (if (avoidRoads) "paths and quiet lanes" else "the walkable network round here") +
                ", and no established walk of about ${km(target)} passes nearby either. " +
                "A different distance, or a start a mile or two away, may work."
        }
        return offer(real, planned, target, null)
    }

    /** How long a plan may search before it must answer with its best. */
    private val PLAN_BUDGET_MS = 90_000L

    /**
     * Both kinds of answer on the one picker: walks that exist and a loop we
     * worked out, each labelled for what it is, so he chooses rather than
     * being handed a guess. Nothing replaces the route on the map — the
     * picker's Use and Start walk do that, with a preview in front of them.
     */
    private fun offer(
        real: List<RouteFinder.FoundWalk>,
        planned: List<Router.Planned>,
        target: Double,
        note: String?,
    ): String {
        val here = fix()
        val candidates = ArrayList<RouteFinder.FoundWalk>()
        planned.forEachIndexed { i, p ->
            candidates.add(
                RouteFinder.FoundWalk(
                    name = "Planned ${km(p.metres)} circular" + if (i > 0) " (${i + 1})" else "",
                    source = "Planned",
                    lines = listOf(p.points),
                    closestM = 0.0,
                    lengthM = p.metres,
                ),
            )
        }
        candidates.addAll(real)
        if (candidates.isEmpty()) return note ?: "Nothing to offer for that."
        WalkPicks.replace(ctx, candidates)

        val lines = StringBuilder()
        planned.forEach { p ->
            lines.append("\n- Planned ${km(p.metres)} circular (asked ${km(target)})")
            if (p.repeatFraction > 0.05) {
                lines.append(", retraces ${(p.repeatFraction * 100).roundToInt()}%")
            }
            val road = p.roadMetres()
            if (road > 50) lines.append(", ${road.roundToInt()} m of it on roads")
        }
        real.forEach { w ->
            val shape = if (isCircular(w)) "circular" else "linear"
            lines.append("\n- ${w.name}: ${km(w.lengthM)} $shape, line ${km(w.closestM)} away (${w.source})")
        }
        return (note?.plus(" ") ?: "") +
            "${candidates.size} to choose from, on the map's picker — " +
            "\u2039 \u203a to flick through, Use or Start walk to take one:" + lines
    }

    /** Does the walk come back to where it started? A coastal-path fragment
     *  can match on distance and still be a line, and saying which is free. */
    private fun isCircular(w: RouteFinder.FoundWalk): Boolean {
        val pts = w.routePoints()
        if (pts.size < 3 || w.lengthM <= 0) return false
        val gap = hypot(pts.last().e - pts.first().e, pts.last().n - pts.first().n)
        return gap < w.lengthM * 0.2
    }

    private fun noNetwork(avoidRoads: Boolean): String =
        "OpenStreetMap has almost no walkable ways mapped round here" +
            (if (avoidRoads) " once the A and B roads are excluded" else "") +
            ", so there is nothing to plan on. That is a gap in the map, not in the ground."

    /** What was actually built, in numbers counted off the route. */
    private fun describe(p: Router.Planned, target: Double?, avoidRoads: Boolean): String {
        val sb = StringBuilder()
        if (target != null) {
            val err = (p.metres - target) / target
            sb.append(
                when {
                    kotlin.math.abs(err) < 0.08 -> "That is on target. "
                    p.metres > target -> "That is ${"%.0f".format(err * 100)}% longer than asked — " +
                        "the network round here would not close a shorter loop. "
                    else -> "That is ${"%.0f".format(-err * 100)}% shorter than asked. "
                },
            )
            // Whether it is a circuit is a fact about the route, not a
            // flourish — a walk that retraces a fifth of itself is not what
            // "circular" means, and he should hear that from the app rather
            // than find it out on the ground.
            val retraced = (p.repeatFraction * 100).roundToInt()
            sb.append(
                when {
                    retraced < 3 -> "A clean circuit — no ground walked twice. "
                    retraced < 15 -> "Very nearly a clean circuit: $retraced% of it doubles back. "
                    else -> "It is not a tidy circle — $retraced% of it retraces itself, which " +
                        "is what the paths round here allow. "
                },
            )
        }
        val pathPct = (p.pathFraction() * 100).roundToInt()
        val lane = (p.byGroup["lane"] ?: 0.0)
        val road = p.roadMetres()
        sb.append("$pathPct% on paths, tracks and bridleways")
        if (lane > 50) sb.append("; ${km(lane)} on quiet lanes")
        sb.append(
            when {
                road < 50 && avoidRoads -> "; no A or B road walking at all."
                road < 50 -> "; no main-road walking."
                else -> "; ${km(road)} on bigger roads — look at the casings on the map before you commit."
            },
        )
        return sb.toString()
    }

    /**
     * Bank the old route and set this one. [source] names who planned it,
     * because the two are not equally answerable: Waymark's own router held
     * the rules it was given, the public one was handed the job whole.
     */
    private fun adopt(
        routed: Pair<List<En>, Double>,
        name: String,
        note: String?,
        source: String = "planned here on OpenStreetMap paths",
    ): String {
        RouteStore.save(ctx, Route(name, routed.first)) // save() banks the old route first
        return "Route set: ${km(routed.second)}. " + (note?.plus(" ") ?: "") +
            "It was $source — usually right, not gospel, so worth a glance against the " +
            "OS map. The previous route is banked; restore_previous_route brings it back."
    }

    /** One routing call: waypoints in, (line in grid metres, distance) out. */
    private fun routeVia(waypoints: List<Pair<Double, Double>>): Pair<List<En>, Double>? {
        val coords = waypoints.joinToString(";") { (lat, lon) -> "%.6f,%.6f".format(lon, lat) }
        val json = runCatching {
            Net.get(
                "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords" +
                    "?overview=full&geometries=geojson&steps=false",
            )
        }.getOrNull() ?: return null
        val routes = JSONObject(json).optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val best = routes.getJSONObject(0)
        val line = best.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<En>(line.length())
        for (i in 0 until line.length()) {
            val c = line.getJSONArray(i)
            pts.add(Bng.fromWgs84(c.getDouble(1), c.getDouble(0)))
        }
        if (pts.size < 2) return null
        return pts to best.getDouble("distance")
    }

    /**
     * The same search as the GPX button's "Walks near me" — OSM walking-route
     * relations plus his indexed GPX library — but reaching a drive away, and
     * narrowable by direction and length, because "a walk south-east, four to
     * six miles" is how the question actually arrives. Survivors are queued
     * on the map's picker, where choosing stays a deliberate act with a
     * preview in front of it.
     */
    fun findWalks(radiusKm: Double, bearing: String, minKm: Double, maxKm: Double): String {
        val here = fix() ?: return "No GPS fix yet — the search is centred on his position."
        val radius = (if (radiusKm <= 0) 5.0 else radiusKm).coerceAtMost(25.0) * 1000
        progress("Searching walking routes within ${km(radius)}…")
        val result = RouteFinder.find(ctx, here, radius)
        val filtered = WalkFilter.filter(
            result.walks, here, bearing.ifBlank { null }, minKm * 1000, maxKm * 1000,
        )
        val prefix = result.note?.plus("\n").orEmpty()
        if (filtered.isEmpty()) {
            val narrowed = if (result.walks.isEmpty()) "" else {
                " ${result.walks.size} were found before the direction/length filter — " +
                    "loosening it may help."
            }
            return prefix + "No walking route matches within ${km(radius)} — neither " +
                "OpenStreetMap's route relations nor his GPX library " +
                "(${Library.count(ctx)} routes indexed).$narrowed"
        }
        WalkPicks.replace(ctx, filtered)
        val listing = filtered.take(10).joinToString("\n") { w ->
            val towards = Sun.compass(WalkFilter.bearingDeg(here, WalkFilter.nearestPoint(here, w.lines)))
            "- ${w.name}: ${km(w.lengthM)} of path, line ${km(w.closestM)} $towards (${w.source})"
        }
        return prefix + "Walks matching, closest first:\n" + listing +
            "\nThey are queued on the map — Prev/Next previews each, " +
            "Use takes one, Start walk takes it and starts recording."
    }

    /**
     * A GPX file from a direct link, into the map's picker — never straight
     * onto the route. The guards are the whole tool: only http(s), only
     * content that actually parses as GPX, only a sane size. AllTrails,
     * komoot and OS Maps are refused by host as well as by the tool
     * description — their terms are not this app's to spend.
     */
    fun downloadGpx(url: String): String {
        var target = url
        var bytes: ByteArray? = null
        // Two passes: the second exists because a link that promised a route
        // very often serves the web page *around* the download. Rather than
        // bouncing that back as a failure, the page is read for the .gpx
        // links it holds — one link is followed, several are handed back to
        // choose from. One hop only; a page behind a page is a maze, not a
        // route.
        for (hop in 0 until 2) {
            val lower = target.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "Failed: only an http(s) link."
            }
            for (banned in listOf("alltrails", "komoot", "osmaps", "ordnancesurvey")) {
                if (banned in lower) return "Failed: $banned links are off-limits (their terms)."
            }
            progress(if (hop == 0) "Downloading…" else "Following the GPX link on that page…")
            val got = try {
                Net.stream(target, timeoutMs = 20_000) { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_GPX_BYTES) {
                            throw RuntimeException("bigger than ${MAX_GPX_BYTES / 1_000_000} MB")
                        }
                    }
                    out.toByteArray()
                }
            } catch (e: Exception) {
                return "Failed: couldn't download it (${e.message ?: "no connection"}). " +
                    handItOver(target)
            }
            val head = String(got, 0, minOf(got.size, 512), Charsets.UTF_8)
            if (Gpx.looksLikeGpx(head)) {
                bytes = got
                break
            }
            if (hop == 1) {
                return "Failed: that page's GPX link served another page rather than the file. " +
                    handItOver(url)
            }
            val links = gpxLinks(String(got, Charsets.UTF_8), target)
            when {
                links.isEmpty() -> return "Failed: that isn't a GPX file and the page holds " +
                    "no plain .gpx links — likely a button behind a script or a login. " +
                    handItOver(target)
                links.size == 1 -> target = links.first()
                else -> return "That page holds ${links.size} GPX links — call download_gpx " +
                    "with the right one:\n" + links.joinToString("\n") { "- $it" }
            }
        }
        val data = bytes ?: return "Failed: nothing downloaded."
        val route = try {
            Gpx.parse(java.io.ByteArrayInputStream(data))
        } catch (e: Exception) {
            return "Failed: the GPX wouldn't parse (${e.message ?: "malformed"})."
        }
        val name = route.name.ifBlank { "Downloaded route" }
        // Kept as a file, not just a picker entry: the queue expires, the
        // folder doesn't, and every walk search reads it from now on.
        val saved = runCatching { Downloads.save(ctx, name, data) }.getOrNull()
        val walk = RouteFinder.FoundWalk(
            name = name,
            source = "Web",
            lines = listOf(route.points),
            closestM = fix()?.let { Geom.closestApproach(it, route.points) } ?: 0.0,
            lengthM = Geom.length(route.points),
            uri = saved?.let { android.net.Uri.fromFile(it).toString() },
        )
        WalkPicks.append(ctx, walk)
        return "\u201C${walk.name}\u201D downloaded: ${km(walk.lengthM)}" +
            (fix()?.let { ", line ${km(walk.closestM)} from him" } ?: "") +
            ". Queued on the map picker and saved to his downloads — " +
            "\u201CWalks on this map\u201D in the GPX menu finds it again any time."
    }

    /**
     * The before-a-walk briefing, every number computed or fetched: length
     * and climb, a Naismith time estimate, the rain across the walk's own
     * window, and whether he is back before dark — with where the sun goes
     * down, since a sunset walked towards is worth planning for.
     */
    fun walkBrief(departInMinutes: Double): String {
        val r = route() ?: return "No route is loaded — import one or plan one, then ask again."
        val at = fix() ?: r.points.first()
        val (lat, lon) = Bng.toWgs84(at)
        val totalM = Geom.length(r.points)

        // Ascent from the terrain model; a network failure downgrades the
        // estimate honestly instead of inventing a flat route.
        val ascent = runCatching {
            var t = 0.0
            for (i in 1 until r.points.size) {
                t += hypot(r.points[i].e - r.points[i - 1].e, r.points[i].n - r.points[i - 1].n)
            }
            val step = maxOf(200.0, t / 90)
            val samples = sampleAlong(r.points, step)
            val lats = StringBuilder(); val lons = StringBuilder()
            samples.forEachIndexed { i, en ->
                val (la, lo) = Bng.toWgs84(en)
                if (i > 0) { lats.append(','); lons.append(',') }
                lats.append("%.5f".format(la)); lons.append("%.5f".format(lo))
            }
            val elev = JSONObject(
                Net.get("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"),
            ).getJSONArray("elevation")
            var up = 0.0
            var prev = elev.getDouble(0)
            for (i in 0 until elev.length()) {
                val e = elev.getDouble(i)
                if (e > prev) up += e - prev
                prev = e
            }
            up
        }.getOrNull()

        // Naismith's rule: 12 min per km plus a minute per 10 m of climb.
        val walkMins = (totalM / 1000 * 12 + (ascent ?: 0.0) / 10).roundToInt()
        val departAt = System.currentTimeMillis() + (departInMinutes * 60_000).toLong()
        val finishAt = departAt + walkMins * 60_000L
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)

        val sb = StringBuilder()
        sb.append("Route \"${r.name}\": ${km(totalM)}")
        sb.append(
            if (ascent != null) ", about ${ascent.roundToInt()} m of climb. "
            else " (no signal for the climb figure, so the estimate is flat-ground). ",
        )
        sb.append("Naismith's rule says roughly ${walkMins / 60} h ${walkMins % 60} min of walking — ")
        sb.append("an estimate for a steady walker, no stops. ")
        sb.append("Setting off at ${hhmm.format(java.util.Date(departAt))}, ")
        sb.append("that finishes around ${hhmm.format(java.util.Date(finishAt))}.\n")

        // Weather across the walk's own window, plus the day's light.
        runCatching {
            val json = JSONObject(
                Net.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(lat, lon) +
                        "&hourly=temperature_2m,precipitation_probability,precipitation," +
                        "wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                        "&daily=sunrise,sunset&forecast_days=2&wind_speed_unit=mph&timezone=auto",
                ),
            )
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val prob = hourly.getJSONArray("precipitation_probability")
            val rain = hourly.getJSONArray("precipitation")
            val temp = hourly.getJSONArray("temperature_2m")
            val wind = hourly.getJSONArray("wind_speed_10m")
            val windDir = hourly.optJSONArray("wind_direction_10m")
            val gusts = hourly.optJSONArray("wind_gusts_10m")
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.UK)
            var worstProb = 0; var totalRain = 0.0
            var tMin = Double.MAX_VALUE; var tMax = -Double.MAX_VALUE; var maxWind = 0.0
            var maxGust = 0.0
            var fromDeg = Double.NaN
            var covered = 0
            for (i in 0 until times.length()) {
                val h = iso.parse(times.getString(i))?.time ?: continue
                if (h + 3_600_000 < departAt || h > finishAt) continue
                covered++
                worstProb = maxOf(worstProb, prob.optInt(i))
                totalRain += rain.optDouble(i, 0.0)
                tMin = minOf(tMin, temp.getDouble(i)); tMax = maxOf(tMax, temp.getDouble(i))
                maxWind = maxOf(maxWind, wind.getDouble(i))
                gusts?.let { maxGust = maxOf(maxGust, it.optDouble(i, 0.0)) }
                // The direction at the start of the walk, not an average:
                // averaging 350° with 10° gives due south, which is the
                // opposite of the truth.
                if (fromDeg.isNaN()) fromDeg = windDir?.optDouble(i, Double.NaN) ?: Double.NaN
            }
            if (covered > 0) {
                sb.append(
                    if (totalRain >= 0.2 || worstProb >= 50) {
                        "Rain: up to $worstProb%% chance in the window, ~%.1f mm in total — expect to get wet. ".format(totalRain)
                    } else if (worstProb >= 20) {
                        "Rain: an outside chance ($worstProb% at worst), little or none expected. "
                    } else {
                        "Rain: none expected in the window. "
                    },
                )
                sb.append("%.0f–%.0f°C. ".format(tMin, tMax))
                // Wind gets its own sentence: he asked for it to count in the
                // planning, and a number without a direction cannot — whether
                // it is behind you or in your face is the whole question on an
                // exposed walk.
                sb.append(
                    if (fromDeg.isNaN()) "Wind up to %.0f mph".format(maxWind)
                    else "Wind from the ${Sun.compass(fromDeg)}, up to %.0f mph".format(maxWind),
                )
                if (maxGust > maxWind + 3) sb.append(", gusting %.0f".format(maxGust))
                sb.append(
                    when {
                        maxWind >= 38 -> " — that is enough to be dangerous on a ridge or a cliff path.\n"
                        maxWind >= 25 -> " — hard going into it, and worth keeping off exposed ground.\n"
                        maxWind >= 16 -> " — noticeable, not a problem.\n"
                        else -> ".\n"
                    },
                )
            }
        }.onFailure {
            sb.append("No connection for the forecast. ")
        }
        // Daylight is computed here, not fetched: "when does it get dark" is
        // exactly the question asked where there is no signal to look it up.
        sb.append(daylight(finishAt, lat, lon))
        return sb.toString()
    }

    /** Sunset, its direction, and the margin against finishing. */
    private fun daylight(finishAt: Long, lat: Double, lon: Double): String {
        val now = System.currentTimeMillis()
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
        val set = Sun.sunset(now, lat, lon)
            ?: return "The sun does not set here today."
        val az = Sun.positionAt(set, lat, lon).azimuth
        val sb = StringBuilder(
            "Sunset ${hhmm.format(java.util.Date(set))}, going down " +
                "${Sun.compass(az)} (${az.roundToInt()}°). ",
        )
        Sun.civilDusk(now, lat, lon)?.let {
            sb.append("Useful light until about ${hhmm.format(java.util.Date(it))}. ")
        }
        val margin = (set - finishAt) / 60_000
        sb.append(
            when {
                margin >= 60 -> "You'd be back with ${margin / 60} h ${margin % 60} min of daylight to spare."
                margin >= 0 -> "You'd be back only $margin min before sunset — tight; take a light."
                else -> "That finishes ${-margin} min AFTER sunset. Take a torch, or go earlier."
            },
        )
        return sb.toString()
    }

    fun restorePreviousRoute(): String {
        val r = RouteStore.restorePrevious(ctx) ?: return "There is no previous route banked."
        return "Restored \"${r.name}\". (The route it replaced is banked now, so this toggles.)"
    }

    // --- places and conditions ----------------------------------------------

    private fun geocode(name: String): Pair<Double, Double>? {
        val here = fix() ?: En(400_000.0, 300_000.0)
        val (lat, lon) = Bng.toWgs84(here)
        val json = Net.get(
            "https://nominatim.openstreetmap.org/search?format=json&limit=1" +
                "&viewbox=%.3f,%.3f,%.3f,%.3f".format(lon - 0.5, lat + 0.35, lon + 0.5, lat - 0.35) +
                "&q=" + Net.encode(name),
        )
        val arr = JSONArray(json)
        if (arr.length() == 0) return null
        val hit = arr.getJSONObject(0)
        return hit.getString("lat").toDouble() to hit.getString("lon").toDouble()
    }

    fun measureTo(place: String): String {
        val here = fix() ?: return "No GPS fix yet."
        val g = geocode(place) ?: return "Couldn't find \"$place\" on the map (Nominatim)."
        val there = Bng.fromWgs84(g.first, g.second)
        val (d, dir) = distanceAndBearing(here, there)
        return "\"$place\" is ${km(d)} away, roughly $dir of you, in a straight line " +
            "(walking distance will be longer)."
    }

    fun weather(): String {
        val at = fix() ?: route()?.let { it.points[it.points.size / 2] }
            ?: return "No GPS fix and no route — nowhere to forecast for."
        val (lat, lon) = Bng.toWgs84(at)
        val json = Net.get(
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(lat, lon) +
                "&hourly=temperature_2m,precipitation_probability,precipitation,wind_speed_10m" +
                "&forecast_hours=8&wind_speed_unit=mph&timezone=auto",
        )
        val hourly = JSONObject(json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temp = hourly.getJSONArray("temperature_2m")
        val prob = hourly.getJSONArray("precipitation_probability")
        val rain = hourly.getJSONArray("precipitation")
        val wind = hourly.getJSONArray("wind_speed_10m")
        val sb = StringBuilder("Next hours here (Open-Meteo):\n")
        for (i in 0 until minOf(8, times.length())) {
            sb.append(
                "- %s: %.0f°C, rain %d%% (%.1f mm), wind %.0f mph\n".format(
                    times.getString(i).substringAfter('T'),
                    temp.getDouble(i), prob.optInt(i), rain.getDouble(i), wind.getDouble(i),
                ),
            )
        }
        return sb.toString().trimEnd()
    }

    fun whereAmI(): String {
        val fixAgeMs = fixAgeMs()
        val here = fix() ?: return "No GPS fix yet."
        val (lat, lon) = Bng.toWgs84(here)
        val grid = Bng.gridRef(here) ?: "off the National Grid"
        val sb = StringBuilder("Grid reference $grid (lat %.5f, lon %.5f).".format(lat, lon))
        if (fixAgeMs > 60_000) sb.append(" Fix is ${fixAgeMs / 60_000} min old.")
        runCatching {
            val json = Net.get(
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=14" +
                    "&lat=%.5f&lon=%.5f".format(lat, lon),
            )
            val name = JSONObject(json).optString("display_name")
            if (name.isNotBlank()) sb.append(" Near: ${name.split(",").take(3).joinToString(",")}.")
        }
        Sun.sunset(System.currentTimeMillis(), lat, lon)?.let { set ->
            val az = Sun.positionAt(set, lat, lon).azimuth
            val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK)
                .format(java.util.Date(set))
            sb.append(" Sunset today: $at, ${Sun.compass(az)}.")
        }
        return sb.toString()
    }

    fun clearMarkers(): String {
        PoiStore.save(ctx, emptyList())
        return "Markers cleared."
    }
}
