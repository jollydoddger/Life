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

    fun post(url: String, body: String, contentType: String, timeoutMs: Int = 25_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
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

    fun overpass(query: String, timeoutMs: Int = 70_000): String {
        var lastMsg: String? = null
        overpassGate.acquire()
        try {
            for (host in OVERPASS) {
                for (attempt in 0 until 2) {
                    try {
                        return post(
                            host,
                            "data=" + encode(query),
                            "application/x-www-form-urlencoded",
                            timeoutMs,
                        )
                    } catch (e: Exception) {
                        lastMsg = e.message
                        // 429 means too fast, 504 means too big; both are
                        // worth one wait before moving on.
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
        throw RuntimeException(
            "OpenStreetMap's query servers all refused (${OVERPASS.size} tried): $lastMsg",
        )
    }
}
