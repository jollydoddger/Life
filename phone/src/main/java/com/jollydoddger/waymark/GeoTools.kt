package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Poi
import com.jollydoddger.waymark.shared.PoiStore
import com.jollydoddger.waymark.shared.Route
import com.jollydoddger.waymark.shared.RouteStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
) {

    private companion object {
        /** Loops tried per plan. Each is one routing call on a free server. */
        const val CANDIDATES = 3

        /** Within this of a mapped path counts as walking on it. */
        const val NEAR_PATH_M = 25.0
    }

    private fun route(): Route? = RouteStore.load(ctx)

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
        val json = Net.post("https://overpass-api.de/api/interpreter", "data=" + Net.encode(query),
            "application/x-www-form-urlencoded")

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

    /** One routed candidate, with how green it turned out. */
    private data class Candidate(val points: List<En>, val metres: Double, val pathFraction: Double)

    /**
     * Plan a walk. Point-to-point when given places; otherwise a circular loop
     * that actually tries to be a country walk.
     *
     * "Countryside" cannot be asserted by the model and is not something the
     * router reports — OSRM returns geometry and distance, not OSM highway
     * tags. So it is measured here instead: one Overpass query fetches the
     * real footpaths, tracks and bridleways around the start, those paths are
     * used to *place* each candidate loop's waypoints, and each routed result
     * is then scored geometrically against the same path data. The percentage
     * in the reply is that measurement, not an impression.
     */
    fun planRoute(placeNames: List<String>, circularKm: Double): String {
        val here = fix() ?: return "No GPS fix yet — can't plan from where you are."

        if (placeNames.isNotEmpty()) {
            val waypoints = ArrayList<Pair<Double, Double>>()
            waypoints.add(Bng.toWgs84(here))
            for (name in placeNames) {
                val g = geocode(name) ?: return "Failed: couldn't find \"$name\" on the map (Nominatim)."
                waypoints.add(g)
            }
            if (circularKm > 0) waypoints.add(Bng.toWgs84(here))
            val routed = routeVia(waypoints)
                ?: return "The router found no walkable route between those points."
            return adopt(routed, "Planned walk", null)
        }

        if (circularKm <= 0) return "Failed: give either place names or a circular distance."

        val target = circularKm * 1000
        val radius = target / (2 * PI)
        progress("Looking up the footpaths round here…")
        val paths = pathSegments(here, radius * 1.6 + 500)

        // Candidates: loops in evenly spread directions, each hung off real
        // path nodes where there are any within reach of the ideal circle.
        val spin = Math.random() * 2 * PI
        val candidates = ArrayList<Candidate>()
        for (c in 0 until CANDIDATES) {
            val bearing0 = spin + c * 2 * PI / CANDIDATES
            val waypoints = ArrayList<Pair<Double, Double>>()
            waypoints.add(Bng.toWgs84(here))
            for (k in 0..2) {
                val b = bearing0 + k * 2 * PI / 3
                val ideal = En(here.e + radius * sin(b), here.n + radius * cos(b))
                waypoints.add(Bng.toWgs84(snapToPath(ideal, paths, radius * 0.45)))
            }
            waypoints.add(Bng.toWgs84(here))
            progress("Trying route ${c + 1} of $CANDIDATES…")
            val routed = routeVia(waypoints) ?: continue
            candidates.add(
                Candidate(routed.first, routed.second, pathFraction(routed.first, paths)),
            )
        }
        if (candidates.isEmpty()) {
            return "The foot router couldn't find a loop from here. It is a free shared " +
                "server, so this may also just be a busy moment — worth one retry."
        }

        // Greenest wins, with length error as the tie-breaker: a beautifully
        // green loop of the wrong length is not what was asked for.
        val best = candidates.maxByOrNull {
            it.pathFraction - 0.6 * kotlin.math.abs(it.metres - target) / target
        }!!
        val note = if (paths.isEmpty()) {
            "No footpaths or tracks are mapped near here, so this follows whatever is walkable."
        } else {
            "${(best.pathFraction * 100).roundToInt()}% of it runs on or beside mapped " +
                "footpaths, tracks or bridleways" +
                (if (candidates.size > 1) " — the greenest of ${candidates.size} loops tried." else ".")
        }
        return adopt(best.points to best.metres, "Planned ${km(best.metres)} circular", note)
    }

    /** Save a routed line as the app's route and say so honestly. */
    private fun adopt(routed: Pair<List<En>, Double>, name: String, note: String?): String {
        RouteStore.save(ctx, Route(name, routed.first)) // save() banks the old route first
        return "Route set: ${km(routed.second)}. " + (note?.plus(" ") ?: "") +
            "It follows paths mapped in OpenStreetMap (FOSSGIS routing) — usually right, " +
            "not gospel, so worth a glance against the OS map. The previous route is banked; " +
            "restore_previous_route brings it back."
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
     * Every mapped footpath, track, bridleway and the like near a point, as
     * straight segments in grid metres. One Overpass query, reused for both
     * placing the waypoints and scoring the results.
     */
    private fun pathSegments(centre: En, searchM: Double): List<Pair<En, En>> {
        val (lat, lon) = Bng.toWgs84(centre)
        // One literal with templates: a .format() spanning concatenated
        // literals binds only to the last one, which is a silent misbuild.
        val at = "%.5f,%.5f".format(lat, lon)
        val kinds = "footway|path|track|bridleway|cycleway|steps|pedestrian"
        val end = "${'$'}" // a bare $ before a quote is not worth risking
        val query = "[out:json][timeout:25];" +
            "way[\"highway\"~\"^($kinds)$end\"](around:${searchM.roundToInt()},$at);" +
            "out geom;"
        val json = runCatching {
            Net.post(
                "https://overpass-api.de/api/interpreter",
                "data=" + Net.encode(query),
                "application/x-www-form-urlencoded",
            )
        }.getOrNull() ?: return emptyList()

        val segs = ArrayList<Pair<En, En>>()
        val elements = runCatching { JSONObject(json).getJSONArray("elements") }.getOrNull()
            ?: return emptyList()
        for (i in 0 until elements.length()) {
            val geom = elements.getJSONObject(i).optJSONArray("geometry") ?: continue
            var prev: En? = null
            for (j in 0 until geom.length()) {
                val nd = geom.getJSONObject(j)
                val en = Bng.fromWgs84(nd.getDouble("lat"), nd.getDouble("lon"))
                prev?.let { segs.add(it to en) }
                prev = en
            }
        }
        return segs
    }

    /** The nearest point on a mapped path, if one is within reach. */
    private fun snapToPath(ideal: En, paths: List<Pair<En, En>>, withinM: Double): En {
        var best = ideal
        var bestD = withinM
        for ((a, b) in paths) {
            val p = nearestOnSegment(ideal, a, b)
            val d = hypot(p.e - ideal.e, p.n - ideal.n)
            if (d < bestD) {
                bestD = d
                best = p
            }
        }
        return best
    }

    /**
     * How much of a routed line runs on or beside a mapped path — sampled
     * every 100 m and counted against the real path geometry, so the figure
     * is measured rather than guessed.
     */
    private fun pathFraction(line: List<En>, paths: List<Pair<En, En>>): Double {
        if (paths.isEmpty() || line.size < 2) return 0.0
        val samples = sampleAlong(line, 100.0)
        if (samples.isEmpty()) return 0.0
        var on = 0
        for (s in samples) {
            for ((a, b) in paths) {
                val p = nearestOnSegment(s, a, b)
                if (hypot(p.e - s.e, p.n - s.n) <= NEAR_PATH_M) {
                    on++
                    break
                }
            }
        }
        return on.toDouble() / samples.size
    }

    private fun nearestOnSegment(p: En, a: En, b: En): En {
        val dx = b.e - a.e
        val dy = b.n - a.n
        val len2 = dx * dx + dy * dy
        if (len2 <= 0) return a
        val t = (((p.e - a.e) * dx + (p.n - a.n) * dy) / len2).coerceIn(0.0, 1.0)
        return En(a.e + t * dx, a.n + t * dy)
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
        runCatching {
            val json = Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(lat, lon) +
                    "&daily=sunset&forecast_days=1&timezone=auto",
            )
            val sunset = JSONObject(json).getJSONObject("daily").getJSONArray("sunset").getString(0)
            sb.append(" Sunset today: ${sunset.substringAfter('T')}.")
        }
        return sb.toString()
    }

    fun clearMarkers(): String {
        PoiStore.save(ctx, emptyList())
        return "Markers cleared."
    }
}
