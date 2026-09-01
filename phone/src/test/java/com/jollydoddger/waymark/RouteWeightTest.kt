package com.jollydoddger.waymark

import com.jollydoddger.waymark.shared.BngMapView.RouteWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial that lets the map be read through the route.
 *
 * Two things worth holding: a stored ordinal can outlive the list it was
 * an index into, and the steps have to actually go somewhere — a "faint"
 * that is barely lighter than "solid" is a menu item that does nothing.
 */
class RouteWeightTest {

    @Test fun `the stored ordinal cannot fall off the end`() {
        // The failure this guards against is a phone holding the last step
        // after a step is removed, and drawing nothing at all.
        for (i in -5..20) {
            assertTrue("$i must resolve to something", RouteWeight.of(i).ink > 0f)
        }
    }

    @Test fun `cycling comes back round`() {
        val all = RouteWeight.values()
        assertEquals(RouteWeight.SOLID, RouteWeight.of(0))
        assertEquals(RouteWeight.SOLID, RouteWeight.of(all.size))
        assertEquals(all.last(), RouteWeight.of(-1))
    }

    @Test fun `each step really is lighter than the one before`() {
        val all = RouteWeight.values()
        for (i in 1 until all.size) {
            assertTrue(
                "${all[i]} must be lighter than ${all[i - 1]}",
                all[i].ink < all[i - 1].ink && all[i].casing <= all[i - 1].casing,
            )
            assertTrue("${all[i]} must be no wider", all[i].width <= all[i - 1].width)
        }
    }

    @Test fun `the white casing is gone by the lightest step`() {
        // The casing is the part doing most of the hiding — a nine-point
        // white band under the line covers the right-of-way symbol the
        // route is being checked against. Fading the line while leaving
        // the casing solid would answer none of the question.
        assertEquals(0f, RouteWeight.values().last().casing, 0f)
    }

    @Test fun `even the lightest line is still visible`() {
        // Faint is not off. There is already a Hide the route for that,
        // and a line at five per cent would be the same thing wearing a
        // different name.
        assertTrue(RouteWeight.values().last().ink >= 0.3f)
        assertTrue(RouteWeight.values().last().width >= 3f)
    }
}
