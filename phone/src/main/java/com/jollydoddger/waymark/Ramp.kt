package com.jollydoddger.waymark

import kotlin.math.floor

/**
 * Turning numbers into colour, and the smoothing that goes with it.
 *
 * Deliberately plain arithmetic with no Android in it: these ramps decide
 * what he believes about the sky at a glance, so they are the part worth
 * being able to test on a bare JVM.
 *
 * Alpha is baked in per colour rather than left to the paint, because the
 * layers want different things from it — cloud has to be see-through where
 * the sky is clear, whereas a temperature wash is even all over.
 */
object Ramp {

    private fun pack(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun mix(c0: Int, c1: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val a = (c0 shr shift) and 0xFF
            val b = (c1 shr shift) and 0xFF
            return (a + (b - a) * u).toInt()
        }
        return pack(ch(24), ch(16), ch(8), ch(0))
    }

    /**
     * Interpolate a square grid, skipping readings the forecast did not
     * supply. A missing corner must not drag a whole quarter of the map to
     * zero — it drops out of the average and the neighbours carry it. Only
     * when every corner is missing does the pixel go clear, which is the
     * honest picture of knowing nothing there.
     */
    fun bilinear(values: DoubleArray, n: Int, r: Double, c: Double): Double {
        if (n <= 0 || values.size < n * n) return Double.NaN
        val r0 = floor(r).toInt().coerceIn(0, n - 1)
        val c0 = floor(c).toInt().coerceIn(0, n - 1)
        val r1 = (r0 + 1).coerceAtMost(n - 1)
        val c1 = (c0 + 1).coerceAtMost(n - 1)
        val fr = (r - r0).coerceIn(0.0, 1.0)
        val fc = (c - c0).coerceIn(0.0, 1.0)
        var sum = 0.0
        var weight = 0.0
        fun add(rr: Int, cc: Int, w: Double) {
            if (w <= 0.0) return
            val v = values[rr * n + cc]
            if (v.isNaN()) return
            sum += v * w
            weight += w
        }
        add(r0, c0, (1 - fr) * (1 - fc))
        add(r0, c1, (1 - fr) * fc)
        add(r1, c0, fr * (1 - fc))
        add(r1, c1, fr * fc)
        return if (weight <= 0.0) Double.NaN else sum / weight
    }

    private class Stop(val at: Double, val colour: Int)

    private fun ramp(stops: Array<Stop>, v: Double): Int {
        if (v <= stops.first().at) return stops.first().colour
        if (v >= stops.last().at) return stops.last().colour
        for (i in 1 until stops.size) {
            if (v <= stops[i].at) {
                val lo = stops[i - 1]
                val hi = stops[i]
                val span = hi.at - lo.at
                return mix(lo.colour, hi.colour, if (span <= 0) 0.0 else (v - lo.at) / span)
            }
        }
        return stops.last().colour
    }

    /** Degrees C, on the range a walk in Wales actually happens in. */
    private val TEMPERATURE = arrayOf(
        Stop(-8.0, pack(255, 92, 40, 140)),
        Stop(0.0, pack(255, 48, 96, 196)),
        Stop(5.0, pack(255, 64, 164, 206)),
        Stop(10.0, pack(255, 62, 172, 112)),
        Stop(15.0, pack(255, 214, 200, 62)),
        Stop(20.0, pack(255, 236, 152, 42)),
        Stop(26.0, pack(255, 220, 72, 40)),
        Stop(32.0, pack(255, 152, 26, 62)),
    )

    fun temperature(celsius: Double): Int = ramp(TEMPERATURE, celsius)

    /**
     * The sky as one colour, from what each layer of it actually does to a
     * day out. Clear sky is **nothing at all** — no wash, the map as
     * printed. (An earlier version marked clear sky gold; it covered the
     * whole map most of the time and said nothing. The ink goes where the
     * weather is.)
     *
     * Low cloud is the walk-wrecker, mid dims, high cirrus barely matters —
     * so cover is weighed, not summed. Fog is not cloud at all: visibility
     * under a kilometre is the one condition that turns a walk-by-sight
     * into a compass leg, so it paints denser and warmer than any overcast,
     * whatever the sky above it says.
     */
    fun sky(low: Double, mid: Double, high: Double, visibilityM: Double): Int {
        val foggy = !visibilityM.isNaN() && visibilityM < FOG_VIS_M
        if (foggy) return pack(200, 148, 138, 116)
        val l = if (low.isNaN()) 0.0 else low
        val m = if (mid.isNaN()) 0.0 else mid
        val h = if (high.isNaN()) 0.0 else high
        if (low.isNaN() && mid.isNaN() && high.isNaN()) return 0
        val gloom = (l + 0.7 * m + 0.35 * h).coerceAtMost(100.0)
        return gloom(gloom)
    }

    /** Metres of visibility below which the sky wash calls it fog. */
    const val FOG_VIS_M = 1_000.0

    /** Weighted cover to grey: nothing below a quarter, never opaque —
     *  the map underneath is the thing being walked on. */
    fun gloom(g: Double): Int = if (g < 25.0) 0 else ramp(GLOOM, g)

    private val GLOOM = arrayOf(
        Stop(25.0, pack(0, 150, 156, 166)),
        Stop(45.0, pack(46, 150, 156, 166)),
        Stop(65.0, pack(96, 138, 144, 154)),
        Stop(85.0, pack(150, 118, 124, 134)),
        Stop(100.0, pack(190, 96, 102, 112)),
    )

    /**
     * Forecast rainfall in mm per hour. Drizzle is drawn boldly on purpose:
     * the whole complaint that started this was rain you could not see.
     */
    private val RAIN = arrayOf(
        Stop(0.02, pack(0, 90, 170, 240)),
        Stop(0.10, pack(120, 90, 170, 240)),
        Stop(0.50, pack(180, 36, 100, 224)),
        Stop(2.0, pack(206, 32, 178, 100)),
        Stop(6.0, pack(220, 236, 206, 48)),
        Stop(12.0, pack(230, 240, 132, 32)),
        Stop(25.0, pack(238, 226, 46, 46)),
    )

    fun rain(mmPerHour: Double): Int =
        if (mmPerHour < 0.02) 0 else ramp(RAIN, mmPerHour)
}
