package com.jollydoddger.waymark

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jollydoddger.waymark.shared.Bng
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Prefs.backupFolder
import com.jollydoddger.waymark.shared.Prefs.lastBackupAt
import com.jollydoddger.waymark.shared.Route
import com.jollydoddger.waymark.shared.RouteStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * His walks, somewhere other than this phone.
 *
 * "Make sure you back my routes up." Everything the app holds lives in
 * its private files, which a lost phone, a reinstall or a cleared app
 * takes with it — and the one wipe this project has already had (the
 * signing key) is exactly that. Two answers, both as GPX, because GPX is
 * the one format every other map app reads and this one imports:
 *
 * - A **backup folder** he picks once — Drive, a card, a synced folder —
 *   into which every saved walk and every route he sets is written the
 *   moment it exists, and rewritten under the same name when it changes.
 *   Nothing to remember, which is the only backup that gets done.
 * - A **zip** of the lot, saved wherever the picker points, for the day
 *   he wants one file to keep.
 *
 * DocumentsContract directly, as the library does: the project carries
 * no androidx, so no DocumentFile, and creating one file in one folder
 * is a query and a create, not a dependency.
 */
object Backup {

    /** A file name a folder will accept, from a walk's name. */
    fun fileName(name: String, fallback: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim()
            .ifEmpty { fallback }.replace(' ', '_').take(80)
        return "$safe.gpx"
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** GPX for any named line. Coordinates go back to WGS84 at the door,
     *  the same edge the app brought them in through. A saved walk is a
     *  track (it was walked); a route is a route (it is to be walked). */
    fun gpx(name: String, desc: String, points: List<En>, asTrack: Boolean = false, whenMs: Long = 0L): String {
        val sb = StringBuilder(points.size * 48 + 400)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Waymark" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("<metadata><name>").append(xml(name)).append("</name>")
        if (desc.isNotBlank()) sb.append("<desc>").append(xml(desc)).append("</desc>")
        if (whenMs > 0) {
            sb.append("<time>").append(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(whenMs)),
            ).append("</time>")
        }
        sb.append("</metadata>\n")
        val (open, pt, close) = if (asTrack) Triple("<trk><name>${xml(name)}</name><trkseg>", "trkpt", "</trkseg></trk>")
        else Triple("<rte><name>${xml(name)}</name>", "rtept", "</rte>")
        sb.append(open).append('\n')
        for (p in points) {
            val (lat, lon) = Bng.toWgs84(p)
            sb.append("<").append(pt).append(""" lat="%.6f" lon="%.6f"/>""".format(Locale.UK, lat, lon)).append('\n')
        }
        sb.append(close).append("\n</gpx>\n")
        return sb.toString()
    }

    fun gpx(walk: Walks.SavedWalk): String = gpx(
        walk.name,
        listOf(walk.place, walk.notes).filter { it.isNotBlank() }.joinToString(" — "),
        walk.points,
        asTrack = true,
        whenMs = walk.startedAt,
    )

    /** The saved walk's file name carries its date, so two "Walk, 5 Sept"s
     *  from different years do not overwrite each other in the folder. */
    fun fileName(walk: Walks.SavedWalk): String =
        fileName(SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(walk.startedAt)) + " " + walk.name, "walk")

    // --- the zip -------------------------------------------------------------

    /** Everything worth keeping, as (file name, contents). */
    fun bundle(ctx: Context): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        val seen = HashSet<String>()
        fun add(name: String, text: String) {
            var n = name
            var k = 2
            while (!seen.add(n)) n = name.removeSuffix(".gpx") + " (${k++}).gpx"
            out.add(n to text.toByteArray(Charsets.UTF_8))
        }
        for (w in Walks.list(ctx)) add("walks/" + fileName(w), gpx(w))
        RouteStore.load(ctx)?.let { r ->
            if (r.points.size >= 2) add("routes/" + fileName(r.name, "route"), gpx(r.name, "", r.points))
        }
        // The walks' own JSON too — notes, times and places survive a
        // round trip through this app exactly, where GPX carries them as
        // one description line.
        val dir = java.io.File(ctx.filesDir, "walks")
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            out.add("walks/json/${f.name}" to f.readBytes())
        }
        return out
    }

    fun zipName(): String =
        "waymark-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date()) + ".zip"

    /** Write the bundle as a zip; returns how many GPX files it holds. */
    fun zip(ctx: Context, into: OutputStream): Int {
        val files = bundle(ctx)
        ZipOutputStream(into.buffered()).use { z ->
            for ((name, bytes) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        ctx.lastBackupAt = System.currentTimeMillis()
        return files.count { it.first.endsWith(".gpx") }
    }

    // --- the folder ----------------------------------------------------------

    fun folder(ctx: Context): Uri? = ctx.backupFolder.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    /** The document already called [name] in the folder, if any. */
    private fun existing(ctx: Context, tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val cursor = runCatching {
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )
        }.getOrNull() ?: return null
        cursor.use {
            while (it.moveToNext()) {
                if (it.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                }
            }
        }
        return null
    }

    /**
     * Write [text] as [name] in the backup folder, replacing a file of that
     * name. Quiet on failure — a backup that cannot be written must not
     * stop the walk being saved — but says so through the return.
     */
    fun write(ctx: Context, name: String, text: String): Boolean {
        val tree = folder(ctx) ?: return false
        return runCatching {
            val doc = existing(ctx, tree, name) ?: DocumentsContract.createDocument(
                ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/gpx+xml",
                name,
            ) ?: return false
            ctx.contentResolver.openOutputStream(doc, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ctx.lastBackupAt = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }

    fun walk(ctx: Context, w: Walks.SavedWalk): Boolean = write(ctx, fileName(w), gpx(w))

    fun route(ctx: Context, r: Route): Boolean =
        r.points.size >= 2 && write(ctx, "route " + fileName(r.name, "route"), gpx(r.name, "", r.points))

    /** Every saved walk and the loaded route, into the folder. Words back. */
    fun all(ctx: Context): String {
        if (folder(ctx) == null) return "No backup folder chosen."
        var ok = 0
        var failed = 0
        for (w in Walks.list(ctx)) if (walk(ctx, w)) ok++ else failed++
        RouteStore.load(ctx)?.let { if (route(ctx, it)) ok++ else failed++ }
        return "Backed up $ok file${if (ok == 1) "" else "s"}" +
            (if (failed > 0) ", $failed couldn't be written" else "") + "."
    }

    fun lastLine(ctx: Context): String {
        val at = ctx.lastBackupAt
        if (at == 0L) return "Nothing backed up yet."
        return "Last backup " + SimpleDateFormat("d MMM, HH:mm", Locale.UK).format(Date(at)) + "."
    }
}
