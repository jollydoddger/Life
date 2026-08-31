package com.jollydoddger.waymark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How to work a walking website: one short recipe per site, read by the
 * assistant rather than compiled into a parser.
 *
 * His idea, and the right shape for the problem. A generic crawler has to
 * *guess* at every site's layout and gets it wrong in a different way each
 * time — Walking Englishman alone mixes `.html` and `.htm` in the same
 * index. A recipe just says where things are, and the model does the
 * navigating with the `download_gpx` it already has. Nothing here parses
 * HTML, so nothing here breaks when a site is restyled.
 *
 * The part that is **not** advice is [Rule]. Sites differ in what they are
 * offering for free, and that difference is theirs to make, not ours to
 * average over: one serves plain files, one puts a consent step in front of
 * the download, one sells bulk access as a membership. Recording that per
 * site is the whole reason this is a curated list rather than a spider.
 */
data class SiteGuide(
    /** Bare host, no scheme, no www — matched as a suffix. */
    val host: String,
    val name: String,
    /** How to find walks for an area on this site. */
    val finding: String,
    /** How to get from a walk page to the GPX file. */
    val getting: String,
    val rule: Rule,
    val note: String = "",
) {
    fun render(): String = buildString {
        append("• $name ($host) — ${rule.short}\n")
        append("    finding: $finding\n")
        append("    gpx: $getting\n")
        if (note.isNotBlank()) append("    note: $note\n")
    }
}

/**
 * What this site permits, as data rather than as a judgement made afresh
 * every time somebody asks.
 */
enum class Rule(val short: String, val perSession: Int) {
    /**
     * Plain .gpx links, free, no gate and nothing sold on top. Fetch what
     * he asks for. The cap is politeness, not suspicion: a personal app
     * has no business pulling dozens of files off a free site in one go.
     */
    OPEN("free, plain .gpx links", 8),

    /**
     * The file sits behind a click-through agreement or a script. The site
     * has deliberately put a person's tap in front of that download, so
     * hand him the page link and let him tap it. Never go hunting for the
     * underlying file URL to get round the gate — that is the one thing
     * the gate exists to prevent.
     */
    GATED("download is gated — hand over the link", 0),

    /**
     * The site sells bulk download as a paid membership. One walk at a
     * time, only ever the one he actually asked for, and never enumerate
     * an area — doing that would be rebuilding the thing they charge for
     * and giving it away, which is not ours to do with somebody else's
     * work.
     */
    SELLS_BULK("free per walk; bulk is a paid membership — one at a time", 3),
}

object WalkSites {

    /**
     * The three he has sent so far, checked against their real structure
     * rather than assumed. He can add more through the assistant.
     */
    private val SEED = listOf(
        SiteGuide(
            host = "walkingenglishman.com",
            name = "Walking Englishman",
            finding = "Area index pages at /<area>.html or /<area>.htm — /wales.html, " +
                "/snowdonia.htm, /peakdistrict.htm. Individual walks are /<area><N>.html " +
                "(/wales15.html is Newborough Beach and Llanddwyn Island, Anglesey). " +
                "Long-distance paths live under /ldp/<name>.html.",
            getting = "A plain .gpx link on the walk's own page — e.g. " +
                "/ldp/ridgeway/01/trackfile.gpx. Hand the walk page to download_gpx and " +
                "it finds the link itself.",
            rule = Rule.OPEN,
            note = "Both .html and .htm are in use on the same site; if one 404s, try " +
                "the other before giving up.",
        ),
        SiteGuide(
            host = "walkingbritain.co.uk",
            name = "Walking Britain",
            finding = "Area index at /<Area>-walks, walk finder map at /map-<Area>. " +
                "Each walk is /walk-<id>-description, /walk-<id>-map, /walk-<id>-gps.",
            getting = "The /walk-<id>-gps page is a click-through, not a file. " +
                "download_gpx will fail on it and hand him the link — which is correct. " +
                "Give him the page and let him tap it.",
            rule = Rule.GATED,
            note = "Tested on walk 1702 (Rhoscolyn Headland): no plain .gpx link. Do not " +
                "try to guess the file URL behind the gate.",
        ),
        SiteGuide(
            host = "hopelesswanderer.co.uk",
            name = "Hopeless Wanderer",
            finding = "Walks are blog posts — index at /blog/category/Mapped, everything " +
                "at /archive.",
            getting = "Each walk page offers GPX, KML, FIT and Google Maps downloads. " +
                "Hand the walk page to download_gpx.",
            rule = Rule.SELLS_BULK,
            note = "Bulk download of all their routes is a paid membership (/members-1). " +
                "Fetch the single walk he asked about and nothing more.",
        ),
    )

    private fun file(c: Context) = File(c.filesDir, "walksites.json")

    /**
     * Seeded once and recorded as seeded — never "the list is empty, so
     * hand the defaults back". That version returns every site he deleted
     * on the next launch, which makes deleting one impossible and is a
     * mistake already paid for in the other app.
     */
    fun guides(c: Context): List<SiteGuide> {
        val f = file(c)
        if (!f.exists()) {
            save(c, SEED)
            return SEED
        }
        return try {
            val arr = JSONObject(f.readText()).getJSONArray("sites")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SiteGuide(
                    host = o.getString("host"),
                    name = o.optString("name", o.getString("host")),
                    finding = o.optString("finding"),
                    getting = o.optString("getting"),
                    rule = runCatching { Rule.valueOf(o.optString("rule")) }
                        .getOrDefault(Rule.OPEN),
                    note = o.optString("note"),
                )
            }
        } catch (e: Exception) {
            // A broken list is not worth losing a walk over.
            emptyList()
        }
    }

    fun add(c: Context, guide: SiteGuide) {
        val kept = guides(c).filterNot { it.host.equals(guide.host, true) }
        save(c, kept + guide)
    }

    fun remove(c: Context, host: String): Boolean {
        val before = guides(c)
        val after = before.filterNot { it.host.equals(host, true) }
        if (after.size == before.size) return false
        save(c, after)
        return true
    }

    /** The guide covering a URL, if he has one for that host. */
    fun forUrl(c: Context, url: String): SiteGuide? {
        val low = url.lowercase()
        return guides(c).firstOrNull { g ->
            val h = g.host.lowercase().removePrefix("www.")
            low.contains("://$h") || low.contains("://www.$h") || low.contains(".$h")
        }
    }

    private fun save(c: Context, sites: List<SiteGuide>) {
        val arr = JSONArray()
        for (g in sites) {
            arr.put(
                JSONObject()
                    .put("host", g.host)
                    .put("name", g.name)
                    .put("finding", g.finding)
                    .put("getting", g.getting)
                    .put("rule", g.rule.name)
                    .put("note", g.note),
            )
        }
        val f = file(c)
        val tmp = File(f.parentFile, "walksites.json.tmp")
        tmp.writeText(JSONObject().put("sites", arr).toString())
        tmp.renameTo(f)
    }

    // --- the volume rule, in code rather than in a paragraph --------------
    //
    // The recipes above are advice to a model and it will usually follow
    // them. "One at a time, never enumerate an area" is the one that must
    // hold even when it doesn't — it is somebody else's paid feature — so
    // it is counted here instead. Per process: a burst is what matters, and
    // a count that survived restarts would eventually refuse a walk he
    // genuinely wanted months later.

    private val fetches = HashMap<String, Int>()

    @Synchronized
    fun mayFetch(c: Context, url: String): String? {
        val guide = forUrl(c, url) ?: return null
        val used = fetches[guide.host.lowercase()] ?: 0
        if (used < guide.rule.perSession) return null
        return when (guide.rule) {
            Rule.GATED ->
                "${guide.name} puts a click-through in front of its downloads, so this " +
                    "has to be his tap, not ours. Give him the page link."
            Rule.SELLS_BULK ->
                "That is ${guide.rule.perSession} walks from ${guide.name} already this " +
                    "session. Bulk access to that site is a membership they sell, and " +
                    "working round it a walk at a time is the same thing done slowly."
            Rule.OPEN ->
                "That is ${guide.rule.perSession} files from ${guide.name} in one go — " +
                    "enough off a free site for one sitting."
        }
    }

    @Synchronized
    fun noteFetch(c: Context, url: String) {
        val guide = forUrl(c, url) ?: return
        val key = guide.host.lowercase()
        fetches[key] = (fetches[key] ?: 0) + 1
    }

    /** Testing seam: the per-process counters, cleared. */
    @Synchronized
    fun forgetFetches() = fetches.clear()

    fun render(c: Context): String {
        val all = guides(c)
        if (all.isEmpty()) {
            return "No walking sites are set up yet. add_walk_site adds one."
        }
        return "Walking sites he has set up, and how each one works:\n" +
            all.joinToString("") { it.render() }
    }
}
