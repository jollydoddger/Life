package com.jollydoddger.waymark

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jollydoddger.waymark.shared.En
import com.jollydoddger.waymark.shared.Gpx
import com.jollydoddger.waymark.shared.Prefs.libraryFolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * His own GPX collection: a folder he points the app at, indexed so "walks
 * near me" can search it by closest approach. He fills it himself — komoot,
 * AllTrails, OS Maps and the rest all let a signed-in user export their own
 * saved routes, and that export is his to do; the app's job is only to read
 * what's in the folder. Nothing here scrapes anything.
 *
 * The index holds, per route: name, document URI, grid bounding box, and a
 * decimated line (≤ 300 points) — enough for proximity search and preview
 * without re-parsing hundreds of files per query.
 */
object Library {

    data class Entry(
        val name: String,
        val uri: String,
        val minE: Double,
        val minN: Double,
        val maxE: Double,
        val maxN: Double,
        val points: List<En>,
    )

    private fun file(ctx: Context) = File(ctx.filesDir, "library.json")

    /**
     * Walk the chosen tree with DocumentsContract directly (this app carries
     * no androidx, so no DocumentFile), parse every .gpx, rebuild the index.
     * Returns words about what happened — counts, not silence.
     */
    fun rescan(ctx: Context): String {
        val treeUri = ctx.libraryFolder.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
            ?: return "No library folder chosen yet."

        val gpxDocs = ArrayList<Pair<String, String>>() // (display name, documentId)
        fun listChildren(documentId: String, depth: Int) {
            if (depth > 6) return // a sane folder tree, not a filesystem crawl
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val cursor = runCatching {
                ctx.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )
            }.getOrNull() ?: return
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1) ?: ""
                    val mime = it.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        listChildren(id, depth + 1)
                    } else if (name.endsWith(".gpx", ignoreCase = true)) {
                        gpxDocs.add(name to id)
                    }
                }
            }
        }
        listChildren(DocumentsContract.getTreeDocumentId(treeUri), 0)

        val entries = ArrayList<Entry>()
        var unreadable = 0
        for ((displayName, docId) in gpxDocs) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val route = runCatching {
                ctx.contentResolver.openInputStream(docUri)!!.use { Gpx.parse(it) }
            }.getOrNull()
            if (route == null) {
                unreadable++
                continue
            }
            val stride = (route.points.size / 300) + 1
            val pts = route.points.filterIndexed { i, _ -> i % stride == 0 || i == route.points.size - 1 }
            entries.add(
                Entry(
                    name = route.name.ifBlank { displayName.removeSuffix(".gpx") },
                    uri = docUri.toString(),
                    minE = pts.minOf { it.e }, minN = pts.minOf { it.n },
                    maxE = pts.maxOf { it.e }, maxN = pts.maxOf { it.n },
                    points = pts,
                ),
            )
        }
        save(ctx, entries)
        return "Library: ${entries.size} routes indexed" +
            (if (unreadable > 0) ", $unreadable unreadable" else "") + "."
    }

    /** Library routes whose line comes within [radiusM] of [near], closest first. */
    fun search(ctx: Context, near: En, radiusM: Double): List<Pair<Entry, Double>> {
        return load(ctx).mapNotNull { entry ->
            // Bounding box grown by the radius rules most routes out cheaply.
            if (near.e < entry.minE - radiusM || near.e > entry.maxE + radiusM ||
                near.n < entry.minN - radiusM || near.n > entry.maxN + radiusM
            ) return@mapNotNull null
            val d = Geom.closestApproach(near, entry.points)
            if (d <= radiusM) entry to d else null
        }.sortedBy { it.second }
    }

    fun count(ctx: Context): Int = load(ctx).size

    private fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            val es = JSONArray()
            val ns = JSONArray()
            e.points.forEach { es.put(it.e); ns.put(it.n) }
            arr.put(
                JSONObject()
                    .put("name", e.name).put("uri", e.uri)
                    .put("minE", e.minE).put("minN", e.minN)
                    .put("maxE", e.maxE).put("maxN", e.maxN)
                    .put("e", es).put("n", ns),
            )
        }
        val tmp = File(ctx.filesDir, "library.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }
    }

    private fun load(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val es = o.getJSONArray("e")
                val ns = o.getJSONArray("n")
                Entry(
                    name = o.getString("name"),
                    uri = o.getString("uri"),
                    minE = o.getDouble("minE"), minN = o.getDouble("minN"),
                    maxE = o.getDouble("maxE"), maxN = o.getDouble("maxN"),
                    points = (0 until es.length()).map { j -> En(es.getDouble(j), ns.getDouble(j)) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
