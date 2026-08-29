package com.jollydoddger.waymark.shared

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream

/** The one imported route. Points are already in grid metres. */
data class Route(val name: String, val points: List<En>)

object Gpx {

    /**
     * Parse a GPX stream: every `trkpt` (in file order, across segments) or,
     * if the file has no track, every `rtept`. Coordinates convert to BNG at
     * the door — nothing downstream ever sees latitude again.
     */
    fun parse(input: InputStream): Route {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(input, null)

        val trk = ArrayList<En>()
        val rte = ArrayList<En>()
        var name = ""
        var inName = false
        var depthAtName = -1

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt", "rtept" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            (if (parser.name == "trkpt") trk else rte).add(Bng.fromWgs84(lat, lon))
                        }
                    }
                    // First <name> in the document (usually the track's).
                    "name" -> if (name.isEmpty()) { inName = true; depthAtName = parser.depth }
                }
                XmlPullParser.TEXT -> if (inName && parser.depth == depthAtName) {
                    name = parser.text.trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "name") inName = false
            }
            event = parser.next()
        }

        val pts = if (trk.isNotEmpty()) trk else rte
        require(pts.size >= 2) { "No track or route points found in that GPX" }
        return Route(name.ifEmpty { "Imported route" }, pts)
    }
}

/**
 * One current route, one JSON file, written atomically (a truncating write
 * interrupted mid-walk would eat the route he is standing on).
 */
object RouteStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "route.json")
    private fun previous(ctx: Context) = File(ctx.filesDir, "route.previous.json")

    fun toJson(route: Route): String {
        val es = JSONArray()
        val ns = JSONArray()
        route.points.forEach { es.put(it.e); ns.put(it.n) }
        return JSONObject().put("name", route.name).put("e", es).put("n", ns).toString()
    }

    fun fromJson(json: String): Route? = try {
        val o = JSONObject(json)
        val es = o.getJSONArray("e")
        val ns = o.getJSONArray("n")
        val pts = ArrayList<En>(es.length())
        for (i in 0 until es.length()) pts.add(En(es.getDouble(i), ns.getDouble(i)))
        if (pts.size < 2) null else Route(o.optString("name", "Route"), pts)
    } catch (e: Exception) {
        null
    }

    fun save(ctx: Context, route: Route) {
        // One level of undo: a planned route overwriting his imported GPX
        // must be reversible, or "plan me a walk" can silently destroy the
        // route he meant to do tomorrow.
        val f = file(ctx)
        if (f.exists()) f.copyTo(previous(ctx), overwrite = true)
        val tmp = File(ctx.filesDir, "route.json.tmp")
        tmp.writeText(toJson(route))
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }
    }

    fun load(ctx: Context): Route? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try { fromJson(f.readText()) } catch (e: Exception) { null }
    }

    /** Swap the current route for the one it replaced. Null if there is none. */
    fun restorePrevious(ctx: Context): Route? {
        val p = previous(ctx)
        if (!p.exists()) return null
        val restored = try { fromJson(p.readText()) } catch (e: Exception) { null } ?: return null
        save(ctx, restored) // save() banks the current one, so restore toggles
        return restored
    }
}
