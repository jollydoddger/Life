package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * What is on each walking site, remembered: walk name, roughly where it is,
 * and the page to get it from.
 *
 * The site guides say how to work a site. This says what is actually on one,
 * so "a walk near me" can be answered out of a file in a few milliseconds
 * instead of a web search and two page fetches every single time.
 *
 * Two things it is deliberately not.
 *
 * It is **not rendered whole, ever**. Seventeen sites at a hundred walks
 * each is a few thousand entries, and a tool that returned all of them
 * would put a phone book in the prompt. Everything here is a query. That is
 * the same lesson the site list just taught — the one that made walk_sites
 * filter by where he is — learnt once and applied before it bit.
 *
 * It is **not a crawl**. Entries arrive from pages the assistant was going
 * to read anyway: one area index when he asks about an area, and whatever
 * turns up in the course of finding him a walk. One contents page read once
 * and remembered is what a person does; walking a whole site to enumerate
 * it is not, and the per-host caps in WalkSites still apply either way.
 */
data class IndexedWalk(
    /** Bare host it came from, matching a SiteGuide. */
    val host: String,
    val name: String,
    /** Whatever the site called the area — "Anglesey", "Peak District". */
    val area: String,
    /** The walk's own page, for download_gpx. */
    val url: String,
    /** Where it is, when known. NaN when the index page did not say. */
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun located(): Boolean = !lat.isNaN() && !lon.isNaN()

    fun render(): String = buildString {
        append("- $name")
        if (area.isNotBlank()) append(" ($area)")
        append(" — $url")
    }
}

object WalkIndex {

    /**
     * Enough for every site he has at a decent depth, and small enough that
     * the whole file is still a quick read on a phone. Oldest go first.
     */
    private const val MAX = 4_000

    /** Past this an entry is worth re-reading: sites add walks. */
    const val STALE_DAYS = 180

    private fun file(c: Context) = File(c.filesDir, "walkindex.json")

    fun all(c: Context): List<IndexedWalk> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONObject(f.readText()).getJSONArray("walks")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                IndexedWalk(
                    host = o.optString("host"),
                    name = o.optString("name"),
                    area = o.optString("area"),
                    url = o.optString("url"),
                    lat = o.optDouble("lat", Double.NaN),
                    lon = o.optDouble("lon", Double.NaN),
                    addedAt = o.optLong("addedAt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** How many are held, and from how many sites — a one-line answer to
     *  "does the index know anything useful yet". */
    fun summary(c: Context): String {
        val all = all(c)
        if (all.isEmpty()) return "The walk index is empty."
        val sites = all.map { it.host }.distinct().size
        val located = all.count { it.located() }
        return "${all.size} walks indexed from $sites site${if (sites == 1) "" else "s"}, " +
            "$located of them with a position."
    }

    /**
     * Add what was just read off a page. Keyed on URL: re-reading an index
     * updates rather than duplicates, which is what makes re-indexing a
     * stale area safe to do at any time.
     */
    fun add(c: Context, walks: List<IndexedWalk>): Int {
        if (walks.isEmpty()) return 0
        val byUrl = LinkedHashMap<String, IndexedWalk>()
        for (w in all(c)) byUrl[w.url] = w
        var fresh = 0
        for (w in walks) {
            if (w.url.isBlank() || w.name.isBlank()) continue
            if (w.url !in byUrl) fresh++
            byUrl[w.url] = w
        }
        save(c, byUrl.values.toList().takeLast(MAX))
        return fresh
    }

    fun forget(c: Context, host: String): Int {
        val before = all(c)
        val after = before.filterNot { it.host.equals(host, true) }
        save(c, after)
        return before.size - after.size
    }

    /**
     * The walks worth offering, best first.
     *
     * A located entry near him beats a text match every time — "walks near
     * me" is a question about the ground, and a name that happens to
     * contain the word is not an answer to it. Unlocated entries still
     * match on words, because most index pages give a name and an area and
     * nothing else, and a named area is better than nothing.
     */
    fun search(
        c: Context,
        text: String,
        near: En?,
        withinKm: Double,
        limit: Int = 12,
    ): List<Pair<IndexedWalk, Double>> {
        val words = text.lowercase().split(' ', ',', '/')
            .map { it.trim() }.filter { it.length > 2 }
        val withinM = withinKm * 1000
        val out = ArrayList<Pair<IndexedWalk, Double>>()
        for (w in all(c)) {
            val hay = "${w.name} ${w.area}".lowercase()
            val wordsMatch = words.isEmpty() || words.all { it in hay }
            var distance = Double.MAX_VALUE
            if (near != null && w.located()) {
                val p = Bng.fromWgs84(w.lat, w.lon)
                distance = hypot(p.e - near.e, p.n - near.n)
            }
            val nearEnough = near == null || distance <= withinM
            // Located and close, or named right — never neither.
            if (w.located() && near != null) {
                if (nearEnough && wordsMatch) out.add(w to distance)
            } else if (wordsMatch && words.isNotEmpty()) {
                out.add(w to Double.MAX_VALUE)
            }
        }
        return out.sortedBy { it.second }.take(limit)
    }

    fun render(c: Context, hits: List<Pair<IndexedWalk, Double>>): String {
        if (hits.isEmpty()) return "Nothing in the index matches."
        return hits.joinToString("\n") { (w, d) ->
            val how = if (d == Double.MAX_VALUE) "" else " · %.1f km away".format(d / 1000)
            w.render() + how
        }
    }

    private fun save(c: Context, walks: List<IndexedWalk>) {
        val arr = JSONArray()
        for (w in walks) {
            arr.put(
                JSONObject()
                    .put("host", w.host)
                    .put("name", w.name)
                    .put("area", w.area)
                    .put("url", w.url)
                    .put("lat", if (w.lat.isNaN()) JSONObject.NULL else w.lat)
                    .put("lon", if (w.lon.isNaN()) JSONObject.NULL else w.lon)
                    .put("addedAt", w.addedAt),
            )
        }
        val f = file(c)
        val tmp = File(f.parentFile, "walkindex.json.tmp")
        tmp.writeText(JSONObject().put("walks", arr).toString())
        tmp.renameTo(f)
    }
}
