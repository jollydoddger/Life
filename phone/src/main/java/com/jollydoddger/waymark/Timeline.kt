package com.jollydoddger.waymark

/**
 * One moment the weather layers can be drawn at.
 *
 * [radarPath] is set only when a real radar sweep exists for this moment.
 * Everything else is a forecast model, and the two must never be presented
 * as the same thing: radar is a measurement of rain that fell, a model is an
 * opinion about rain that might. The label the scrubber shows says which.
 */
data class WxFrame(
    val timeMs: Long,
    val radarPath: String?,
    /** A radar frame that is itself a short extrapolation, not an observation. */
    val nowcast: Boolean = false,
) {
    val kind: String get() = when {
        radarPath != null && nowcast -> "radar nowcast"
        radarPath != null -> "radar"
        else -> "forecast"
    }
}

/**
 * The scrub timeline: three hours back, twelve hours forward.
 *
 * He asked for both halves, and the honest answer is that they are not made
 * of the same stuff. Real radar reaches about two hours back and half an hour
 * on — that is the whole of what RainViewer's free service publishes, and no
 * amount of wanting extends it. The rest of the span is filled from the
 * hourly forecast grid, at the model's own coarser resolution, and every
 * frame says out loud which of the two it is.
 *
 * The halves are unequal on purpose. Back covers the radar and an hour of
 * context — rain that has gone is the least useful hour on the bar.
 * Forward is the length of a day out: "as detailed future as possible" is
 * the ask, and a five-hour horizon was answering "what at four" with a
 * shrug by lunchtime.
 */
object Timeline {

    /** Hours back from now. Radar reaches two; one more for the run-up. */
    const val BACK_HOURS = 3

    /** Hours forward. A day's walk, and the point forecast's own reach. */
    const val AHEAD_HOURS = 12

    /**
     * Radar frames where radar exists, forecast hours either side of it.
     *
     * Forecast frames are never added inside the radar's own window: a model
     * hour laid over an observed sweep would be a worse answer sitting on top
     * of a better one.
     */
    fun merge(radar: List<WxFrame>, hourlyMs: List<Long>, nowMs: Long): List<WxFrame> {
        val from = nowMs - BACK_HOURS * 3_600_000L
        val to = nowMs + AHEAD_HOURS * 3_600_000L
        val out = ArrayList<WxFrame>()
        for (f in radar) if (f.timeMs in from..to) out.add(f)
        val radarFrom = out.minOfOrNull { it.timeMs } ?: Long.MAX_VALUE
        val radarTo = out.maxOfOrNull { it.timeMs } ?: Long.MIN_VALUE
        for (t in hourlyMs) {
            if (t < from || t > to) continue
            if (t in radarFrom..radarTo) continue
            out.add(WxFrame(t, null))
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** Where the scrubber should sit when it appears: on the moment nearest now. */
    fun indexOfNow(frames: List<WxFrame>, nowMs: Long): Int {
        if (frames.isEmpty()) return 0
        var best = 0
        var bestGap = Long.MAX_VALUE
        for (i in frames.indices) {
            val gap = kotlin.math.abs(frames[i].timeMs - nowMs)
            if (gap < bestGap) { bestGap = gap; best = i }
        }
        return best
    }
}
