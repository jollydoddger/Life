package com.jollydoddger.waymark

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The one HTTP door for the assistant's data sources. Nominatim and the
 * FOSSGIS router require an honest User-Agent (anonymous clients get blocked),
 * and every free service here deserves the same courtesy.
 */
object Net {
    private const val UA = "Waymark/0.1 (personal walking app; github.com/jollydoddger/Life)"

    fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")

    fun get(url: String, timeoutMs: Int = 15_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpError(code, URL(url).host)
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Hand the response body to [block] as a stream rather than a String. A
     * county's rights of way run to many megabytes; holding that as text and
     * then again as a parse tree is how a phone runs out of memory.
     */
    fun <T> stream(url: String, timeoutMs: Int = 60_000, block: (java.io.InputStream) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpError(code, URL(url).host)
            val raw = conn.inputStream
            val body = if (conn.contentEncoding?.contains("gzip", true) == true) {
                java.util.zip.GZIPInputStream(raw)
            } else {
                raw
            }
            return body.use(block)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * A server that answered, and said no. Distinct from a dropped
     * connection on purpose: a 404 means *this URL* is wrong and will stay
     * wrong, while a timeout on a hill means nothing about the URL at all.
     * Anything that decides to stop asking for something needs to know which
     * of those it just saw.
     */
    class HttpError(val code: Int, val host: String) : RuntimeException("HTTP $code from $host")

    /** A small binary fetch — radar tiles, nothing else so far. */
    fun getBytes(url: String, timeoutMs: Int = 20_000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpError(code, URL(url).host)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * How long to wait for a server to *answer at all*, as opposed to how
     * long to wait for what it is sending.
     *
     * These were one number, and Overpass passes 70 s for the read — so an
     * unreachable mirror spent seventy seconds failing to connect, twice,
     * on each of three hosts. Up to seven minutes of nothing before the
     * error appeared. A host either picks up the phone in a few seconds or
     * it is not there; the long wait belongs to the megabytes afterwards.
     */
    private const val CONNECT_MS = 8_000

    fun post(url: String, body: String, contentType: String, timeoutMs: Int = 25_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(CONNECT_MS, timeoutMs)
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", contentType)
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw HttpError(code, URL(url).host)
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The same POST, handing the reply back as a stream.
     *
     * Overpass replies are the largest thing this app ever reads, and
     * [post] holds every byte twice — once as the byte array and again as
     * the String — before a parser has even seen them. A tree parser then
     * turns each `{"lat":…,"lon":…}` into an object, a map and two boxed
     * Doubles: roughly two hundred bytes for twenty-four bytes of text.
     * That is how a phone with a 256 MB heap dies on a reply it could
     * happily have walked through.
     *
     * [block] failing with an [java.io.IOException] is a transport
     * failure — the connection died mid-reply — and is left to the caller's
     * retry. Anything else it throws is this app's own fault and is wrapped
     * in [Fatal], so a bug in a parser is never reported as a server
     * refusing.
     */
    fun <T> postStream(
        url: String,
        body: String,
        contentType: String,
        timeoutMs: Int = 25_000,
        block: (java.io.InputStream) -> T,
    ): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = minOf(CONNECT_MS, timeoutMs)
        conn.readTimeout = timeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw HttpError(code, URL(url).host)
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                java.util.zip.GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.use {
                try {
                    block(it)
                } catch (e: java.io.IOException) {
                    throw e
                } catch (e: Throwable) {
                    throw Fatal(e)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Something that went wrong on this side of the wire. Carried through
     * the mirror loop untouched and rethrown as its cause, because trying
     * two more servers cannot fix it and blaming them for it is a lie.
     */
    class Fatal(cause: Throwable) : RuntimeException(cause)

    /**
     * A server that answered, and said it had given up.
     *
     * Overpass does not fail a slow query with a 5xx — it returns HTTP 200
     * carrying a `remark` and no data. That is a refusal wearing the
     * clothes of an answer, and the difference matters more than it looks:
     * treated as an answer it means "there is nothing round here", which
     * this app then cached and repeated all afternoon.
     *
     * An IOException so the mirror loop counts it as transport, and one
     * this loop moves on from rather than retrying — a server too busy to
     * finish in sixty seconds will be too busy again in another sixty, and
     * the next mirror is the better question.
     */
    class GaveUp(val remark: String) : java.io.IOException("gave up: $remark")

    /**
     * Overpass, with somewhere else to go when the main server says no.
     *
     * The public instance at overpass-api.de rate-limits hard and drops
     * connections outright under load, and this app can have the router, the
     * rights-of-way overlay and the all-paths overlay all asking at once. A
     * dropped connection then reads to the user as "there are no paths here",
     * which is a lie about the ground. So: a small queue so we never open more
     * than two at a time, one retry each, and two mirrors behind the main
     * server before giving up.
     */
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** At most two Overpass requests in flight from this app, ever. */
    private val overpassGate = java.util.concurrent.Semaphore(2, true)

    /**
     * Whether a failure is the *connection* failing rather than a server
     * answering. A host that cannot be resolved or reached will not be
     * reached by asking again a second and a half later, and when every
     * host fails this way the honest diagnosis is about his phone, not
     * about OpenStreetMap.
     */
    private fun isUnreachable(e: Exception): Boolean = when (e) {
        is HttpError -> false
        is java.net.UnknownHostException -> true
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        is java.net.NoRouteToHostException -> true
        else -> false
    }

    /** The short form of what went wrong with one host. */
    private fun why(e: Exception): String = when (e) {
        is HttpError -> "HTTP ${e.code}"
        is GaveUp -> "too busy to finish"
        is java.net.UnknownHostException -> "no DNS"
        is java.net.ConnectException -> "refused the connection"
        is java.net.SocketTimeoutException -> "timed out"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * What to tell him when nothing answered. Pure, so the wording can be
     * tested — and it needed testing: the version he was shown named only
     * the *last* mirror's failure, so three different problems arrived as
     * one unresolvable hostname, which sounded like a bug in the app rather
     * than a bad signal on a hillside.
     */
    fun overpassFailure(reasons: List<Pair<String, String>>, allUnreachable: Boolean): String {
        val detail = reasons.joinToString(", ") { "${it.first} ${it.second}" }
        return if (allUnreachable) {
            "No connection to OpenStreetMap — you look to be offline or on a very " +
                "poor signal. Nothing answered ($detail)."
        } else {
            "OpenStreetMap's query servers all refused ($detail)."
        }
    }

    /**
     * Ask each mirror in turn until one answers, and hand the reply to
     * [call]. The loop is shared so the streaming and string forms cannot
     * drift apart on which failures are worth a second go.
     */
    private fun <T> overpassCall(call: (String) -> T): T {
        val reasons = ArrayList<Pair<String, String>>()
        var allUnreachable = true
        overpassGate.acquire()
        try {
            for (url in OVERPASS) {
                val host = URL(url).host
                for (attempt in 0 until 2) {
                    try {
                        return call(url)
                    } catch (f: Fatal) {
                        // Our bug, not theirs. Two more servers will make
                        // the same mistake and the message would name them.
                        throw f.cause ?: f
                    } catch (e: Exception) {
                        if (attempt == 0) reasons.add(host to why(e))
                        if (!isUnreachable(e)) allUnreachable = false
                        // 429 means too fast and 504 means too big: both are
                        // worth one wait and a second go. A host that isn't
                        // there will still not be there in a second and a
                        // half, and asking again only spends his afternoon —
                        // and neither will one that just spent sixty seconds
                        // failing to finish the same query.
                        if (isUnreachable(e) || e is GaveUp) break
                        try {
                            Thread.sleep(1_500L * (attempt + 1))
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
        } finally {
            overpassGate.release()
        }
        throw RuntimeException(overpassFailure(reasons, allUnreachable))
    }

    fun overpass(query: String, timeoutMs: Int = 70_000): String =
        overpassCall { url ->
            post(url, "data=" + encode(query), "application/x-www-form-urlencoded", timeoutMs)
        }

    /**
     * The same query, read as a stream. Prefer this for anything that can
     * come back large — every `out geom` query in this app can.
     */
    fun <T> overpassStream(query: String, timeoutMs: Int = 70_000, block: (java.io.InputStream) -> T): T =
        overpassCall { url ->
            postStream(url, "data=" + encode(query), "application/x-www-form-urlencoded", timeoutMs, block)
        }
}
