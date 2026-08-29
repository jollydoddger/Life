package com.jollydoddger.waymark

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The walks he has actually done: each finished recording, saved with a name,
 * his own note, when, how far and how long. One JSON file per walk in
 * `filesDir/walks/`, atomic like every other store — the archive of walks is
 * exactly the thing a truncating write must never eat.
 */
object Walks {

    data class SavedWalk(
        val id: String,
        val name: String,
        val notes: String,
        /** Where it started: a grid reference, plus a place name when one
         *  could be looked up. Offline saves still save. */
        val place: String,
        val startedAt: Long,
        val endedAt: Long,
        val distanceM: Double,
        val points: List<En>,
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "walks").apply { mkdirs() }

    fun save(ctx: Context, walk: SavedWalk) {
        val es = JSONArray()
        val ns = JSONArray()
        walk.points.forEach { es.put(it.e); ns.put(it.n) }
        val json = JSONObject()
            .put("id", walk.id).put("name", walk.name).put("notes", walk.notes)
            .put("place", walk.place)
            .put("startedAt", walk.startedAt).put("endedAt", walk.endedAt)
            .put("distanceM", walk.distanceM)
            .put("e", es).put("n", ns)
            .toString()
        val f = File(dir(ctx), "${walk.id}.json")
        val tmp = File(dir(ctx), "${walk.id}.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** Every saved walk, newest first. */
    fun list(ctx: Context): List<SavedWalk> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(f.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    private fun fromJson(json: String): SavedWalk {
        val o = JSONObject(json)
        val es = o.getJSONArray("e")
        val ns = o.getJSONArray("n")
        val pts = ArrayList<En>(es.length())
        for (i in 0 until es.length()) pts.add(En(es.getDouble(i), ns.getDouble(i)))
        return SavedWalk(
            id = o.getString("id"),
            name = o.getString("name"),
            notes = o.optString("notes"),
            place = o.optString("place"),
            startedAt = o.getLong("startedAt"),
            endedAt = o.getLong("endedAt"),
            distanceM = o.getDouble("distanceM"),
            points = pts,
        )
    }

    /** Duration as words: "2 h 10" / "48 min". */
    fun duration(walk: SavedWalk): String {
        val mins = ((walk.endedAt - walk.startedAt) / 60_000L).coerceAtLeast(0)
        return if (mins >= 60) "${mins / 60} h ${(mins % 60).toString().padStart(2, '0')}"
        else "$mins min"
    }

    fun dateLine(walk: SavedWalk): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(walk.startedAt))

    /**
     * Write the walk as a GPX file into `filesDir/share/` and return a
     * content:// URI for it — the share sheet's currency. Coordinates go back
     * to WGS84 at the door, the same edge the app brought them in through.
     */
    fun asGpxUri(ctx: Context, walk: SavedWalk): Uri {
        val shareDir = File(ctx.filesDir, "share").apply { mkdirs() }
        // One shared file at a time; the previous export is stale by definition.
        shareDir.listFiles()?.forEach { it.delete() }
        val safeName = walk.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
            .ifEmpty { "walk" }.replace(' ', '_')
        val f = File(shareDir, "$safeName.gpx")
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Waymark" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<metadata><name>").append(xml(walk.name)).append("</name>")
        if (walk.notes.isNotBlank() || walk.place.isNotBlank()) {
            sb.append("<desc>").append(xml(listOf(walk.place, walk.notes).filter { it.isNotBlank() }.joinToString(" — ")))
                .append("</desc>")
        }
        sb.append("</metadata>\n<trk><name>").append(xml(walk.name)).append("</name><trkseg>\n")
        for (p in walk.points) {
            val (lat, lon) = Bng.toWgs84(p)
            sb.append("""<trkpt lat="%.6f" lon="%.6f"/>""".format(lat, lon)).append('\n')
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        f.writeText(sb.toString())
        return Uri.parse("content://${ShareProvider.AUTHORITY}/${f.name}")
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/**
 * The smallest possible way to hand one file to the share sheet. Android
 * stopped accepting file:// URIs years ago and the usual answer is androidx's
 * FileProvider — but this project carries no androidx, and serving one
 * read-only file from one directory is thirty lines, not a dependency.
 */
class ShareProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.jollydoddger.waymark.share"
    }

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null // stay in the box
        val f = File(File(context!!.filesDir, "share"), name)
        return if (f.exists()) f else null
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/gpx+xml"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        // Share targets ask for the display name and size; answer honestly.
        val f = fileFor(uri)
        val cols = arrayOf("_display_name", "_size")
        val cursor = MatrixCursor(cols)
        if (f != null) cursor.addRow(arrayOf(f.name, f.length()))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
