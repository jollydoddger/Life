package com.jollydoddger.waymark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The sky, drawn rather than typed.
 *
 * Same reasoning as the map buttons in Icons.kt: an emoji renders at the
 * mercy of whatever font the phone picked, and the ones for weather are
 * particularly bad about it — several are indistinguishable at row height,
 * and the rain ones do not vary with how hard it is raining at all.
 *
 * Rain intensity is the whole point of this file. He asked for it by name,
 * and it is the reading he actually acts on: drizzle you walk through, and
 * 4 mm an hour you do not set off into. So the drops are counted and their
 * length grows with the rate — a glance down the column shows the shower
 * building without reading a single number.
 */
class WeatherIcon(
    private val code: Int,
    private val density: Float,
    /** Rain rate in mm for the hour, which sets how hard the drops read. */
    private val mm: Double = 0.0,
) : Drawable() {

    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 205, 90) }
    private val cloud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 212, 218) }
    private val darkCloud = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 158, 166) }
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 185, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val flake = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 210, 80) }
    private val fog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 198, 204)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        val u = minOf(w, h)
        drop.strokeWidth = u * 0.085f
        flake.strokeWidth = u * 0.07f
        fog.strokeWidth = u * 0.075f
        val cx = b.left + w / 2
        val cy = b.top + h / 2

        when (code) {
            0 -> sun(canvas, cx, cy, u * 0.30f, rays = true)
            1 -> { sun(canvas, cx - u * 0.14f, cy - u * 0.12f, u * 0.22f, rays = true)
                   cloud(canvas, cx + u * 0.06f, cy + u * 0.12f, u * 0.52f, cloud) }
            2 -> { sun(canvas, cx - u * 0.18f, cy - u * 0.16f, u * 0.19f, rays = false)
                   cloud(canvas, cx + u * 0.04f, cy + u * 0.08f, u * 0.60f, cloud) }
            3 -> { cloud(canvas, cx - u * 0.06f, cy - u * 0.04f, u * 0.55f, darkCloud)
                   cloud(canvas, cx + u * 0.10f, cy + u * 0.10f, u * 0.58f, cloud) }
            45, 48 -> fogBars(canvas, cx, cy, u)
            // Drizzle, rain and showers all share the cloud; the drops carry
            // the difference, which is what he asked to be able to see.
            51, 53, 55, 56, 57 -> rain(canvas, cx, cy, u, drops = 2, short = true)
            61, 66 -> rain(canvas, cx, cy, u, drops = 2, short = false)
            63 -> rain(canvas, cx, cy, u, drops = 3, short = false)
            65, 67 -> rain(canvas, cx, cy, u, drops = 4, short = false)
            80 -> rain(canvas, cx, cy, u, drops = 2, short = false, sunny = true)
            81 -> rain(canvas, cx, cy, u, drops = 3, short = false, sunny = true)
            82 -> rain(canvas, cx, cy, u, drops = 4, short = false, sunny = true)
            71, 77 -> snow(canvas, cx, cy, u, flakes = 2)
            73, 85 -> snow(canvas, cx, cy, u, flakes = 3)
            75, 86 -> snow(canvas, cx, cy, u, flakes = 4)
            95, 96, 99 -> storm(canvas, cx, cy, u)
            else -> cloud(canvas, cx, cy + u * 0.05f, u * 0.55f, darkCloud)
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, rays: Boolean) {
        if (rays) {
            val ray = Paint(sun).apply {
                style = Paint.Style.STROKE
                strokeWidth = r * 0.24f
                strokeCap = Paint.Cap.ROUND
            }
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                c.drawLine(
                    (cx + Math.cos(a) * r * 1.35).toFloat(),
                    (cy + Math.sin(a) * r * 1.35).toFloat(),
                    (cx + Math.cos(a) * r * 1.75).toFloat(),
                    (cy + Math.sin(a) * r * 1.75).toFloat(),
                    ray,
                )
            }
        }
        c.drawCircle(cx, cy, r, sun)
    }

    /** A cloud as three overlapping discs and a flat base — reads as a cloud
     *  at row height, which a single blob does not. */
    private fun cloud(c: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val r = size * 0.30f
        c.drawCircle(cx - r * 0.85f, cy, r * 0.78f, paint)
        c.drawCircle(cx + r * 0.80f, cy, r * 0.68f, paint)
        c.drawCircle(cx, cy - r * 0.48f, r, paint)
        c.drawRect(cx - r * 1.6f, cy - r * 0.1f, cx + r * 1.45f, cy + r * 0.78f, paint)
    }

    private fun rain(
        c: Canvas, cx: Float, cy: Float, u: Float,
        drops: Int, short: Boolean, sunny: Boolean = false,
    ) {
        if (sunny) sun(c, cx - u * 0.26f, cy - u * 0.26f, u * 0.15f, rays = false)
        cloud(c, cx, cy - u * 0.12f, u * 0.52f, if (drops >= 3) darkCloud else cloud)
        // Heavier rain also draws longer, steeper drops — the count alone
        // tops out too early to tell 4 mm from 12.
        val extra = (mm / 6.0).coerceIn(0.0, 1.0).toFloat()
        val len = (if (short) u * 0.12f else u * 0.20f) * (1f + extra * 0.5f)
        val top = cy + u * 0.16f
        val spread = u * 0.30f
        for (i in 0 until drops) {
            val x = cx - spread + (spread * 2f) * (i.toFloat() / (drops - 1).coerceAtLeast(1))
            val stagger = if (i % 2 == 0) 0f else u * 0.06f
            c.drawLine(x, top + stagger, x - u * 0.05f, top + stagger + len, drop)
        }
    }

    private fun snow(c: Canvas, cx: Float, cy: Float, u: Float, flakes: Int) {
        cloud(c, cx, cy - u * 0.12f, u * 0.52f, cloud)
        val top = cy + u * 0.22f
        val spread = u * 0.28f
        for (i in 0 until flakes) {
            val x = cx - spread + (spread * 2f) * (i.toFloat() / (flakes - 1).coerceAtLeast(1))
            val r = u * 0.07f
            c.drawLine(x - r, top, x + r, top, flake)
            c.drawLine(x, top - r, x, top + r, flake)
        }
    }

    private fun storm(c: Canvas, cx: Float, cy: Float, u: Float) {
        cloud(c, cx, cy - u * 0.14f, u * 0.55f, darkCloud)
        val p = Path().apply {
            moveTo(cx + u * 0.06f, cy + u * 0.08f)
            lineTo(cx - u * 0.10f, cy + u * 0.30f)
            lineTo(cx + u * 0.01f, cy + u * 0.30f)
            lineTo(cx - u * 0.06f, cy + u * 0.46f)
            lineTo(cx + u * 0.14f, cy + u * 0.22f)
            lineTo(cx + u * 0.03f, cy + u * 0.22f)
            close()
        }
        c.drawPath(p, bolt)
    }

    private fun fogBars(c: Canvas, cx: Float, cy: Float, u: Float) {
        cloud(c, cx, cy - u * 0.20f, u * 0.46f, cloud)
        for (i in 0 until 3) {
            val y = cy + u * 0.14f + i * u * 0.14f
            val half = u * (if (i == 1) 0.30f else 0.24f)
            c.drawLine(cx - half, y, cx + half, y, fog)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Drawable API", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = (26 * density).toInt()
    override fun getIntrinsicHeight(): Int = (26 * density).toInt()
}
