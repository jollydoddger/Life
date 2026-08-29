package com.jollydoddger.waymark.shared

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** The buttons this app has. */
enum class Glyph { LOCATE, RECORD, REVERSE, ROUTE, DOWNLOAD, SUN, SETTINGS, MIC, SEND }

/**
 * The map controls, drawn rather than typed.
 *
 * They began as text glyphs (◉ ● ⇄) which render at the mercy of whatever
 * font the phone picks — thin, small, and different on every device. These are
 * Canvas primitives instead: circles, arcs and triangles at known sizes, so a
 * crosshair is a crosshair and the record button reads as a record button on
 * any screen. Each draws its own dark disc, because a control has to be found
 * against pale OS map paper in daylight.
 */
class IconDrawable(
    private val glyph: Glyph,
    private val density: Float,
) : Drawable() {

    /** Recording, for RECORD: a red disc becomes a stop square. */
    var active: Boolean = false
        set(value) {
            field = value
            invalidateSelf()
        }

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 22, 24, 23)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 255, 255, 255)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()
    private val box = RectF()

    private val red = Color.rgb(226, 52, 44)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f - 1f * density

        canvas.drawCircle(cx, cy, r, disc)
        ring.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, r - 0.5f * density, ring)

        // Glyphs are drawn inside this radius, so every button reads at the
        // same visual weight whatever its shape.
        val g = r * 0.56f
        line.strokeWidth = 2f * density
        fill.color = Color.WHITE

        when (glyph) {
            Glyph.LOCATE -> {
                // The classic crosshair: ring, ticks on the axes, solid centre.
                canvas.drawCircle(cx, cy, g * 0.62f, line)
                canvas.drawCircle(cx, cy, g * 0.2f, fill)
                for (k in 0..3) {
                    val dx = if (k == 0) 1f else if (k == 1) -1f else 0f
                    val dy = if (k == 2) 1f else if (k == 3) -1f else 0f
                    canvas.drawLine(
                        cx + dx * g * 0.78f, cy + dy * g * 0.78f,
                        cx + dx * g * 1.15f, cy + dy * g * 1.15f, line,
                    )
                }
            }

            Glyph.RECORD -> {
                if (active) {
                    // Stop: the square everyone reads as "end the recording".
                    fill.color = red
                    box.set(cx - g * 0.62f, cy - g * 0.62f, cx + g * 0.62f, cy + g * 0.62f)
                    canvas.drawRoundRect(box, 2f * density, 2f * density, fill)
                } else {
                    fill.color = red
                    canvas.drawCircle(cx, cy, g * 0.72f, fill)
                    ring.strokeWidth = 1.5f * density
                    canvas.drawCircle(cx, cy, g * 0.72f, ring)
                }
            }

            Glyph.REVERSE -> {
                // Two arrows passing each other — the route, walked either way.
                val s = g * 0.95f
                val gap = g * 0.42f
                arrow(canvas, cx - s, cy - gap, cx + s, cy - gap, g * 0.42f)
                arrow(canvas, cx + s, cy + gap, cx - s, cy + gap, g * 0.42f)
            }

            Glyph.ROUTE -> {
                // A path between two waypoints: what a GPX actually is.
                path.rewind()
                path.moveTo(cx - g * 0.85f, cy + g * 0.75f)
                path.cubicTo(
                    cx + g * 0.4f, cy + g * 0.55f,
                    cx - g * 0.6f, cy - g * 0.55f,
                    cx + g * 0.85f, cy - g * 0.75f,
                )
                canvas.drawPath(path, line)
                canvas.drawCircle(cx - g * 0.85f, cy + g * 0.75f, g * 0.26f, fill)
                canvas.drawCircle(cx + g * 0.85f, cy - g * 0.75f, g * 0.26f, fill)
            }

            Glyph.DOWNLOAD -> {
                // An arrow dropping into a tray: save this for offline.
                canvas.drawLine(cx, cy - g * 0.95f, cx, cy + g * 0.25f, line)
                path.rewind()
                path.moveTo(cx, cy + g * 0.55f)
                path.lineTo(cx - g * 0.5f, cy - g * 0.05f)
                path.lineTo(cx + g * 0.5f, cy - g * 0.05f)
                path.close()
                canvas.drawPath(path, fill)
                path.rewind()
                path.moveTo(cx - g * 0.9f, cy + g * 0.45f)
                path.lineTo(cx - g * 0.9f, cy + g * 0.95f)
                path.lineTo(cx + g * 0.9f, cy + g * 0.95f)
                path.lineTo(cx + g * 0.9f, cy + g * 0.45f)
                canvas.drawPath(path, line)
            }

            Glyph.SUN -> {
                // A disc with rays: where the sun is and where it is going.
                canvas.drawCircle(cx, cy, g * 0.45f, fill)
                line.strokeWidth = 1.8f * density
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val sx = (cx + kotlin.math.sin(a) * g * 0.72f).toFloat()
                    val sy = (cy - kotlin.math.cos(a) * g * 0.72f).toFloat()
                    val ex = (cx + kotlin.math.sin(a) * g * 1.08f).toFloat()
                    val ey = (cy - kotlin.math.cos(a) * g * 1.08f).toFloat()
                    canvas.drawLine(sx, sy, ex, ey, line)
                }
                line.strokeWidth = 2f * density
            }

            Glyph.SETTINGS -> {
                // A cog: eight teeth round a ring, drawn by rotating the canvas
                // rather than by trusting hand-written path data.
                canvas.save()
                for (k in 0 until 8) {
                    box.set(
                        cx - g * 0.17f, cy - g * 1.15f,
                        cx + g * 0.17f, cy - g * 0.62f,
                    )
                    canvas.drawRoundRect(box, g * 0.1f, g * 0.1f, fill)
                    canvas.rotate(45f, cx, cy)
                }
                canvas.restore()
                line.strokeWidth = 2.4f * density
                canvas.drawCircle(cx, cy, g * 0.62f, line)
                line.strokeWidth = 2f * density
            }

            Glyph.MIC -> {
                // Capsule body, cradle arc, stem: unmistakably a microphone.
                box.set(cx - g * 0.34f, cy - g * 1.0f, cx + g * 0.34f, cy + g * 0.16f)
                canvas.drawRoundRect(box, g * 0.34f, g * 0.34f, fill)
                box.set(cx - g * 0.68f, cy - g * 0.5f, cx + g * 0.68f, cy + g * 0.62f)
                canvas.drawArc(box, 10f, 160f, false, line)
                canvas.drawLine(cx, cy + g * 0.62f, cx, cy + g * 1.05f, line)
            }

            Glyph.SEND -> {
                // A paper dart, pointing the way the words go.
                path.rewind()
                path.moveTo(cx - g * 0.9f, cy - g * 0.85f)
                path.lineTo(cx + g * 1.0f, cy)
                path.lineTo(cx - g * 0.9f, cy + g * 0.85f)
                path.lineTo(cx - g * 0.5f, cy)
                path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

    /** A line with a solid head, pointing from (x1,y1) to (x2,y2). */
    private fun arrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, head: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = kotlin.math.hypot(dx, dy)
        if (len <= 0f) return
        val ux = dx / len
        val uy = dy / len
        canvas.drawLine(x1, y1, x2 - ux * head * 0.8f, y2 - uy * head * 0.8f, line)
        path.rewind()
        path.moveTo(x2, y2)
        path.lineTo(x2 - ux * head - uy * head * 0.55f, y2 - uy * head + ux * head * 0.55f)
        path.lineTo(x2 - ux * head + uy * head * 0.55f, y2 - uy * head - ux * head * 0.55f)
        path.close()
        canvas.drawPath(path, fill)
    }

    override fun setAlpha(alpha: Int) {
        disc.alpha = alpha
        line.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        line.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable, but still abstract")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
