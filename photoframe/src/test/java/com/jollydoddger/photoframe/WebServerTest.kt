package com.jollydoddger.photoframe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/** Runs the real server on a random port against a temp folder standing in for the USB stick. */
class WebServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var server: WebServer

    @Before fun setUp() {
        root = tmp.newFolder("PhotoFrame")
        MediaLibrary.useRoot(root)
        server = WebServer(page = { "<html>frame</html>".toByteArray() }, thumbDir = tmp.newFolder("thumbs"), port = 0)
        server.start()
        val until = System.currentTimeMillis() + 5000
        while (server.boundPort <= 0 && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @After fun tearDown() = server.stop()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private class Res(val code: Int, val body: String)

    private fun call(method: String, path: String, body: ByteArray? = null): Res {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
        return Res(code, text)
    }

    @Test fun servesThePage() {
        val r = call("GET", "/")
        assertEquals(200, r.code)
        assertEquals("<html>frame</html>", r.body)
    }

    @Test fun uploadLandsInAlbumWithOriginalDate() {
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val mtime = 1_600_000_000_000L
        val r = call("POST", "/api/upload?album=${enc("Holidays 2026")}&name=${enc("IMG 1.jpg")}&mtime=$mtime", bytes)
        assertEquals(r.body, 200, r.code)
        val f = File(root, "Holidays 2026/IMG 1.jpg")
        assertArrayEquals(bytes, f.readBytes())
        assertEquals(mtime, f.lastModified())
        assertEquals("Holidays 2026/IMG 1.jpg", JSONObject(r.body).getString("path"))
        assertTrue("no .part left behind", File(root, "Holidays 2026").listFiles()!!.none { it.name.endsWith(".part") })

        // Same name again gets a suffix, not an overwrite
        val r2 = call("POST", "/api/upload?album=${enc("Holidays 2026")}&name=${enc("IMG 1.jpg")}", byteArrayOf(1, 2, 3))
        assertEquals("Holidays 2026/IMG 1 (1).jpg", JSONObject(r2.body).getString("path"))

        val media = JSONArray(call("GET", "/api/media").body)
        assertEquals(2, media.length())
        val albums = JSONArray(call("GET", "/api/albums").body)
        assertEquals("Holidays 2026", albums.getJSONObject(1).getString("name"))
        assertEquals(2, albums.getJSONObject(1).getInt("count"))
    }

    @Test fun refusesNonMedia() {
        assertEquals(415, call("POST", "/api/upload?name=evil.apk", byteArrayOf(1)).code)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun pathTraversalIsBlocked() {
        val secret = File(tmp.root, "secret.jpg").apply { writeText("x") }
        assertEquals(404, call("GET", "/api/file?path=${enc("../secret.jpg")}").code)
        assertEquals(404, call("POST", "/api/delete?path=${enc("../secret.jpg")}").code)
        assertTrue(secret.exists())
        // An album name can't climb out either
        call("POST", "/api/upload?album=${enc("../../x")}&name=a.jpg", byteArrayOf(1))
        assertFalse(File(tmp.root, "x").exists())
        assertTrue(root.walk().any { it.name == "a.jpg" })
    }

    @Test fun deleteFileThenEmptyAlbum() {
        call("POST", "/api/upload?album=A&name=p.png", byteArrayOf(1, 2))
        assertEquals(409, call("POST", "/api/delete?path=A").code)
        assertEquals(200, call("POST", "/api/delete?path=${enc("A/p.png")}").code)
        assertEquals(200, call("POST", "/api/delete?path=A").code)
        assertFalse(File(root, "A").exists())
    }

    @Test fun settingsPatch() {
        val r = call("POST", "/api/settings", """{"mode":"wall","intervalSec":20}""".toByteArray())
        assertEquals(200, r.code)
        val s = JSONObject(call("GET", "/api/settings").body)
        assertEquals("wall", s.getString("mode"))
        assertEquals(20, s.getInt("intervalSec"))
        call("POST", "/api/settings", """{"mode":"slideshow","intervalSec":12}""".toByteArray())
    }

    @Test fun unknownControlRefused() {
        assertEquals(400, call("POST", "/api/control?action=selfdestruct").code)
        assertEquals(200, call("POST", "/api/control?action=next").code)
    }

    @Test fun badPercentEscapeStillAnswers() {
        assertEquals(404, call("GET", "/api/file?path=100%25%zz.jpg").code)
    }

    @Test fun expectContinueAndMissingLength() {
        Socket("127.0.0.1", server.boundPort).use { s ->
            val o = s.getOutputStream()
            o.write("POST /api/upload?name=c.jpg HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\nExpect: 100-continue\r\n\r\n".toByteArray())
            o.flush()
            val inp = s.getInputStream()
            val first = ByteArray(25).let { b -> var n = 0; while (n < 25) { val k = inp.read(b, n, 25 - n); if (k < 0) break; n += k }; String(b, 0, n) }
            assertTrue(first, first.startsWith("HTTP/1.1 100 Continue"))
            o.write(byteArrayOf(9, 9, 9, 9)); o.flush()
            val rest = String(inp.readBytes())
            assertTrue(rest, rest.contains("200 OK"))
        }
        assertEquals(4L, File(root, "c.jpg").length())

        Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("POST /api/upload?name=d.jpg HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            assertTrue(String(s.getInputStream().readBytes()).startsWith("HTTP/1.1 411"))
        }
    }
}
