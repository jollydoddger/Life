package com.jollydoddger.waymark

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * "The assistant call failed: Request failed" — what he was actually shown,
 * twice, with the assistant unusable and nothing to act on.
 *
 * The Anthropic SDK reports a dropped connection as an exception whose own
 * message is that bare phrase and puts the real reason in the *cause*. The
 * app read only the top layer, so the message was useless AND every branch
 * of the translator was matching against text one level below where it was
 * looking. These tests hold the fix to the failures he actually hit.
 */
class AssistantErrorTest {

    /** The shape the SDK throws: a useless outer message over a real cause. */
    private fun wrapped(cause: Throwable) = RuntimeException("Request failed", cause)

    @Test
    fun `a dropped connection is named as a connection problem, not as Request failed`() {
        val said = Assistant.explain(wrapped(UnknownHostException("api.anthropic.com")))
        assertTrue("must not parrot the SDK's own shrug", "Request failed" !in said)
        assertTrue("must name the actual problem", "offline" in said || "signal" in said)
    }

    @Test
    fun `a timeout says so, through the wrapper`() {
        val said = Assistant.explain(wrapped(SocketTimeoutException("timeout")))
        assertTrue(said, "time" in said.lowercase())
        assertTrue("Request failed" !in said)
    }

    @Test
    fun `a rejected key is still spotted when it is buried a layer down`() {
        val said = Assistant.explain(wrapped(IOException("HTTP 401 authentication_error")))
        assertTrue(said, "key" in said)
    }

    @Test
    fun `an unrecognised failure hands back the whole chain, not a shrug`() {
        // He can read an ugly chain out to me. He cannot do anything with
        // "something went wrong".
        val said = Assistant.explain(wrapped(IllegalStateException("kaboom in the widget")))
        assertTrue("keeps the detail", "kaboom in the widget" in said)
        assertTrue("and the layer above it", "Request failed" in said)
    }

    @Test
    fun `the chain reads outermost first and does not run away on a deep stack`() {
        val chain = Assistant.chain(wrapped(UnknownHostException("no dns")))
        assertTrue(chain, chain.indexOf("Request failed") < chain.indexOf("no dns"))

        // Wrapped twenty deep: bounded, so a pathological stack can never
        // put a paragraph of Java class names on his screen.
        var deep: Throwable = IllegalStateException("the root")
        repeat(20) { deep = RuntimeException("layer $it", deep) }
        val long = Assistant.chain(deep)
        assertTrue("bounded", long.split(" ← ").size <= 8)
    }

    @Test
    fun `an overloaded server is told apart from a broken key`() {
        val busy = Assistant.explain(wrapped(IOException("HTTP 529 overloaded_error")))
        assertTrue(busy, "busy" in busy)
        assertTrue("not blamed on the key", "key" !in busy)
    }
}
