package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En

/**
 * "Walks whose line comes within X of me" — the thing the route-app map pins
 * can't answer, because a pin marks where a walk starts, not where it goes.
 * Two sources, both legal and both his: OpenStreetMap's walking-route
 * relations (public data, queried by whether the *line* passes near the fix)
 * and his own GPX library folder. No scraping of anybody's route site.
 */
object RouteFinder {

    /**
     * The smallest the OSM geometry clip box is ever made. A national trail
     * is megabytes unclipped and the part 200 km away is not the part he
     * can walk today — but the floor used to be twenty kilometres whatever
     * was asked, so a "walks starting within five hundred metres" question
     * pulled a forty-kilometre square of the Pennine Way to answer it. The
     * box has to cover the radius searched, with room for a trail to be
     * routed along; it does not have to cover the next county.
     */
    private const val MIN_CLIP_M = 6_000.0

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
        // The downloads folder rides every search: a route he went to the
        // trouble of finding once must stay findable, network or none.
        val saved = runCatching { Downloads.walks(ctx, near) }.getOrDefault(emptyList())
            .filter { it.closestM <= radiusM }
        val walks = (osm + library + saved).sortedBy { it.closestM }.take(MAX_RESULTS)
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
        // The clip box must grow with the search radius: a fixed ±20 km box
        // under a 25 km radius clips a 22-km-away walk to no geometry at
        // all, and the closest-distance guard below then drops it silently.
        val clipM = maxOf(MIN_CLIP_M, radiusM + 5_000.0)
        val (south, west) = Bng.toWgs84(En(near.e - clipM, near.n - clipM))
        val (north, east) = Bng.toWgs84(En(near.e + clipM, near.n + clipM))
        // One literal with templates (a .format() over concatenated literals
        // binds only to the last), and ${'$'} for the regex end.
        val at = "%.5f,%.5f".format(lat, lon)
        val clip = "%.5f,%.5f,%.5f,%.5f".format(south, west, north, east)
        val end = "${'$'}"
        val query = "[out:json][timeout:30];" +
            "relation[\"route\"~\"^(hiking|foot|walking)$end\"]" +
            "(around:${radiusM.toInt()},$at);" +
            "out geom($clip) 40;"
        val out = ArrayList<FoundWalk>()
        // Streamed; see Overpass.kt. Forty relations sounds small until one
        // of them is a national trail with thirty thousand nodes in it.
        Overpass.forEach(query) { rel ->
            val name = rel.tags["name"]?.takeIf { it.isNotBlank() }
                ?: rel.tags["ref"]?.takeIf { it.isNotBlank() }
            // An unnamed relation is not offerable as "a walk".
            if (rel.type == "relation" && name != null) {
                // Member ways as separate polylines; clipping replaces
                // out-of-box nodes with nulls, which split a way here.
                val lines = ArrayList<List<En>>()
                for (member in rel.members) {
                    for (run in Overpass.runs(member)) {
                        val line = ArrayList<En>(run.size / 2)
                        var g = 0
                        while (g + 1 < run.size) {
                            line.add(Bng.fromWgs84(run[g], run[g + 1]))
                            g += 2
                        }
                        lines.add(line)
                    }
                }
                if (lines.isNotEmpty()) {
                    val closest = lines.minOf { Geom.closestApproach(near, it) }
                    // around: matched a part the clip cut off
                    if (closest <= radiusM) {
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
                }
            }
        }
        return out
    }
}
