package com.jollydoddger.waymark

import android.content.Context
import android.net.Uri
import com.jollydoddger.waymark.shared.Gpx
import java.io.File

/**
 * Every GPX the assistant downloads, kept as a plain .gpx file.
 *
 * The picker's queue expires and its candidates are consumed — that is
 * right for a queue and wrong for a route somebody went to the trouble of
 * finding. So the file itself is saved the moment it parses, named after
 * the walk, and the saved folder is one of the sources every walk search
 * draws from: a route downloaded once is findable for ever, network or no
 * network, whether or not it was ever adopted.
 */
object Downloads {

    private fun dir(c: Context) = File(c.filesDir, "gpx").apply { mkdirs() }

    /** Save the raw bytes; the name comes from the parsed route. Returns the
     *  file, reusing an identical earlier download rather than duplicating. */
    fun save(c: Context, name: String, bytes: ByteArray): File {
        val safe = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().take(60)
            .ifEmpty { "walk" }
        val hash = Integer.toHexString(bytes.contentHashCode())
        val f = File(dir(c), "$safe-$hash.gpx")
        if (!f.exists()) {
            val tmp = File(dir(c), "$safe-$hash.gpx.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        return f
    }

    /**
     * The saved routes as found-walk candidates. Parsed on demand — the
     * folder holds this one walker's downloads, not an archive, and a few
     * dozen small parses cost less than an index that can drift from the
     * files it describes.
     */
    fun walks(c: Context, near: com.jollydoddger.waymark.shared.En): List<RouteFinder.FoundWalk> =
        (dir(c).listFiles { f -> f.name.endsWith(".gpx") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val route = f.inputStream().use { Gpx.parse(it) }
                // Decimated the same way the library index is: the picker
                // preview needs the shape, not every vertex.
                val stride = (route.points.size / 300) + 1
                val pts = route.points.filterIndexed { i, _ ->
                    i % stride == 0 || i == route.points.size - 1
                }
                RouteFinder.FoundWalk(
                    name = route.name.ifBlank { f.name.removeSuffix(".gpx") },
                    source = "Saved",
                    lines = listOf(pts),
                    closestM = Geom.closestApproach(near, pts),
                    lengthM = Geom.length(route.points),
                    uri = Uri.fromFile(f).toString(),
                )
            }.getOrNull()
        }
}
