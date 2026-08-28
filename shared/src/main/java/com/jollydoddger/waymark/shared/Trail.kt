package com.jollydoddger.waymark.shared

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * The breadcrumb trail: where he has already been on this walk, so it is
 * obvious at a glance where he still has to go.
 *
 * One walk at a time, by his choice — Start clears the last one. Points are
 * appended in grid metres and written atomically, for the same reason the
 * route is: a truncating write interrupted mid-walk would eat the walk it is
 * recording.
 */
object TrailStore {
    /** Two fixes closer together than this are the same standing-still. */
    private const val MIN_STEP_M = 8.0

    /** A day's walking at 8 m a point is a few thousand; this is generous. */
    private const val MAX_POINTS = 40_000

    private fun file(ctx: Context) = File(ctx.filesDir, "trail.json")

    @Volatile private var cache: MutableList<En>? = null

    @Synchronized
    fun points(ctx: Context): List<En> {
        cache?.let { return it }
        val loaded = ArrayList<En>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                val es = o.getJSONArray("e")
                val ns = o.getJSONArray("n")
                for (i in 0 until es.length()) loaded.add(En(es.getDouble(i), ns.getDouble(i)))
            } catch (e: Exception) {
                // A corrupt trail is not worth losing the walk over; start clean.
            }
        }
        cache = loaded
        return loaded
    }

    /** True if the point was far enough from the last to be worth keeping. */
    @Synchronized
    fun add(ctx: Context, p: En): Boolean {
        val pts = points(ctx) as MutableList<En>
        val last = pts.lastOrNull()
        if (last != null && hypot(p.e - last.e, p.n - last.n) < MIN_STEP_M) return false
        if (pts.size >= MAX_POINTS) return false
        pts.add(p)
        write(ctx, pts)
        return true
    }

    @Synchronized
    fun clear(ctx: Context) {
        cache = ArrayList()
        write(ctx, emptyList())
    }

    private fun write(ctx: Context, pts: List<En>) {
        val es = JSONArray()
        val ns = JSONArray()
        pts.forEach { es.put(it.e); ns.put(it.n) }
        val json = JSONObject().put("e", es).put("n", ns).toString()
        val tmp = File(ctx.filesDir, "trail.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }
    }
}
