package com.jollydoddger.waymark

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The bottom sheet: the app's one interaction surface over the map.
 *
 * A slim peek row that is always there, and a body underneath it. It
 * replaces five separately shown-and-hidden bars whose visibility was
 * hand-managed from a dozen call sites.
 *
 * The first version of this got the mechanism wrong in a way worth
 * recording, because the symptom was "bottom options not appearing" and
 * the cause was nowhere near the options. Open was a **pixel height I
 * measured and then had to keep correct**: measured on open, re-measured
 * by hand when a panel was swapped, animated between values, and mutated
 * again by every drag. Four separate things had to agree, and they did
 * not. The animator was never cleared when it finished, which silently
 * disabled the hand re-measure; a panel swapped while open then kept the
 * *previous* panel's height, and a body that measured zero — which the ask
 * panel does whenever the assistant is switched off, as it ships — left
 * the sheet stuck at zero for every panel afterwards. Nothing could be
 * drawn in it again.
 *
 * So the height is no longer mine to get right. **Open is
 * `WRAP_CONTENT`; closed is `0`.** The framework then does the resizing:
 * a panel swap, a reply arriving, a keyboard appearing all just work,
 * because nothing is remembering a number. The animation is decoration
 * over the top of that, and it hands the height back to `WRAP_CONTENT`
 * the moment it ends. The cap that stops the sheet eating the map lives
 * in [Capped.onMeasure], where a cap belongs, rather than in arithmetic
 * at the call site.
 *
 * Hand-built on framework classes only; the project deliberately carries
 * no androidx, so there is no BottomSheetBehavior to lean on. The map
 * never fights it for touches: the sheet is a sibling drawn above the
 * map, so hit-testing gives it everything inside its own bounds and
 * nothing outside them.
 */
class SheetLayout(ctx: Context) : LinearLayout(ctx) {

    enum class State { HIDDEN, PEEK, OPEN }

    companion object {
        /**
         * The body height a state means — the whole of the fix, in one
         * line, and deliberately a function rather than two literals
         * scattered through [setState] so nothing can drift from it.
         *
         * Open is `WRAP_CONTENT`: not a number anybody measured, computed
         * or has to keep correct. That is the difference between a panel
         * that resizes itself when it is swapped, grows a reply or meets
         * the keyboard, and a panel wearing a height taken from something
         * else entirely.
         */
        fun heightFor(s: State): Int =
            if (s == State.OPEN) ViewGroup.LayoutParams.WRAP_CONTENT else 0
    }

    /**
     * The body. A [FrameLayout] that refuses to grow past a share of the
     * screen — it is a sheet over a map, not a screen pretending to be
     * one — and that measures itself the rest of the time, so no caller
     * has to know or remember how tall its contents are.
     */
    class Capped(ctx: Context) : FrameLayout(ctx) {
        /** Room available to the whole sheet; 0 until the parent says. */
        var availablePx = 0

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, heightSpec)
            if (availablePx <= 0) return
            val cap = (availablePx * 0.6f).toInt()
            if (measuredHeight > cap) {
                setMeasuredDimension(measuredWidth, cap)
            }
        }
    }

    /** The always-visible summary row of the current panel. */
    val peekRow = FrameLayout(ctx)

    /** The panel body, revealed in [State.OPEN]. */
    val content = Capped(ctx)

    var state = State.PEEK
        private set

    var onStateChanged: ((State) -> Unit)? = null

    private val handle = View(ctx)
    private var anim: ValueAnimator? = null

    init {
        orientation = VERTICAL
        background = Ui.card(ctx, Palette.sheet, Ui.RADIUS_SHEET, topOnly = true)
        // The sheet eats its own touches; a tap between two buttons must
        // never zoom the map underneath.
        isClickable = true
        handle.background = Ui.pill(ctx, Palette.stroke, android.graphics.Color.TRANSPARENT)
        addView(
            handle,
            LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(ctx, 8)
                bottomMargin = Ui.dp(ctx, 6)
            },
        )
        addView(peekRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    /** System-bar or keyboard depth below the sheet; the panel rides on it. */
    fun setBottomInset(px: Int) {
        setPadding(0, 0, 0, px)
        // The cap is a share of what is actually free, so a tall reply with
        // the keyboard up can no longer push the ask box off the screen.
        content.availablePx = ((parent as? View)?.height ?: 0) - px
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        content.availablePx = ((parent as? View)?.height ?: 0) - paddingBottom
    }

    /**
     * Reconcile the sheet to [s]. Always — never an early return on
     * "already in that state". A drag moves the body's height without
     * touching [state], so the two are routinely out of step, and the
     * version that returned early left the sheet stuck at whatever height
     * a part-drag happened to end on, for good.
     */
    fun setState(s: State, animate: Boolean = true) {
        val was = state
        state = s
        anim?.cancel()
        anim = null
        visibility = if (s == State.HIDDEN) GONE else VISIBLE
        val lp = content.layoutParams
        if (s == State.HIDDEN) {
            if (was != s) onStateChanged?.invoke(s)
            return
        }
        val open = s == State.OPEN
        // Before the first layout there is nothing to measure and nothing
        // to animate — and measuring anyway is how "open" once resolved to
        // zero and stayed there.
        if (!animate || !isLaidOut) {
            lp.height = heightFor(s)
            content.requestLayout()
            if (was != s) onStateChanged?.invoke(s)
            return
        }
        val from = content.height
        val to = if (open) measuredOpenHeight() else 0
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                content.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    anim = null
                    // Hand the height back to the framework. Everything
                    // after this — a panel swap, a reply arriving, the
                    // keyboard — resizes without anyone remembering a
                    // number, which is the whole point.
                    lp.height = heightFor(s)
                    content.requestLayout()
                }
            })
            start()
        }
        if (was != s) onStateChanged?.invoke(s)
    }

    /** What the body wants right now — for the animation's target only. */
    private fun measuredOpenHeight(): Int {
        if (width == 0) return 0
        content.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return content.measuredHeight
    }

    fun toggle() {
        setState(if (state == State.OPEN) State.PEEK else State.OPEN)
    }

    // --- dragging ----------------------------------------------------------
    //
    // The handle and the peek row drag the body open and shut, tracking the
    // finger; a release snaps to whichever state the finger was heading
    // for. Drags that start inside the body are left alone — the body
    // holds sliders and scrolling text, and stealing a vertical gesture
    // from a SeekBar is how a scrubber becomes unusable.

    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var dragFullHeight = 0
    private var dragging = false
    private var lastY = 0f
    private var lastDy = 0f

    private fun inGrabZone(y: Float): Boolean =
        y <= peekRow.bottom + Ui.dp(context, 4)

    private fun beginDrag() {
        dragging = true
        anim?.cancel()
        anim = null
        dragStartHeight = content.height
        // Measured once, here — not on every touch sample, which was a
        // full measure pass per frame of a drag.
        dragFullHeight = maxOf(measuredOpenHeight(), dragStartHeight)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.y
                lastY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && inGrabZone(dragStartY) &&
                    kotlin.math.abs(ev.y - dragStartY) > ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    beginDrag()
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Everything inside the sheet's bounds is the sheet's, consumed or
        // not — a touch that slipped between two buttons must never reach
        // the map underneath and zoom it.
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.y
                lastY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && inGrabZone(dragStartY) &&
                    kotlin.math.abs(ev.y - dragStartY) > ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    beginDrag()
                }
                if (dragging) {
                    lastDy = ev.y - lastY
                    lastY = ev.y
                    content.layoutParams.height =
                        (dragStartHeight + (dragStartY - ev.y).toInt()).coerceIn(0, dragFullHeight)
                    content.requestLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    // The finger's last direction outranks position: a
                    // flick from nearly-open must close, because that is
                    // what the flick said.
                    val opening = when {
                        lastDy < -1f -> true
                        lastDy > 1f -> false
                        else -> content.height > dragFullHeight / 2
                    }
                    setState(if (opening) State.OPEN else State.PEEK)
                } else if (ev.actionMasked == MotionEvent.ACTION_UP &&
                    inGrabZone(dragStartY) &&
                    kotlin.math.abs(ev.y - dragStartY) < ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    // A plain tap on the handle or the peek row's quiet
                    // parts: the other state.
                    toggle()
                }
            }
        }
        return true
    }
}
