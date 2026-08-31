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
    /**
     * Where in the country this site actually has walks, in words for the
     * model to read.
     */
    val covers: String = "UK",
    val note: String = "",
    /**
     * The same thing as arithmetic: rough lat/lon bounds of the site's
     * coverage, so a search near him can drop the ones that are no use.
     *
     * Load-bearing once the list stops being short. Three of the best sites
     * here cover Scotland, London and Yorkshire; from Anglesey they are a
     * wall of text in every prompt and a minute spent finding nothing.
     * Deliberately generous — this decides what gets *offered*, and an
     * over-tight box silently hides a site that would have had the walk.
     */
    val south: Double = 49.8,
    val west: Double = -8.7,
    val north: Double = 61.0,
    val east: Double = 1.9,
) {
    fun covers(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east

    fun render(): String = buildString {
        append("• $name ($host) — ${rule.short}\n")
        append("    covers: $covers\n")
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

    /**
     * Not a source of walks at all — a list of other people's walking
     * sites. Worth reading when he wants somewhere new to look, and worth
     * knowing about so a search does not spend a fetch on an index page
     * expecting a GPX to fall out of it.
     */
    DIRECTORY("a directory of other walking sites — no GPX of its own", 0),
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
            covers = "England and Wales, including Snowdonia and Anglesey",
            finding = "Area index pages at /<area>.html or /<area>.htm — /wales.html, " +
                "/snowdonia.htm, /peakdistrict.htm. Individual walks are /<area><N>.html " +
                "(/wales15.html is Newborough Beach and Llanddwyn Island, Anglesey). " +
                "Long-distance paths live under /ldp/<name>.html.",
            getting = "A plain .gpx link on the walk's own page — e.g. " +
                "/ldp/ridgeway/01/trackfile.gpx. Hand the walk page to download_gpx and " +
                "it finds the link itself.",
            rule = Rule.OPEN,
            note = "Both .html and .htm are in use on the same site; if one 404s, try " +
                "the other before giving up. Best first stop for anything near home.",
            south = 49.8, west = -6.5, north = 55.9, east = 1.9,
        ),
        SiteGuide(
            host = "walkingbritain.co.uk",
            name = "Walking Britain",
            covers = "England, Wales and Scotland; 130+ walks in Snowdonia alone",
            finding = "Area index at /<Area>-walks, walk finder map at /map-<Area>. " +
                "Each walk is /walk-<id>-description, /walk-<id>-map, /walk-<id>-gps.",
            getting = "The /walk-<id>-gps page is a click-through, not a file. " +
                "download_gpx will fail on it and hand him the link — which is correct. " +
                "Give him the page and let him tap it.",
            rule = Rule.GATED,
            note = "Tested on walk 1702 (Rhoscolyn Headland, near him): no plain .gpx " +
                "link. Do not try to guess the file URL behind the gate. Still worth " +
                "searching for the write-up, then handing him the link.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "hopelesswanderer.co.uk",
            name = "Hopeless Wanderer",
            covers = "Great Britain",
            finding = "Walks are blog posts — index at /blog/category/Mapped, everything " +
                "at /archive.",
            getting = "Each walk page offers GPX, KML, FIT and Google Maps downloads. " +
                "Hand the walk page to download_gpx.",
            rule = Rule.SELLS_BULK,
            note = "Bulk download of all their routes is a paid membership (/members-1). " +
                "Fetch the single walk he asked about and nothing more.",
            south = 49.8, west = -8.7, north = 59.0, east = 1.9,
        ),
        SiteGuide(
            host = "walkingclub.org.uk",
            name = "Saturday Walkers Club",
            covers = "London and South East England only — a trip, never a walk from home",
            finding = "400+ walks, map of them at /walks/map.html. Walks are " +
                "/walk/<slug>/ and long paths /long-distance-path/<slug>/.",
            getting = "Every walk has /walk/<slug>/download-GPX-KML.html — the most " +
                "regular structure of any site here. Hand that page to download_gpx.",
            rule = Rule.OPEN,
            note = "Public-transport-friendly walks. Material is free for " +
                "non-commercial use only.",
            south = 50.5, west = -1.9, north = 52.4, east = 1.6,
        ),
        SiteGuide(
            host = "haroldstreet.org.uk",
            name = "Harold Street",
            covers = "UK-wide — Lake District, Yorkshire, Wales, Scotland",
            finding = "Paged listing at /routes/?page=<N>. Each entry gives start point, " +
                "distance, ascent and time.",
            getting = "Free GPX download per route from its listing page.",
            rule = Rule.OPEN,
            note = "Community-shared: many files are raw GPS tracks recorded in the " +
                "field rather than tidied routes, so expect wobble and the odd detour.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "happyhiker.co.uk",
            name = "Happy Hiker",
            covers = "Mostly Yorkshire and the Lake District",
            finding = "400+ routes, found through the Google map on the Hiking Pages — " +
                "start points, parking and links to each route.",
            getting = "Each route page has a free GPX download alongside a printable PDF.",
            rule = Rule.OPEN,
            note = "Start points include parking, which pairs well with the picker's " +
                "Parking button.",
            south = 52.9, west = -3.8, north = 55.1, east = -0.2,
        ),
        SiteGuide(
            host = "walkhighlands.co.uk",
            name = "Walkhighlands",
            covers = "SCOTLAND ONLY — 2,100+ routes. Nothing here for a walk near home",
            finding = "Search at /walk-search.php; walks are .shtml pages; long paths " +
                "at /long-distance-routes.shtml.",
            getting = "GPX at /download-ge.php?w=<id>, linked from the walk page.",
            rule = Rule.OPEN,
            note = "Their own terms: the file data is theirs and is offered for PERSONAL " +
                "USE ONLY and must not be republished. Downloading one for him to walk " +
                "is exactly that; passing it on anywhere is not.",
            south = 54.5, west = -8.7, north = 61.0, east = -0.6,
        ),
        SiteGuide(
            host = "gps-routes.co.uk",
            name = "GPS Routes",
            covers = "UK-wide, walking and cycling",
            finding = "Not yet mapped out properly — search the site for the area and " +
                "read the index page you land on.",
            getting = "Free GPX per route from the route's own page.",
            rule = Rule.OPEN,
            note = "This recipe is thinner than the others because its structure has " +
                "not been checked. First time you use it, look at how the URLs are " +
                "actually laid out and call add_walk_site to write a proper one.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "walkingworld.com",
            name = "Walkingworld",
            covers = "UK-wide",
            finding = "Walks are browsable, but the GPX is not.",
            getting = "GPX export is a PAID subscription feature (about £18 a year) " +
                "behind a login. The app cannot fetch it and should not try. If he " +
                "subscribes, he downloads in his browser and shares the file to " +
                "Waymark — one tap, and it imports straight onto the map.",
            rule = Rule.GATED,
            note = "Free tier is one walk at a time, kept for seven days, and no GPX. " +
                "Tell him the cost rather than letting him hit the paywall himself.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "gpstraining.co.uk",
            name = "GPS Training — UK GPX Walks Directory",
            covers = "n/a — it lists other people's sites",
            finding = "/pages/uk-gpx-walks-directory is a directory of free GPX walking " +
                "sites across the UK.",
            getting = "Nothing to download here. Read it to find NEW sites, check how " +
                "one is laid out, then add_walk_site so the next search knows it.",
            rule = Rule.DIRECTORY,
            note = "The place to go when the sites in this list do not cover an area.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "ldwa.org.uk",
            name = "Long Distance Walkers Association",
            covers = "UK-wide — the national register of long-distance paths",
            finding = "The LDP database: /ldp/members/show_path.php?path_name=<Path+Name>. " +
                "It has the Isle of Anglesey Coastal Path, which is on his doorstep.",
            getting = "GPX per path from the path's own page.",
            rule = Rule.OPEN,
            note = "Their terms: downloads are for personal or LDWA use only. Paths are " +
                "long — a whole national trail, not an afternoon — so expect to want a " +
                "section rather than the file whole.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "slowways.org",
            name = "Slow Ways",
            covers = "Great Britain — 8,000+ routes between towns and villages",
            finding = "Routes connect one settlement to the next, so search by the two " +
                "place names rather than by an area.",
            getting = "Free GPX per route from the route page.",
            rule = Rule.OPEN,
            note = "Free for personal, non-commercial use; the data is Crown Copyright / " +
                "Ordnance Survey. Being settlement-to-settlement they are mostly linear " +
                "and often lanes — good for getting somewhere, less so for a circular.",
            south = 49.8, west = -8.7, north = 59.0, east = 1.9,
        ),
        SiteGuide(
            host = "gpxwalks.co.uk",
            name = "GPX Walks",
            covers = "UK-wide",
            finding = "A browse-and-download index of GPX walks; structure not checked yet.",
            getting = "Free GPX per walk.",
            rule = Rule.OPEN,
            note = "Thin recipe — look at how its URLs are laid out on first use and " +
                "call add_walk_site to replace this with a real one.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
        SiteGuide(
            host = "nationaltrail.co.uk",
            name = "National Trails",
            covers = "England and Wales — the official national trails",
            finding = "One site per trail, with sections and route information.",
            getting = "Route files per section where offered; structure not checked yet.",
            rule = Rule.OPEN,
            note = "Official source, so worth trusting over a copy elsewhere. Thin " +
                "recipe until it has actually been used once.",
            south = 49.8, west = -6.5, north = 55.9, east = 1.9,
        ),
        SiteGuide(
            host = "naturalresources.wales",
            name = "Natural Resources Wales",
            covers = "Wales — official trails and local circular routes",
            finding = "National Trails and the Wales Coast Path pages, plus their Outdoor " +
                "Wales online mapping.",
            getting = "Route information and files where published; structure not checked yet.",
            rule = Rule.OPEN,
            note = "The official Welsh source and closest to home of anything here. " +
                "Worth checking properly next time he asks for a local walk.",
            south = 51.2, west = -5.5, north = 53.6, east = -2.5,
        ),
        SiteGuide(
            host = "britishpilgrimage.org",
            name = "British Pilgrimage Trust",
            covers = "England and Wales",
            finding = "Free GPX routes listed at /download-gpx.",
            getting = "Free GPX per route from that page.",
            rule = Rule.OPEN,
            note = "Pilgrimage routes — long, linear and church-to-church. A different " +
                "kind of walk from a circular afternoon, and worth knowing exists.",
            south = 49.8, west = -6.5, north = 55.9, east = 1.9,
        ),
        SiteGuide(
            host = "go4awalk.com",
            name = "Go4aWalk",
            covers = "UK-wide, 6,000+ routes",
            finding = "Browsable, but downloads need an account.",
            getting = "Downloads are PAID — a credits system, around 12.5p a walk, behind " +
                "a login. About fifty free samples exist but sit behind a newsletter " +
                "signup. The app cannot fetch any of it.",
            rule = Rule.GATED,
            note = "Tell him the cost rather than letting him find the login himself.",
            south = 49.8, west = -8.7, north = 61.0, east = 1.9,
        ),
    )

    private fun file(c: Context) = File(c.filesDir, "walksites.json")

    /**
     * Bumped whenever sites are added to [SEED]. A stored list from an
     * older version has the new ones merged in on next read.
     *
     * Without this, seeding-once has a second failure to go with the one it
     * fixes: his file exists, so a release that adds seven sites reaches
     * him with none of them, silently, for ever. The tombstone list is what
     * lets both hold at once — new sites arrive, deleted ones stay deleted.
     */
    private const val SEED_VERSION = 2

    private fun read(c: Context): Triple<List<SiteGuide>, Set<String>, Int> {
        val f = file(c)
        if (!f.exists()) return Triple(emptyList(), emptySet(), 0)
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("sites")
            val sites = (0 until arr.length()).map { i ->
                val g = arr.getJSONObject(i)
                SiteGuide(
                    host = g.getString("host"),
                    name = g.optString("name", g.getString("host")),
                    finding = g.optString("finding"),
                    getting = g.optString("getting"),
                    rule = runCatching { Rule.valueOf(g.optString("rule")) }
                        .getOrDefault(Rule.OPEN),
                    covers = g.optString("covers").ifBlank { "UK" },
                    note = g.optString("note"),
                    south = g.optDouble("south", 49.8),
                    west = g.optDouble("west", -8.7),
                    north = g.optDouble("north", 61.0),
                    east = g.optDouble("east", 1.9),
                )
            }
            val goneArr = o.optJSONArray("removed")
            val gone = (0 until (goneArr?.length() ?: 0))
                .map { goneArr!!.getString(it).lowercase() }.toSet()
            Triple(sites, gone, o.optInt("seedVersion"))
        } catch (e: Exception) {
            // A broken list is not worth losing a walk over; start again.
            Triple(emptyList(), emptySet(), 0)
        }
    }

    /**
     * His site list: what he has, plus any seed sites newer than the last
     * version he saw, minus anything he has deleted.
     */
    fun guides(c: Context): List<SiteGuide> {
        val (stored, gone, version) = read(c)
        if (!file(c).exists()) {
            save(c, SEED, gone)
            return SEED
        }
        if (version >= SEED_VERSION) return stored
        val have = stored.map { it.host.lowercase() }.toSet()
        val fresh = SEED.filter { it.host.lowercase() !in have && it.host.lowercase() !in gone }
        val merged = stored + fresh
        save(c, merged, gone)
        return merged
    }

    fun add(c: Context, guide: SiteGuide) {
        val (stored, gone, _) = read(c)
        val kept = stored.filterNot { it.host.equals(guide.host, true) }
        // Adding a site he once deleted un-deletes it: he has just asked
        // for it by name, which outranks a tombstone.
        save(c, kept + guide, gone - guide.host.lowercase())
    }

    fun remove(c: Context, host: String): Boolean {
        val before = guides(c)
        val after = before.filterNot { it.host.equals(host, true) }
        if (after.size == before.size) return false
        val (_, gone, _) = read(c)
        // Remembered as deleted, or the next seed bump hands it straight back.
        save(c, after, gone + host.lowercase())
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

    /**
     * The sites worth trying at a place. Directories come too — they are
     * where he looks when nothing here covers the ground.
     */
    fun covering(c: Context, lat: Double, lon: Double): List<SiteGuide> =
        guides(c).filter { it.rule == Rule.DIRECTORY || it.covers(lat, lon) }

    private fun save(c: Context, sites: List<SiteGuide>, removed: Set<String>) {
        val arr = JSONArray()
        for (g in sites) {
            arr.put(
                JSONObject()
                    .put("host", g.host)
                    .put("name", g.name)
                    .put("finding", g.finding)
                    .put("getting", g.getting)
                    .put("rule", g.rule.name)
                    .put("covers", g.covers)
                    .put("note", g.note)
                    .put("south", g.south)
                    .put("west", g.west)
                    .put("north", g.north)
                    .put("east", g.east),
            )
        }
        val body = JSONObject()
            .put("seedVersion", SEED_VERSION)
            .put("sites", arr)
            .put("removed", JSONArray().also { j -> removed.forEach { j.put(it) } })
            .toString()
        val f = file(c)
        val tmp = File(f.parentFile, "walksites.json.tmp")
        tmp.writeText(body)
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
            Rule.DIRECTORY ->
                "${guide.name} is a directory of other walking sites, not a source of " +
                    "walks — there is no GPX here to fetch. Read it for somewhere new " +
                    "to look, then add that site instead."
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

    /**
     * The list, narrowed to where he actually is unless [everywhere].
     *
     * Narrowing is not tidiness. Seventeen recipes is several thousand
     * tokens on every call that asks, most of it about Scotland and the
     * Home Counties while he is stood in Anglesey — and a model given a
     * Scottish site alongside a Welsh one will sometimes pick the Scottish
     * one. Fewer, right ones beat all of them.
     */
    fun render(c: Context, at: Pair<Double, Double>?, everywhere: Boolean = false): String {
        val all = guides(c)
        if (all.isEmpty()) {
            return "No walking sites are set up yet. add_walk_site adds one."
        }
        if (everywhere || at == null) {
            val why = if (at == null) " (no GPS fix, so nothing could be narrowed)" else ""
            return "All ${all.size} walking sites he has$why:\n" +
                all.joinToString("") { it.render() }
        }
        val (lat, lon) = at
        val near = covering(c, lat, lon)
        if (near.isEmpty()) {
            return "None of his ${all.size} sites covers where he is. Ask walk_sites " +
                "again with all=true to see the lot, or use the directory site to find " +
                "one that does and add_walk_site it."
        }
        val hidden = all.size - near.size
        val tail = if (hidden > 0) {
            "\n$hidden more are set up but have no walks round here — ask with " +
                "all=true if he wants them anyway."
        } else {
            ""
        }
        return "Walking sites with walks where he is, and how each one works:\n" +
            near.joinToString("") { it.render() } + tail
    }
}
