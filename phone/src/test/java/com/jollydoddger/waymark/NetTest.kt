package com.jollydoddger.waymark

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of a total network failure, because he was shown the wrong
 * one and it cost him a diagnosis.
 *
 * Three Overpass mirrors failed for three different reasons and the app
 * reported only the last: "Unable to resolve host overpass.private.coffee".
 * That sounds like a broken app — a wrong hostname somebody typed in — and
 * it was actually a phone with no usable connection. The message has to name
 * every host it tried and lead with the cause that is actually actionable.
 */
class NetTest {

    @Test
    fun `every host that failed is named, not just the last one`() {
        val msg = Net.overpassFailure(
            listOf(
                "overpass-api.de" to "HTTP 429",
                "overpass.kumi.systems" to "timed out",
                "overpass.private.coffee" to "no DNS",
            ),
            allUnreachable = false,
        )
        assertTrue("names the first", "overpass-api.de HTTP 429" in msg)
        assertTrue("names the second", "kumi.systems timed out" in msg)
        assertTrue("names the third", "private.coffee no DNS" in msg)
    }

    @Test
    fun `nothing reachable at all is described as his connection, not their servers`() {
        val msg = Net.overpassFailure(
            listOf("overpass-api.de" to "timed out", "overpass.kumi.systems" to "no DNS"),
            allUnreachable = true,
        )
        assertTrue("leads with the actionable cause", msg.startsWith("No connection"))
        assertTrue("and says what it means", "offline" in msg || "poor signal" in msg)
        // Never blame the servers for a failure they were never asked about.
        assertTrue("does not accuse the servers", "refused" !in msg)
    }

    @Test
    fun `a server that answered and said no is their refusal, not his signal`() {
        val msg = Net.overpassFailure(
            listOf("overpass-api.de" to "HTTP 429"),
            allUnreachable = false,
        )
        assertTrue("refused" in msg)
        assertTrue("offline" !in msg)
    }
}
