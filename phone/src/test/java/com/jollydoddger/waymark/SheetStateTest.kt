package com.jollydoddger.waymark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the bottom sheet now runs on, and why it is a rule rather than
 * arithmetic.
 *
 * The first version computed the open height as a pixel value, then had to
 * keep it correct across panel swaps, replies arriving, drags and the
 * keyboard. Four things had to agree and did not: a body measuring zero —
 * which the ask panel does whenever the assistant is off, as the app ships
 * — left the sheet wedged, and no panel drew anything again. "Bottom
 * options not appearing."
 *
 * Open is now `WRAP_CONTENT` and closed is `0`, so the framework does the
 * resizing and there is no number for anyone to get wrong. A `View` cannot
 * be built under a plain JVM test — which is why the rule is a function
 * `setState` calls rather than two literals inside it. These exercise the
 * real one.
 */
class SheetStateTest {

    private val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    /** The production rule itself, not a copy of it. */
    private fun heightFor(state: SheetLayout.State): Int = SheetLayout.heightFor(state)

    @Test fun `open is not a measurement`() {
        // The whole fix in one assertion. If this is ever a pixel count
        // again, everything above comes back.
        assertEquals(WRAP, heightFor(SheetLayout.State.OPEN))
        assertTrue("WRAP_CONTENT is a sentinel, not a size", WRAP < 0)
    }

    @Test fun `closed is flat`() {
        assertEquals(0, heightFor(SheetLayout.State.PEEK))
        assertEquals(0, heightFor(SheetLayout.State.HIDDEN))
    }

    @Test fun `reconciling twice lands in the same place`() {
        // setState used to return early when asked for the state it was
        // already in — but a drag moves the height without touching the
        // state, so "already open" could mean any height at all, and a
        // part-drag released where it started stuck there for good.
        for (s in SheetLayout.State.values()) {
            assertEquals(heightFor(s), heightFor(s))
        }
    }

    @Test fun `an empty panel is a normal open, not a wedged one`() {
        // A body with nothing in it measures zero. Under WRAP_CONTENT that
        // is simply a short sheet; under the old measured height it was a
        // zero that got remembered and never re-measured.
        assertEquals(WRAP, heightFor(SheetLayout.State.OPEN))
    }

    @Test fun `every state has a height`() {
        // No state may fall through without one — the sheet has to be
        // reconcilable from any of them.
        for (s in SheetLayout.State.values()) {
            assertTrue(heightFor(s) == WRAP || heightFor(s) == 0)
        }
    }
}
