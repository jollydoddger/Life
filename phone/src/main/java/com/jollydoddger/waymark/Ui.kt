package com.jollydoddger.waymark

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.jollydoddger.waymark.shared.Glyph
import com.jollydoddger.waymark.shared.IconDrawable

/**
 * The one place the app's chrome is decided.
 *
 * Before this file existed there was no theme at all: roughly ten
 * near-identical dark greens and greys typed out as `Color.argb(...)` at
 * every call site, twelve ad-hoc text sizes, zero corner radius anywhere on
 * the map screen, no touch feedback on any custom button, and a *light*
 * stock theme underneath it all — so every dialog and spinner rendered in
 * bright white over a hand-painted dark app. "Everything feels scattered"
 * was the complaint, and scattered was the literal truth of how the styling
 * was written.
 *
 * Everything here is derived from the colours the app already used — the
 * forest green of the chips, the near-black greens of the bars — so the
 * app keeps its face and loses the inconsistency. The stock-widget half of
 * the fix lives in res/values/themes.xml, which pins the framework's dark
 * Material theme with the same greens.
 */
object Palette {
    // Surfaces, darkest to lightest.
    val bg = Color.rgb(18, 21, 19)
    val surface = Color.rgb(26, 30, 27)
    val raised = Color.rgb(38, 46, 42)
    /** Bottom bars and sheets over the map: near-opaque, never see-through
     *  enough for the map to fight the text. */
    val sheet = Color.argb(246, 22, 26, 24)
    /** Floating chips and readouts over the map: translucent on purpose —
     *  they sit on the map and should admit they do. */
    val scrim = Color.argb(205, 30, 34, 32)
    /** Hairline outlines on raised things. */
    val stroke = Color.argb(42, 255, 255, 255)

    // Ink.
    val ink = Color.WHITE
    val inkMut = Color.argb(220, 185, 196, 188)
    val inkFaint = Color.argb(170, 150, 160, 152)

    // Brand.
    val green = Color.argb(235, 34, 96, 58)
    val greenHi = Color.rgb(150, 210, 170)
    val record = Color.rgb(226, 52, 44)
    val warn = Color.rgb(214, 132, 12)
}

object Ui {

    // The type scale. Everything on screen uses one of these, so two
    // labels doing the same job can no longer be two different sizes.
    const val BIG = 26f
    const val TITLE = 17f
    const val BODY = 15f
    const val LABEL = 14f
    const val CAP = 13f
    const val MICRO = 11f

    // Shape.
    const val RADIUS_SHEET = 18
    const val RADIUS_CARD = 14

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** A fully-rounded lozenge — buttons, chips, the status line. */
    fun pill(ctx: Context, fill: Int, stroke: Int = Palette.stroke): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, 999).toFloat()
            if (Color.alpha(stroke) > 0) setStroke(dp(ctx, 1), stroke)
        }

    /** A rounded card; [topOnly] for a sheet that meets the screen edge. */
    fun card(
        ctx: Context,
        fill: Int = Palette.surface,
        radiusDp: Int = RADIUS_CARD,
        topOnly: Boolean = false,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        val r = dp(ctx, radiusDp).toFloat()
        cornerRadii = if (topOnly) {
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(r, r, r, r, r, r, r, r)
        }
    }

    /**
     * Touch feedback. The app had none anywhere: a press either did its
     * thing invisibly or did nothing invisibly, and the two were
     * indistinguishable at the moment of pressing — which is a large part
     * of what "doesn't feel like a pro app" is.
     */
    fun ripple(content: android.graphics.drawable.Drawable?): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(46, 255, 255, 255)),
            content,
            content ?: android.graphics.drawable.ColorDrawable(Color.WHITE),
        )

    /** A pill button. [filled] for the one primary action in a row. */
    fun button(ctx: Context, label: String, filled: Boolean = false, onTap: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = LABEL
            gravity = Gravity.CENTER
            setTextColor(Palette.ink)
            background = ripple(pill(ctx, if (filled) Palette.green else Palette.raised))
            setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
            minHeight = dp(ctx, 44)
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** A toggle chip: green when on, scrim when off. */
    fun chip(ctx: Context, label: String, on: Boolean, onTap: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = CAP
            setTextColor(if (on) Palette.ink else Palette.inkMut)
            background = ripple(
                pill(ctx, if (on) Palette.green else Palette.scrim, if (on) Color.TRANSPARENT else Palette.stroke),
            )
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** A round map-control button: the shared glyph disc, plus a ripple. */
    fun iconButton(ctx: Context, glyph: Glyph, sizeDp: Int = 52, onTap: () -> Unit): View =
        View(ctx).apply {
            val icon = IconDrawable(glyph, ctx.resources.displayMetrics.density)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(46, 255, 255, 255)),
                icon,
                // A circular mask, so the ripple honours the disc.
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
            )
            layoutParams = android.view.ViewGroup.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** The same, when the caller needs the drawable (the record button
     *  repaints its own glyph). */
    fun iconButton(ctx: Context, icon: IconDrawable, sizeDp: Int = 52, onTap: () -> Unit): View =
        View(ctx).apply {
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(46, 255, 255, 255)),
                icon,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
            )
            layoutParams = android.view.ViewGroup.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            isClickable = true
            setOnClickListener { onTap() }
        }

    /** A small caps section heading. */
    fun heading(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = MICRO
        letterSpacing = 0.08f
        setTextColor(Palette.inkFaint)
        setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 6))
    }

    fun label(ctx: Context, text: String, sp: Float = BODY, colour: Int = Palette.ink): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sp
            setTextColor(colour)
        }

    /**
     * Hold to talk, release to send — the finger coming off IS the send.
     * Extracted from the map screen so the chat screen can carry the same
     * mic instead of being the one place in the app that cannot listen.
     * [start] is responsible for the RECORD_AUDIO permission dance.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun bindHoldToTalk(view: View, start: () -> Unit, stop: () -> Unit) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    start()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    stop()
                    true
                }
                else -> false
            }
        }
    }
}
