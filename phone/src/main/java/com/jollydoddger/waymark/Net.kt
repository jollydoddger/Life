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
