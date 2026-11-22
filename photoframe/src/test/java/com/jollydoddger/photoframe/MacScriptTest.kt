package com.jollydoddger.photoframe

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs mac/photoframe-upload (bash + curl, like on the Mac) against the real server. */
class MacScriptTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var home: File
    private lateinit var server: WebServer
    private val script = File("mac/photoframe-upload").absoluteFile

    @Before fun setUp() {
        assumeTrue(File("/bin/bash").exists() && runCatching { ProcessBuilder("curl", "--version").start().waitFor() == 0 }.getOrDefault(false))
        root = tmp.newFolder("tv")
        home = tmp.newFolder("home")
        MediaLibrary.useRoot(root)
        server = WebServer(page = { ByteArray(0) }, thumbDir = tmp.newFolder("thumbs"), port = 0).also { it.start() }
        val until = System.currentTimeMillis() + 5000
        while (server.boundPort <= 0 && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @After fun tearDown() { if (::server.isInitialized) server.stop() }

    private fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf("/bin/bash", script.path) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["PHOTOFRAME_TV"] = "http://127.0.0.1:${server.boundPort}"
                environment()["PHOTOFRAME_HOME"] = home.path
                environment()["HOME"] = home.path
            }.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun old(f: File) = f.apply { setLastModified(System.currentTimeMillis() - 3_600_000) }

    @Test fun folderBecomesAlbumAndRerunsAreFree() {
        val mac = tmp.newFolder("Holidays")
        File(mac, "a.jpg").writeBytes(ByteArray(20_000) { 1 })
        File(mac, "sub").mkdir(); File(mac, "sub/b.mov").writeBytes(ByteArray(300_000) { 2 })
        File(mac, ".DS_Store").writeText("x"); File(mac, "notes.txt").writeText("x")

        val (code, out) = run(mac.path)
        assertEquals(out, 0, code)
        assertEquals(setOf("a.jpg", "b.mov"), File(root, "Holidays").list()!!.toSet())

        val (code2, out2) = run(mac.path)
        assertEquals(out2, 0, code2)
        assertTrue(out2, out2.contains("Sent 0, 2 already there"))
        assertEquals(2, File(root, "Holidays").list()!!.size)

        // Edited and re-exported: replaces the TV's copy, no "a (1).jpg"
        val edited = ByteArray(25_000) { 3 }
        File(mac, "a.jpg").writeBytes(edited)
        run(mac.path)
        assertEquals(setOf("a.jpg", "b.mov"), File(root, "Holidays").list()!!.toSet())
        assertArrayEquals(edited, File(root, "Holidays/a.jpg").readBytes())
    }

    @Test fun namedAlbumAndAccents() {
        val f = File(tmp.root, "café & crème.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val (code, out) = run("-a", "Été 2026", f.path)
        assertEquals(out, 0, code)
        assertTrue(File(root, "Été 2026/café & crème.jpg").exists())
    }

    @Test fun exportFolderSync() {
        val export = tmp.newFolder("Photo Frame")
        old(File(export, "Holidays").apply { mkdir() }.let { File(it, "x.jpg") }.apply { writeBytes(byteArrayOf(5)) })
        old(File(export, "loose.png").apply { writeBytes(byteArrayOf(6)) })
        File(export, "still-exporting.mp4").writeBytes(byteArrayOf(7)) // brand new: left for next time

        val (c1, o1) = run("--sync", export.path)
        assertEquals(o1, 0, c1)
        assertTrue(File(root, "Holidays/x.jpg").exists())
        assertTrue(File(root, "loose.png").exists())
        assertTrue(!File(root, "still-exporting.mp4").exists())
        assertEquals(o1 + "\n" + File(home, "sent").readText(), 2, File(home, "sent").readLines().size)

        // Nothing new: a second run sends nothing
        val (_, out) = run("--sync", export.path)
        assertEquals("", out.trim())
    }
}
