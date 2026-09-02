package com.jollydoddger.waymark

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Pages inside the bottom sheet, one showing at a time, swiped or tabbed.
 *
 * Hand-built because the project carries no androidx and so no ViewPager.
 * Deliberately simple: the pages are stacked in a frame and all but one
 * are GONE, so the sheet's `WRAP_CONTENT` body is exactly the height of
 * the page on show and nothing else — a hidden page cannot hold the sheet
 * open at the wrong size, which is the failure the sheet was rebuilt to
 * make impossible.
 *
 * A swipe is claimed only once it is plainly horizontal, so the buttons
 * and chips on a page still take their taps, and the sheet's own vertical
 * drag from the handle is untouched.
 */
class Pager(ctx: Context) : FrameLayout(ctx) {

    var index = 0
        private set

    /** Told after the page changes, whichever way it was asked for. */
    var onPage: ((Int) -> Unit)? = null

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    fun add(page: View) {
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        page.visibility = if (childCount - 1 == index) VISIBLE else GONE
    }

    fun show(i: Int, animate: Boolean = true) {
        val to = i.coerceIn(0, childCount - 1)
        if (childCount == 0) return
        val from = index
        index = to
        for (k in 0 until childCount) {
            val v = getChildAt(k)
            if (k == to) {
                v.visibility = VISIBLE
                if (animate && from != to && width > 0) {
                    v.alpha = 0f
                    v.translationX = (if (to > from) 1 else -1) * width * 0.18f
                    v.animate().alpha(1f).translationX(0f).setDuration(180).start()
                } else {
                    v.alpha = 1f
                    v.translationX = 0f
                }
            } else {
                v.animate().cancel()
                v.visibility = GONE
            }
        }
        requestLayout()
        onPage?.invoke(to)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Horizontal beyond doubt before it is taken from a button:
                // a thumb wandering sideways while pressing must not turn
                // into a page flip.
                if (!swiping && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2) {
                    swiping = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!swiping && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2) swiping = true
            }
            MotionEvent.ACTION_UP -> {
                if (swiping) {
                    val dx = ev.x - downX
                    if (dx < -slop) show(index + 1) else if (dx > slop) show(index - 1)
                }
                swiping = false
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}
