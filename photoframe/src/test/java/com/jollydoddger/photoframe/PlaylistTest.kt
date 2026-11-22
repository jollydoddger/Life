package com.jollydoddger.photoframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaylistTest {
    private fun item(name: String, mtime: Long = 0) = MediaItem(File("/lib/$name"), "", false, mtime)
    private fun items(n: Int, prefix: String = "p") = (1..n).map { item("$prefix$it.jpg", it.toLong()) }

    @Test fun everyPhotoOncePerRound() {
        val all = items(20)
        val p = Playlist(all, shuffle = true)
        val round = List(20) { p.next()!! }
        assertEquals(all.toSet(), round.toSet())
    }

    @Test fun noRepeatAcrossReshuffle() {
        val p = Playlist(items(3), shuffle = true)
        var last = p.next()
        repeat(300) {
            val n = p.next()
            assertNotEquals(last, n)
            last = n
        }
    }

    @Test fun unshuffledIsInDateOrder() {
        val p = Playlist(items(5).reversed(), shuffle = false)
        assertEquals(listOf("p1.jpg", "p2.jpg", "p3.jpg"), List(3) { p.next()!!.file.name })
    }

    @Test fun prevThenNextWalksHistory() {
        val p = Playlist(items(10), shuffle = true)
        val a = p.next(); val b = p.next(); val c = p.next()
        assertEquals(b, p.prev())
        assertEquals(a, p.prev())
        assertEquals(a, p.prev()) // can't go before the first
        assertEquals(b, p.next())
        assertEquals(c, p.next())
    }

    @Test fun freshUploadsShowNext() {
        val old = items(10)
        val p = Playlist(old, shuffle = true)
        p.next(); p.next()
        val up = listOf(item("new1.jpg", 100), item("new2.jpg", 101))
        p.update(old + up)
        assertEquals(listOf("new1.jpg", "new2.jpg"), List(2) { p.next()!!.file.name })
    }

    @Test fun libraryAppearingIsNotPlayedInDateOrder() {
        // e.g. the USB stick mounted after the show had started on an empty internal folder
        val p = Playlist(emptyList(), shuffle = true)
        val lib = items(200)
        p.update(lib)
        val first = List(30) { p.next()!!.file.name }
        assertNotEquals((1..30).map { "p$it.jpg" }, first)
        assertEquals(30, first.toSet().size)
    }

    @Test fun deletedItemsDropOut() {
        val all = items(5)
        val p = Playlist(all, shuffle = false)
        p.update(all.filter { it.file.name != "p2.jpg" })
        val seen = List(8) { p.next()!!.file.name }
        assertTrue("p2.jpg" !in seen)
    }
}
