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
) {

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

    /**
     * A route from the FOSSGIS foot router — real OSM-mapped paths, never a
     * line the model drew. Waypoints are lat,lon pairs; a circular request
     * turns into three deterministic waypoints on a circle whose circumference
     * matches the asked-for length, so nothing about the shape is invented
     * either.
     */
    fun planRoute(placeNames: List<String>, circularKm: Double): String {
        val here = fix() ?: return "No GPS fix yet — can't plan from where you are."
        val waypoints = ArrayList<Pair<Double, Double>>()
        waypoints.add(Bng.toWgs84(here))

        for (name in placeNames) {
            val g = geocode(name) ?: return "Failed: couldn't find \"$name\" on the map (Nominatim)."
            waypoints.add(g)
        }
        if (circularKm > 0) {
            // Circumference ≈ asked-for distance → radius; three points, random
            // starting bearing so repeated asks explore different directions.
            val radius = circularKm * 1000 / (2 * PI)
            val start = Math.random() * 2 * PI
            for (k in 0..2) {
                val b = start + k * 2 * PI / 3
                waypoints.add(Bng.toWgs84(En(here.e + radius * sin(b), here.n + radius * cos(b))))
            }
            waypoints.add(Bng.toWgs84(here)) // and home again
        }
        if (waypoints.size < 2) return "Failed: give either place names or a circular distance."

        val coords = waypoints.joinToString(";") { (lat, lon) -> "%.6f,%.6f".format(lon, lat) }
        val json = Net.get(
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords" +
                "?overview=full&geometries=geojson&steps=false",
        )
        val routes = JSONObject(json).optJSONArray("routes")
        if (routes == null || routes.length() == 0) return "The router found no walkable route between those points."
        val best = routes.getJSONObject(0)
        val line = best.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = ArrayList<En>(line.length())
        for (i in 0 until line.length()) {
            val c = line.getJSONArray(i)
            pts.add(Bng.fromWgs84(c.getDouble(1), c.getDouble(0)))
        }
        if (pts.size < 2) return "The router returned an empty line."
        val dist = best.getDouble("distance")

        val name = if (circularKm > 0) "Planned ${km(dist)} circular" else "Planned walk"
        RouteStore.save(ctx, Route(name, pts)) // save() banks the old route first
        return "Route set: ${km(dist)}, following paths mapped in OpenStreetMap (FOSSGIS routing). " +
            "The previous route is banked — restore_previous_route brings it back. " +
            "OSM paths are usually right but not gospel: worth a glance against the OS map."
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
