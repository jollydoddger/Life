package com.jollydoddger.waymark

import android.content.Context
import com.jollydoddger.waymark.shared.En
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Walks the assistant has queued for the picker on the map.
 *
 * A file rather than memory because the assistant runs in two places — the
 * chat screen and the map's own ask bar — and the picker lives only on the
 * map: the candidates have to survive the hop between activities, and a
 * process death in between. The file existing is the whole "pending" state;
 * adopting or dismissing deletes it. No seen-flags to get out of sync.
 *
 * Six hours and they expire — long enough that leaving the app and coming
 * back mid-afternoon still finds them, short enough that yesterday's
 * candidates never haunt today's map. And expiry stopped being loss the
 * moment downloads became files: the queue is a working surface, the gpx
 * folder is the keep, and "All walks" on the walks panel rebuilds a
 * picker from the keep whenever one is wanted.
 */
object WalkPicks {

    private const val TTL_MS = 6 * 3600_000L
    private const val MAX = 20

    private fun file(c: Context) = File(c.filesDir, "picks.json")

    fun replace(c: Context, walks: List<RouteFinder.FoundWalk>) =
        save(c, walks.take(MAX))

    fun append(c: Context, walk: RouteFinder.FoundWalk) =
        save(c, (pending(c) + walk).takeLast(MAX))

    fun pending(c: Context): List<RouteFinder.FoundWalk> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            if (System.currentTimeMillis() - o.optLong("at") > TTL_MS) {
                f.delete()
                return emptyList()
            }
            val arr = o.getJSONArray("walks")
            (0 until arr.length()).map { i ->
                val w = arr.getJSONObject(i)
                RouteFinder.FoundWalk(
                    name = w.optString("name"),
                    source = w.optString("source"),
                    lines = w.getJSONArray("lines").let { ls ->
                        (0 until ls.length()).map { j ->
                            val line = ls.getJSONArray(j)
                            (0 until line.length() step 2).map { k ->
                                En(line.getDouble(k), line.getDouble(k + 1))
                            }
                        }
                    },
                    closestM = w.optDouble("closestM"),
                    lengthM = w.optDouble("lengthM"),
                    uri = w.optString("uri").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            // A broken picks file is not worth crashing a map over.
            f.delete()
            emptyList()
        }
    }

    /**
     * He closed the picker — remember that, without losing the batch.
     *
     * "If I close it I can't get back to it": dismissal used to delete the
     * file, so a mis-tap on ✕ threw away a search he had just waited ninety
     * seconds for. Now the batch stays (until the TTL, or the next search
     * replaces it) and only the *auto-opening* is silenced: [freshPending]
     * skips a dismissed batch so the picker stops popping back up on every
     * resume, and the GPX menu's picker entry reads [pending], which
     * ignores the mark — that is the way back.
     */
    fun dismiss(c: Context) {
        val f = file(c)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.put("dismissedAt", System.currentTimeMillis())
            val tmp = File(f.parentFile, "picks.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            f.delete()
        }
    }

    /** The batch, unless he has already closed it once — for the auto-open
     *  paths, which must not haunt him with a picker he dismissed. */
    fun freshPending(c: Context): List<RouteFinder.FoundWalk> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        val dismissed = runCatching {
            val o = JSONObject(f.readText())
            o.optLong("dismissedAt") >= o.optLong("at")
        }.getOrDefault(false)
        return if (dismissed) emptyList() else pending(c)
    }

    fun consume(c: Context) {
        file(c).delete()
    }

    private fun save(c: Context, walks: List<RouteFinder.FoundWalk>) {
        val arr = JSONArray()
        for (w in walks) {
            arr.put(
                JSONObject().apply {
                    put("name", w.name)
                    put("source", w.source)
                    put("closestM", w.closestM)
                    put("lengthM", w.lengthM)
                    w.uri?.let { put("uri", it) }
                    // Flat e,n pairs: half the JSON of an object per point,
                    // and a downloaded route can run to thousands of them.
                    put("lines", JSONArray().also { ls ->
                        for (line in w.lines) {
                            ls.put(JSONArray().also { la ->
                                for (p in line) { la.put(p.e); la.put(p.n) }
                            })
                        }
                    })
                },
            )
        }
        val body = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("walks", arr)
            .toString()
        val f = file(c)
        val tmp = File(f.parentFile, "picks.json.tmp")
        tmp.writeText(body)
        tmp.renameTo(f)
    }
}
