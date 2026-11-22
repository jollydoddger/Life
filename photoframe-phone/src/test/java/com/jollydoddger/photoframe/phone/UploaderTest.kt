package com.jollydoddger.photoframe.phone

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.URLDecoder

/** The uploader against a stand-in for the TV that speaks the same /api/upload contract. */
class UploaderTest {
    private lateinit var server: ServerSocket
    private var query: Map<String, String> = emptyMap()
    private var body = ByteArray(0)
    private var reply: Pair<Int, String> = 200 to """{"ok":true,"path":"Holidays/IMG 1.jpg"}"""

    /** A one-request-at-a-time HTTP stand-in for the TV. */
    @Before fun setUp() {
        server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s ->
                    val inp = s.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; sb.append(b.toChar()) }; return sb.toString().trimEnd('\r') }
                    val target = line().split(" ")[1]
                    var len = 0
                    while (true) { val h = line(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt() }
                    query = target.substringAfter('?').split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    body = ByteArray(len).also { b -> var o = 0; while (o < len) { val n = inp.read(b, o, len - o); if (n < 0) break; o += n } }
                    val r = reply.second.toByteArray()
                    s.getOutputStream().apply {
                        write("HTTP/1.1 ${reply.first} X\r\nContent-Type: application/json\r\nContent-Length: ${r.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(r); flush()
                    }
                }
            }
        }.start()
    }

    @After fun tearDown() = server.close()

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Test fun sendsFileAndNames() {
        val data = ByteArray(3_500_000) { (it % 253).toByte() }
        val progress = ArrayList<Long>()
        val path = Uploader(base).upload(data.inputStream(), data.size.toLong(), "IMG 1 & co+.jpg", "Holidays 2026", 1234L) { progress += it }
        assertEquals("Holidays/IMG 1.jpg", path)
        assertArrayEquals(data, body)
        assertEquals("IMG 1 & co+.jpg", query["name"])
        assertEquals("Holidays 2026", query["album"])
        assertEquals("1234", query["mtime"])
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test fun tvErrorMessageComesThrough() {
        reply = 507 to """{"error":"Not enough space on the storage"}"""
        try {
            Uploader(base).upload(byteArrayOf(1, 2, 3).inputStream(), 3, "a.jpg", "", 0)
            fail()
        } catch (e: IOException) {
            assertEquals("Not enough space on the storage", e.message)
        }
    }

    @Test fun normaliseAddresses() {
        assertEquals("http://192.168.1.20:8080", Tv.normalise("192.168.1.20"))
        assertEquals("http://192.168.1.20:8080", Tv.normalise(" http://192.168.1.20:8080/ "))
        assertEquals("http://192.168.1.20:9000", Tv.normalise("192.168.1.20:9000"))
        assertNull(Tv.normalise(""))
        assertNull(Tv.normalise("192.168.1.20:abc"))
        assertNull(Tv.normalise("http://x/y"))
    }
}
