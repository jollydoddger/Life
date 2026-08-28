package com.jollydoddger.waymark.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.jollydoddger.waymark.shared.Prefs.osApiKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiles: an in-memory LRU of bitmaps over a disk store of plain files
 * `files/tiles/{z}/{x}/{y}.png`.
 *
 * Plain files in filesDir, not cacheDir, deliberately: the corridor prefetched
 * for a route is an offline *promise* — the OS clearing a cache on a hillside
 * with no signal would break exactly the walk it was fetched for. Plain files
 * also make the phone→watch tile transfer a zip of a directory, no database.
 *
 * Everything is fetched off the draw path: [bitmap] answers from memory and
 * otherwise schedules a load (disk, then network), pinging [onTileReady] when
 * something new is drawable — the view redraws and meanwhile scales up a
 * coarser tile.
 */
class TileStore(context: Context) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.filesDir, "tiles")

    private val memory = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val inFlight = HashSet<Long>()
    private val failedAt = HashMap<Long, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parallel = Semaphore(4)

    /** Redraw hook; the map view sets this to postInvalidateOnAnimation. */
    var onTileReady: (() -> Unit)? = null

    /** Set when the server said 401/403 — the UI turns it into words about the key/plan. */
    @Volatile var lastAuthError: Int = 0

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 42) or (x.toLong() shl 21) or y.toLong()
    fun fileFor(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.png")

    /**
     * Memory-only lookup, no fetch — for coarser-tile fallback while a real
     * tile loads (fetching every parent looked at would flood the queue).
     */
    fun peek(z: Int, x: Int, y: Int): Bitmap? {
        if (x < 0 || y < 0 || z < 0 || z > TileGrid.MAX_Z) return null
        return memory.get(key(z, x, y))
    }

    /** Memory-fast lookup for drawing; misses trigger an async load. */
    fun bitmap(z: Int, x: Int, y: Int): Bitmap? {
        if (x < 0 || y < 0 || z < 0 || z > TileGrid.MAX_Z) return null
        val k = key(z, x, y)
        memory.get(k)?.let { return it }
        synchronized(inFlight) {
            if (k in inFlight) return null
            val failed = failedAt[k]
            if (failed != null && System.currentTimeMillis() - failed < 60_000) return null
            inFlight.add(k)
        }
        scope.launch {
            try {
                parallel.withPermit { load(z, x, y, k) }
            } finally {
                synchronized(inFlight) { inFlight.remove(k) }
            }
        }
        return null
    }

    private fun load(z: Int, x: Int, y: Int, k: Long) {
        val f = fileFor(z, x, y)
        if (!f.exists()) {
            val ok = download(z, x, y)
            if (!ok) {
                synchronized(inFlight) { failedAt[k] = System.currentTimeMillis() }
                return
            }
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(f.path, opts)
        if (bmp == null) {
            // A truncated or corrupt file: remove it so the next look refetches.
            f.delete()
            synchronized(inFlight) { failedAt[k] = System.currentTimeMillis() }
            return
        }
        memory.put(k, bmp)
        onTileReady?.invoke()
    }

    /**
     * Fetch one tile to disk (atomically — a half-written PNG must not be
     * mistaken for a tile). True if the file is now present. Used both by the
     * draw path above and by the corridor prefetch.
     */
    fun download(z: Int, x: Int, y: Int): Boolean {
        val f = fileFor(z, x, y)
        if (f.exists()) return true
        val apiKey = ctx.osApiKey
        if (apiKey.isEmpty()) return false
        return try {
            val conn = URL(TileGrid.url(z, x, y, apiKey)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        f.parentFile?.mkdirs()
                        val tmp = File(f.parentFile, f.name + ".tmp")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(f)
                        true
                    }
                    401, 403 -> { lastAuthError = code; false }
                    else -> false
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
