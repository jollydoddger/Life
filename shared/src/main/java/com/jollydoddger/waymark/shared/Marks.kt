package com.jollydoddger.waymark.shared

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/**
 * Points on the route he has asked to be buzzed at — the turn he must not
 * miss, the peak, the lunch spot. Numbered, at most five, and bound to the
 * route they were tapped on: a new route silently retires them, because a
 * flag on a line he is no longer walking is a lie waiting for an alarm.
 */
class Mark(val number: Int, val e: Double, val n: Double, val alongM: Double) {
    fun en() = En(e, n)
}

object Marks {

    const val MAX = 5

    /** Close enough to say "you're there". GPS in a pocket wobbles tens of
     *  metres, and the point of the buzz is not to be missed. */
    const val ARRIVE_M = 40.0

    private fun file(c: Context) = File(c.filesDir, "marks.json")

    fun load(c: Context, routeFingerprint: String): List<Mark> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            if (o.optString("route") != routeFingerprint) return emptyList()
            val arr = o.getJSONArray("marks")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Mark(m.getInt("number"), m.getDouble("e"), m.getDouble("n"), m.getDouble("alongM"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw load for the service, which has no route in hand: the fingerprint
     *  bound at save time is trusted, since only the map writes marks. */
    fun loadAny(c: Context): List<Mark> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("marks")
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Mark(m.getInt("number"), m.getDouble("e"), m.getDouble("n"), m.getDouble("alongM"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(c: Context, routeFingerprint: String, e: Double, n: Double, alongM: Double): Mark? {
        val existing = load(c, routeFingerprint)
        if (existing.size >= MAX) return null
        val number = ((existing.maxOfOrNull { it.number } ?: 0) + 1)
        val mark = Mark(number, e, n, alongM)
        save(c, routeFingerprint, existing + mark)
        return mark
    }

    fun remove(c: Context, routeFingerprint: String, number: Int) {
        save(c, routeFingerprint, load(c, routeFingerprint).filter { it.number != number })
    }

    fun clear(c: Context) {
        file(c).delete()
    }

    /** The armed mark the fix has arrived at, if any. */
    fun arrivedAt(c: Context, fix: En): Mark? =
        loadAny(c).firstOrNull { hypot(it.e - fix.e, it.n - fix.n) <= ARRIVE_M }

    /** Drop one mark by number, whatever route it was bound to — the
     *  service's cleanup after a buzz. */
    fun removeAny(c: Context, number: Int) {
        val f = file(c)
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("marks")
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.getInt("number") != number) kept.put(m)
            }
            o.put("marks", kept)
            writeAtomic(f, o.toString())
        } catch (e: Exception) {
        }
    }

    private fun save(c: Context, routeFingerprint: String, marks: List<Mark>) {
        val arr = JSONArray()
        for (m in marks) {
            arr.put(
                JSONObject().put("number", m.number).put("e", m.e).put("n", m.n)
                    .put("alongM", m.alongM),
            )
        }
        writeAtomic(file(c), JSONObject().put("route", routeFingerprint).put("marks", arr).toString())
    }

    private fun writeAtomic(f: File, body: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(body)
        tmp.renameTo(f)
    }
}
