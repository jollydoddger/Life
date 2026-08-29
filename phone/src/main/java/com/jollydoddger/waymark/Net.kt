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
            if (code != 200) throw RuntimeException("HTTP $code from ${URL(url).host}")
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
            if (code != 200) throw RuntimeException("HTTP $code from ${URL(url).host}")
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

    /** A small binary fetch — radar tiles, nothing else so far. */
    fun getBytes(url: String, timeoutMs: Int = 20_000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        try {
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code from ${URL(url).host}")
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
            if (code != 200) throw RuntimeException("HTTP $code from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            conn.disconnect()
        }
    }
}
