package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.En
import org.json.JSONObject
import kotlin.math.hypot

/**
 * "A circular, two to three hours, Saturday" — said once, as a form, rather
 * than typed at an assistant every time.
 *
 * That is the whole point of this file and it is worth stating plainly: the
 * assistant can already do all of this, and asking it costs a paid call, a
 * sentence he has to compose, and a wait. A walk is specified by four
 * things — its shape, how long it should be, whether that length is in
 * miles or in hours, and which day — and four things is a form. The form
 * remembers what he last said, so the ordinary case is opening it and
 * tapping Find.
 *
 * Everything here is arithmetic and no network, because "east, three hours,
 * circular" quietly producing the wrong candidates is exactly the failure a
 * unit test catches and a walk in the rain does not.
 */

/** What he asked for. */
enum class Shape { CIRCULAR, OUT_AND_BACK, ANY }

/**
 * Where the walk is allowed to begin — the question the first version of
 * this form forgot to ask, and the one that decides everything else. A
 * five-mile circular is a different walk from his kitchen than from a car
 * park two valleys away, and until he could say which, the form could only
 * ever plan the first.
 *
 * All three are a *region*, never a point: he said "within like 500 m"
 * himself, and a start pinned to the exact metre is a start that often has
 * no junction to work with.
 */
enum class Origin {
    /** Where he is, give or take 500 m. */
    HERE,

    /** Somewhere he taps on the map, give or take 500 m. Needs no GPS at
     *  all, which is the point: planning happens at a kitchen table as
     *  often as at a trailhead. */
    TAP,

    /** Anywhere on the map as it is currently framed. The map in front of
     *  you is the question — the same framing "Walks on this map" uses. */
    SCREEN,
}

/** What a walk in front of us actually *is* — measured off its line, never
 *  taken from its name. Plenty of routes called "circular" are not. */
enum class Form { CIRCULAR, OUT_AND_BACK, LINEAR }

data class WalkSpec(
    val shape: Shape = Shape.ANY,
    /** Whether [from]/[to] are hours rather than kilometres. He thinks in
     *  both — "a couple of hours" and "about five miles" are the same
     *  request from opposite ends — and a form that only took one would be
     *  the typing he asked to stop doing. */
    val byTime: Boolean = false,
    val from: Double = 5.0,
    val to: Double = 10.0,
    /** 0 = today. Bounded by what a forecast can honestly cover. */
    val dayOffset: Int = 0,
    /** Where it may start. See [Origin]. */
    val origin: Origin = Origin.HERE,
) {

    /**
     * The length range in metres. Hours become metres through *his* pace,
     * not a book's, which is why the pace is passed in rather than assumed
     * — and the conversion is deliberately flat-ground: climb is unknowable
     * before a route exists, so a three-hour ask fetches three hours of
     * level walking and the brief afterwards says what the hills add.
     */
    fun rangeMetres(paceMinPerKm: Double): Pair<Double, Double> {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        return if (byTime) {
            (lo * 60.0 / paceMinPerKm) * 1000 to (hi * 60.0 / paceMinPerKm) * 1000
        } else {
            lo * 1000 to hi * 1000
        }
    }

    /** How the form reads back, for the status line and the picker title. */
    fun label(): String {
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val size = if (byTime) "${trim(lo)}–${trim(hi)} hours" else "${trim(lo)}–${trim(hi)} km"
        val kind = when (shape) {
            Shape.CIRCULAR -> "circular"
            Shape.OUT_AND_BACK -> "there and back"
            Shape.ANY -> "any shape"
        }
        val where = when (origin) {
            Origin.HERE -> "from near you"
            Origin.TAP -> "from the point you picked"
            Origin.SCREEN -> "starting anywhere on this map"
        }
        return "$kind, $size, ${dayName(dayOffset)}, $where"
    }

    fun toJson(): String = JSONObject()
        .put("shape", shape.name)
        .put("byTime", byTime)
        .put("from", from)
        .put("to", to)
        .put("dayOffset", dayOffset)
        .put("origin", origin.name)
        .toString()

    companion object {
        /** As far ahead as an hourly forecast is worth believing. Offering
         *  a fortnight would be offering a brief nobody should plan on. */
        const val MAX_DAY_OFFSET = 6

        fun fromJson(s: String?): WalkSpec {
            if (s.isNullOrBlank()) return WalkSpec()
            return try {
                val o = JSONObject(s)
                WalkSpec(
                    shape = runCatching { Shape.valueOf(o.optString("shape")) }.getOrDefault(Shape.ANY),
                    byTime = o.optBoolean("byTime"),
                    from = o.optDouble("from", 5.0),
                    to = o.optDouble("to", 10.0),
                    dayOffset = o.optInt("dayOffset").coerceIn(0, MAX_DAY_OFFSET),
                    origin = runCatching { Origin.valueOf(o.optString("origin")) }
                        .getOrDefault(Origin.HERE),
                )
            } catch (e: Exception) {
                WalkSpec()
            }
        }

        fun trim(v: Double): String =
            if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

        fun dayName(offset: Int): String = when (offset) {
            0 -> "today"
            1 -> "tomorrow"
            else -> {
                val c = java.util.Calendar.getInstance()
                c.add(java.util.Calendar.DAY_OF_YEAR, offset)
                java.text.SimpleDateFormat("EEEE", java.util.Locale.UK).format(c.time)
            }
        }
    }
}

/**
 * Turning a pile of found walks into the ones he actually asked for.
 *
 * Separate from [WalkFilter] on purpose: that one narrows by direction and
 * length, which is a question about *where*. This one is a question about
 * *what shape*, and the shape has to be measured off the line because no
 * route's name can be trusted about it.
 */
object Specifier {

    /**
     * Closed enough to call it a round trip: the gap from start to finish
     * against the length walked. A fifth is generous, and generous is right
     * — a real GPX often stops in the next car park along.
     */
    private const val CLOSED_FRACTION = 0.20

    /**
     * A closed walk that comes home down its own outward leg is a
     * there-and-back; one that comes home a different way is a circuit.
     * That is the only difference between them, so it is the thing measured
     * — points on the way out compared against the line coming back.
     *
     * The obvious cheaper test was tried and thrown away: how far the walk
     * reaches from its start, as a fraction of its length. The arithmetic is
     * lovely — a perfect out-and-back reaches 0.5, a circle 0.318 — and it
     * is wrong on real ground, because a wandering there-and-back through a
     * wood reaches barely a third of its length and would be filed as a
     * circuit. Retracing is what he can see out of his own eyes, and it is
     * what the walk is.
     */
    private const val RETRACE_M = 30.0
    private const val RETRACE_FRACTION = 0.7
    private const val RETRACE_SAMPLES = 24

    private fun retraces(points: List<En>): Boolean {
        val cum = Eta.cumulative(points)
        val total = cum.last()
        if (total <= 0) return false
        val turn = points.indices.firstOrNull { cum[it] >= total / 2 } ?: return false
        val out = points.subList(0, turn + 1)
        val back = points.subList(turn, points.size)
        if (out.size < 3 || back.size < 3) return false
        var near = 0
        for (i in 0 until RETRACE_SAMPLES) {
            // The middle 80% of the outward leg only. The two legs meet at
            // both ends of any closed walk whatsoever, so a sample taken
            // there says nothing about which kind it is.
            val t = 0.1 + 0.8 * i / (RETRACE_SAMPLES - 1)
            val at = (t * (out.size - 1)).toInt()
            if (Geom.closestApproach(out[at], back) <= RETRACE_M) near++
        }
        return near >= RETRACE_SAMPLES * RETRACE_FRACTION
    }

    fun formOf(points: List<En>): Form {
        if (points.size < 3) return Form.LINEAR
        val length = Geom.length(points)
        if (length <= 0) return Form.LINEAR
        val gap = hypot(
            points.last().e - points.first().e,
            points.last().n - points.first().n,
        )
        if (gap > length * CLOSED_FRACTION) return Form.LINEAR
        return if (retraces(points)) Form.OUT_AND_BACK else Form.CIRCULAR
    }

    fun formOf(w: RouteFinder.FoundWalk): Form = formOf(w.routePoints())

    fun describe(f: Form): String = when (f) {
        Form.CIRCULAR -> "circular"
        Form.OUT_AND_BACK -> "there and back"
        Form.LINEAR -> "one way"
    }

    /**
     * A one-way walk turned into the walk he'd actually do: out along it and
     * back down it. This is how most out-and-backs come to exist — nobody
     * publishes them, because half of one is already published.
     *
     * The length doubles, and saying so is the point: a 4 km linear path is
     * an 8 km answer to "eight kilometres there and back", and offering it
     * at its published 4 km would be offering half a walk.
     */
    fun thereAndBack(w: RouteFinder.FoundWalk): RouteFinder.FoundWalk {
        val pts = w.routePoints()
        val doubled = pts + pts.reversed().drop(1)
        return w.copy(
            name = "${w.name} — there and back",
            lines = listOf(doubled),
            lengthM = Geom.length(doubled),
            // The GPX behind it describes one leg, not two; re-parsing it on
            // adoption would quietly hand back the half walk.
            uri = null,
        )
    }

    /**
     * The candidates worth putting on the picker: right shape, right length,
     * nearest first. A one-way walk is mirrored when he asked for a
     * there-and-back and only then — turning every linear route into a
     * double-length one on a "circular" ask would be answering a question
     * nobody asked.
     */
    fun shortlist(
        found: List<RouteFinder.FoundWalk>,
        spec: WalkSpec,
        minM: Double,
        maxM: Double,
        limit: Int = 8,
    ): List<RouteFinder.FoundWalk> {
        val out = ArrayList<RouteFinder.FoundWalk>()
        for (w in found) {
            val form = formOf(w)
            val asIs = when (spec.shape) {
                Shape.ANY -> true
                Shape.CIRCULAR -> form == Form.CIRCULAR
                Shape.OUT_AND_BACK -> form == Form.OUT_AND_BACK
            }
            if (asIs && w.lengthM in minM..maxM) {
                out.add(w)
                continue
            }
            if (spec.shape == Shape.OUT_AND_BACK && form == Form.LINEAR) {
                val doubled = thereAndBack(w)
                if (doubled.lengthM in minM..maxM) out.add(doubled)
            }
        }
        return out.sortedBy { it.closestM }.take(limit)
    }
}
