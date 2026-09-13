package com.jollydoddger.waymark.shared

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
class Mark(
    val number: Int,
    val e: Double,
    val n: Double,
    val alongM: Double,
    /**
     * Why this flag is here, when it was not put here by hand.
     *
     * Blank for one he tapped: he knows why he tapped it. Set for one the
     * app raised on his behalf — "692 m with no recorded tracks" — which
     * makes the flag explain itself when it is tapped weeks later, and is
     * the only thing separating the app's flags from his own. Nothing may
     * remove or overwrite a blank one.
     */
    val why: String = "",
) {
    fun en() = En(e, n)

    /** Raised by the app rather than tapped by him. */
    val automatic: Boolean get() = why.isNotBlank()
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
                Mark(
                    m.getInt("number"), m.getDouble("e"), m.getDouble("n"),
                    m.getDouble("alongM"), m.optString("why"),
                )
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
                Mark(
                    m.getInt("number"), m.getDouble("e"), m.getDouble("n"),
                    m.getDouble("alongM"), m.optString("why"),
                )
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

    /**
     * Put the app's own flags on the route: every mark it raised before is
     * replaced, and every mark he tapped himself is left exactly where it
     * is. Returns how many landed and how many would not fit.
     *
     * His flags come first for the five slots, always. A check that found
     * seven doubtful stretches must not quietly evict the summit he
     * flagged last week to make room for them — the automatic ones are a
     * convenience and his are the point of the feature.
     */
    fun raise(
        c: Context,
        routeFingerprint: String,
        found: List<Triple<Double, Double, Double>>,
        why: (Int) -> String,
    ): Pair<Int, Int> {
        val after = raised(load(c, routeFingerprint), found, why)
        save(c, routeFingerprint, after)
        val placed = after.count { it.automatic }
        return placed to (found.size - placed)
    }

    /**
     * The list a [raise] produces — separated from the file so the one
     * promise in here that matters can be tested: **a flag he tapped is
     * never removed, renumbered or crowded out.** His come first for the
     * five slots, and what will not fit is reported rather than dropped
     * quietly.
     */
    fun raised(
        existing: List<Mark>,
        found: List<Triple<Double, Double, Double>>,
        why: (Int) -> String,
    ): List<Mark> {
        val his = existing.filter { !it.automatic }
        val room = (MAX - his.size).coerceAtLeast(0)
        val take = minOf(room, found.size)
        var number = his.maxOfOrNull { it.number } ?: 0
        return his + (0 until take).map { i ->
            val (e, n, alongM) = found[i]
            Mark(++number, e, n, alongM, why(i))
        }
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

    const val CHANNEL = "waymark_marks"
    private const val NOTIFICATION_BASE = 4200

    /**
     * The point he asked not to miss, arriving as a buzz he cannot. High
     * importance with vibration — the one notification in the app that
     * exists to interrupt — and the mark clears itself, so it fires once,
     * not again every fix while he stands on it. Shared between the
     * recording service and the open map, because the buzz has to work from
     * whichever of them is watching the fixes.
     */
    fun buzz(ctx: Context, mark: Mark) {
        removeAny(ctx, mark.number)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Marked point reached", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                },
            )
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        nm.notify(
            NOTIFICATION_BASE + mark.number,
            Notification.Builder(ctx, CHANNEL)
                .setContentTitle("You're at mark ${mark.number}")
                .setContentText("The point you flagged on the route is here.")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

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
                    .put("alongM", m.alongM).put("why", m.why),
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
