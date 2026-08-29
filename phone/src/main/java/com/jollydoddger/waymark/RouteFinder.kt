package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONObject

/**
 * "Walks whose line comes within X of me" — the thing the route-app map pins
 * can't answer, because a pin marks where a walk starts, not where it goes.
 * Two sources, both legal and both his: OpenStreetMap's walking-route
 * relations (public data, queried by whether the *line* passes near the fix)
 * and his own GPX library folder. No scraping of anybody's route site.
 */
object RouteFinder {

    /** How far out the OSM geometry is clipped: a national trail is megabytes
     *  unclipped, and the part 200 km away is not the part he can walk today. */
    private const val CLIP_M = 20_000.0

    private const val MAX_RESULTS = 25

    data class FoundWalk(
        val name: String,
        /** "OSM" or "Library". */
        val source: String,
        /**
         * The walk's geometry as separate polylines. An OSM relation's member
         * ways come unordered and sometimes gappy, and pretending otherwise
         * draws lines that jump about; a library GPX is a single line.
         */
        val lines: List<List<En>>,
        /** Closest the line comes to the fix, in metres. */
        val closestM: Double,
        /** Length of the geometry held here — for an OSM trail, the part
         *  within ~20 km, not the whole named route. */
        val lengthM: Double,
        /** Library entries carry the document URI so adopting one can re-parse
         *  the full-resolution GPX rather than the decimated index line. */
        val uri: String? = null,
    ) {
        /** One line for the route store; gaps between OSM members included. */
        fun routePoints(): List<En> = lines.flatten()
    }

    data class Result(val walks: List<FoundWalk>, val note: String?)

    fun find(ctx: Context, near: En, radiusM: Double): Result {
        var note: String? = null
        val osm = runCatching { fromOsm(near, radiusM) }.getOrElse {
            note = "OpenStreetMap search failed (${it.message ?: it.javaClass.simpleName}) — " +
                "showing your library only."
            emptyList()
        }
        val library = Library.search(ctx, near, radiusM).map { (entry, d) ->
            FoundWalk(
                name = entry.name,
                source = "Library",
                lines = listOf(entry.points),
                closestM = d,
                lengthM = Geom.length(entry.points),
                uri = entry.uri,
            )
        }
        val walks = (osm + library).sortedBy { it.closestM }.take(MAX_RESULTS)
        return Result(walks, note)
    }

    /**
     * Every OSM walking-route relation whose line passes within [radiusM] of
     * [near]. One Overpass query; `(around:…)` does the within-a-radius test
     * server-side on the actual geometry, and `out geom(bbox)` clips what
     * comes back so a national trail stays a sane size.
     */
    private fun fromOsm(near: En, radiusM: Double): List<FoundWalk> {
        val (lat, lon) = Bng.toWgs84(near)
        val (south, west) = Bng.toWgs84(En(near.e - CLIP_M, near.n - CLIP_M))
        val (north, east) = Bng.toWgs84(En(near.e + CLIP_M, near.n + CLIP_M))
        // One literal with templates (a .format() over concatenated literals
        // binds only to the last), and ${'$'} for the regex end.
        val at = "%.5f,%.5f".format(lat, lon)
        val clip = "%.5f,%.5f,%.5f,%.5f".format(south, west, north, east)
        val end = "${'$'}"
        val query = "[out:json][timeout:30];" +
            "relation[\"route\"~\"^(hiking|foot|walking)$end\"]" +
            "(around:${radiusM.toInt()},$at);" +
            "out geom($clip) 40;"
        val json = Net.post(
            "https://overpass-api.de/api/interpreter",
            "data=" + Net.encode(query),
            "application/x-www-form-urlencoded",
        )

        val out = ArrayList<FoundWalk>()
        val elements = JSONObject(json).getJSONArray("elements")
        for (i in 0 until elements.length()) {
            val rel = elements.getJSONObject(i)
            if (rel.optString("type") != "relation") continue
            val tags = rel.optJSONObject("tags")
            val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("ref")?.takeIf { it.isNotBlank() }
                ?: continue // an unnamed relation is not offerable as "a walk"

            // Member ways as separate polylines; clipping replaces out-of-box
            // nodes with nulls, which split a way into pieces here.
            val lines = ArrayList<List<En>>()
            val members = rel.optJSONArray("members") ?: continue
            for (m in 0 until members.length()) {
                val member = members.getJSONObject(m)
                if (member.optString("type") != "way") continue
                val geom = member.optJSONArray("geometry") ?: continue
                var run = ArrayList<En>()
                for (g in 0 until geom.length()) {
                    val nd = geom.optJSONObject(g)
                    if (nd == null) {
                        if (run.size >= 2) lines.add(run)
                        run = ArrayList()
                        continue
                    }
                    run.add(Bng.fromWgs84(nd.getDouble("lat"), nd.getDouble("lon")))
                }
                if (run.size >= 2) lines.add(run)
            }
            if (lines.isEmpty()) continue

            val closest = lines.minOf { Geom.closestApproach(near, it) }
            if (closest > radiusM) continue // around: matched a part the clip cut off
            out.add(
                FoundWalk(
                    name = name,
                    source = "OSM",
                    lines = lines,
                    closestM = closest,
                    lengthM = lines.sumOf { Geom.length(it) },
                ),
            )
        }
        return out
    }
}
