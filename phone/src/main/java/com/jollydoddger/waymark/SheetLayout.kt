package com.jollydoddger.waymark

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The bottom sheet: the app's one interaction surface over the map.
 *
 * It replaces a stack of five separately shown-and-hidden bars (ask, reply,
 * picker, edit, weather), whose visibility was hand-managed from a dozen
 * call sites and whose restore-the-right-one dance was a standing source of
 * "why is the scrubber gone". A sheet holds one panel at a time: a slim
 * peek row that is always present, and a body revealed by swiping up or
 * tapping — the shape every maps app has settled on, because it keeps the
 * map on screen and the controls under the thumb.
 *
 * Hand-built on framework classes only; the project deliberately carries no
 * androidx, so there is no BottomSheetBehavior to lean on. Open and close
 * work by animating the body's height rather than translating the sheet —
 * translation fights the bottom inset (the nav-bar/keyboard padding lives
 * at the sheet's bottom edge, and pushing the sheet down pushes the padding
 * off screen before the body), while a height simply grows and shrinks
 * above padding that never moves.
 *
 * The map never fights it for touches: the sheet is a sibling drawn above
 * the map, so hit-testing gives it everything inside its own bounds and
 * nothing outside them. The map's own gesture handling is untouched.
 */
class SheetLayout(ctx: Context) : LinearLayout(ctx) {

    enum class State { HIDDEN, PEEK, OPEN }

    /** The always-visible summary row of the current panel. */
    val peekRow = FrameLayout(ctx)

    /** The panel body, revealed in [State.OPEN]. */
    val content = FrameLayout(ctx)

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
    }

    /** The body's full height, as constrained right now. */
    private fun fullContentHeight(): Int {
        if (width == 0) return 0
        // Never taller than 60% of the screen: it is a sheet over a map,
        // not a screen pretending to be one.
        val cap = ((parent as? View)?.height ?: height).let {
            if (it > 0) (it * 0.6f).toInt() else Int.MAX_VALUE / 2
        }
        content.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST),
        )
        return content.measuredHeight
    }

    fun setState(s: State, animate: Boolean = true) {
        if (s == state && anim == null) return
        state = s
        anim?.cancel()
        anim = null
        visibility = if (s == State.HIDDEN) GONE else VISIBLE
        if (s == State.HIDDEN) {
            onStateChanged?.invoke(s)
            return
        }
        val target = if (s == State.OPEN) fullContentHeight() else 0
        val lp = content.layoutParams
        if (!animate || !isLaidOut) {
            lp.height = target
            content.requestLayout()
            onStateChanged?.invoke(s)
            return
        }
        anim = ValueAnimator.ofInt(content.height, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                content.requestLayout()
            }
            start()
        }
        onStateChanged?.invoke(s)
    }

    /** A panel that changed size while open keeps fitting. */
    fun remeasureOpen() {
        if (state == State.OPEN && anim == null) {
            content.layoutParams.height = fullContentHeight()
            content.requestLayout()
        }
    }

    fun toggle() {
        setState(if (state == State.OPEN) State.PEEK else State.OPEN)
    }

    // --- dragging ----------------------------------------------------------
    //
    // The handle and the peek row drag the body open and shut, tracking the
    // finger; a release snaps to whichever state is nearer, or the way the
    // finger was moving. Drags that start inside the body are left alone —
    // the body holds sliders and scrolling text, and stealing a vertical
    // gesture from a SeekBar is how a scrubber becomes unusable.

    private var dragStartY = 0f
    private var dragStartHeight = 0
    private var dragging = false
    private var lastY = 0f
    private var lastDy = 0f

    private fun inGrabZone(y: Float): Boolean =
        y <= peekRow.bottom + Ui.dp(context, 4)

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
                    dragging = true
                    dragStartHeight = content.height
                    anim?.cancel()
                    anim = null
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
                dragStartHeight = content.height
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && inGrabZone(dragStartY) &&
                    kotlin.math.abs(ev.y - dragStartY) > ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    dragging = true
                    anim?.cancel()
                    anim = null
                }
                if (dragging) {
                    lastDy = ev.y - lastY
                    lastY = ev.y
                    val full = fullContentHeight()
                    val h = (dragStartHeight + (dragStartY - ev.y).toInt()).coerceIn(0, full)
                    content.layoutParams.height = h
                    content.requestLayout()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val full = fullContentHeight()
                    // The finger's last direction outranks position: a
                    // flick from nearly-open must close, because that is
                    // what the flick said.
                    val opening = when {
                        lastDy < -1f -> true
                        lastDy > 1f -> false
                        else -> content.height > full / 2
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
