package com.jollydoddger.waymark.shared

import android.graphics.Color

/**
 * The pickable colours. A fixed palette rather than a colour wheel: six
 * choices are one glanceable row of swatches that can be hit with a gloved
 * thumb, and every one of them is legible against pale OS map paper — which
 * a free-choice picker cannot promise (pick pale yellow and the route
 * vanishes into a field).
 */
object Colours {
    val DEFAULT_ROUTE = Color.argb(220, 30, 98, 208)   // blue
    val DEFAULT_ARROW = Color.argb(240, 30, 98, 208)   // blue
    val DEFAULT_TRAIL = Color.argb(200, 220, 40, 160)  // magenta

    /** label to colour, in swatch order. */
    val PALETTE: List<Pair<String, Int>> = listOf(
        "Blue" to Color.argb(230, 30, 98, 208),
        "Red" to Color.argb(230, 214, 40, 40),
        "Magenta" to Color.argb(230, 220, 40, 160),
        "Orange" to Color.argb(230, 240, 130, 20),
        "Green" to Color.argb(230, 20, 140, 70),
        "Black" to Color.argb(230, 20, 20, 20),
    )
}
