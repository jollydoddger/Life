package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Route
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * The route's elevation profile, fetched once and kept.
 *
 * Heights come from Open-Meteo's terrain model (~90 m grid) — the same
 * source the route profile and walk brief already use — sampled every
 * couple of hundred metres along the line. Cached against a fingerprint of
 * the route, because the profile is asked for on every tapped point and an
 * API call per tap would be both slow and rude; a new route simply misses
 * and refetches.
 */
class Heights(val alongs: DoubleArray, val heights: DoubleArray)

object RouteHeights {

    private const val MAX_SAMPLES = 90 // Open-Meteo's batch limit is 100

    private fun file(c: Context) = File(c.filesDir, "heights.json")

    /** Something stable enough to notice the route changing under the cache. */
    fun fingerprint(r: Route): String {
        if (r.points.isEmpty()) return "${r.name}/empty"
        return "${r.name}/${r.points.size}/${r.points.first().e.toInt()}/${r.points.last().n.toInt()}"
    }

    fun cached(c: Context, r: Route): Heights? {
        val f = file(c)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (o.optString("route") != fingerprint(r)) return null
            val a = o.getJSONArray("alongs")
            val h = o.getJSONArray("heights")
            Heights(
                DoubleArray(a.length()) { a.getDouble(it) },
                DoubleArray(h.length()) { h.getDouble(it) },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Fetch and cache the profile. Network; call off the main thread. */
    fun fetch(c: Context, r: Route): Heights {
        val total = Eta.cumulative(r.points).last()
        val step = maxOf(150.0, total / (MAX_SAMPLES - 1))
        val alongs = ArrayList<Double>()
        val samples = ArrayList<En>()
        var next = 0.0
        var along = 0.0
        alongs.add(0.0); samples.add(r.points.first())
        for (i in 1 until r.points.size) {
            val a = r.points[i - 1]
            val b = r.points[i]
            val seg = hypot(b.e - a.e, b.n - a.n)
            while (seg > 0 && next + step <= along + seg) {
                next += step
                val t = (next - along) / seg
                alongs.add(next)
                samples.add(En(a.e + t * (b.e - a.e), a.n + t * (b.n - a.n)))
            }
            along += seg
        }
        alongs.add(total); samples.add(r.points.last())

        val lats = StringBuilder()
        val lons = StringBuilder()
        samples.forEachIndexed { i, en ->
            val (lat, lon) = Bng.toWgs84(en)
            if (i > 0) { lats.append(','); lons.append(',') }
            lats.append("%.5f".format(java.util.Locale.UK, lat))
            lons.append("%.5f".format(java.util.Locale.UK, lon))
        }
        val json = Net.get("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons")
        val elev = JSONObject(json).getJSONArray("elevation")
        val heights = DoubleArray(minOf(elev.length(), alongs.size)) { elev.getDouble(it) }
        val out = Heights(alongs.toDoubleArray().copyOf(heights.size), heights)

        val body = JSONObject()
            .put("route", fingerprint(r))
            .put("alongs", JSONArray().also { arr -> out.alongs.forEach { arr.put(it) } })
            .put("heights", JSONArray().also { arr -> out.heights.forEach { arr.put(it) } })
            .toString()
        val f = file(c)
        val tmp = File(f.parentFile, "heights.json.tmp")
        tmp.writeText(body)
        tmp.renameTo(f)
        return out
    }
}
