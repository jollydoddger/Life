package com.jollydoddger.waymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import com.jollydoddger.waymark.shared.TileGrid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imported LIDAR squares, kept as map tiles rather than as terrain.
 *
 * A 5 km square at 2 m is six and a quarter million heights — twenty-five
 * megabytes as floats, and a hundred at 1 m. Holding one to draw from would
 * be the Overpass out-of-memory crash again with different numbers. So a
 * square is shaded **once, on import**, and written out as ordinary 256-pixel
 * PNG tiles on the same National Grid pyramid the OS paper already uses.
 * After that it costs what any other tile costs: the visible ones, in an
 * LRU, and nothing else.
 *
 * Tiles are rendered at several zooms rather than one and scaled, because a
 * hillshade scaled down turns to mush exactly where the detail matters —
 * and the detail is the entire reason for the feature.
 *
 * Nothing here talks to the network. A square arrives because he downloaded
 * it and shared it in, which is the whole bargain: the Environment Agency
 * publish it free under the Open Government Licence and serve it as bulk
 * files, so the file is his and this reads it.
 */
object TerrainStore {

    /** Zooms rendered. 9 is 1.75 m/px, about a 2 m square's own resolution;
     *  below 6 a hillshade is a grey smear and worth nothing. */
    private val LEVELS = 6..TileGrid.MAX_Z

    private fun dir(ctx: Context) = File(ctx.filesDir, "terrain").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")
    private fun tileFile(ctx: Context, z: Int, x: Int, y: Int) = File(dir(ctx), "$z/$x/$y.png")

    private val memory = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 10).toInt(),
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 42) or (x.toLong() shl 21) or y.toLong()

    /** One imported square, for the Settings list and for knowing what is held. */
    class Square(val name: String, val west: Double, val south: Double, val east: Double, val north: Double)

    fun squares(ctx: Context): List<Square> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Square(
                    o.optString("name"), o.getDouble("west"), o.getDouble("south"),
                    o.getDouble("east"), o.getDouble("north"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun remember(ctx: Context, s: Square) {
        val all = squares(ctx).filter { it.name != s.name } + s
        val arr = JSONArray()
        for (q in all) {
            arr.put(
                JSONObject().put("name", q.name).put("west", q.west).put("south", q.south)
                    .put("east", q.east).put("north", q.north),
            )
        }
        val tmp = File(dir(ctx), "index.json.tmp")
        tmp.writeText(arr.toString())
        val f = indexFile(ctx)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun clear(ctx: Context) {
        memory.evictAll()
        dir(ctx).deleteRecursively()
    }

    fun bytes(ctx: Context): Long =
        dir(ctx).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** A rendered tile, from memory or disk. Null means nothing covers it. */
    fun bitmap(ctx: Context, z: Int, x: Int, y: Int): Bitmap? {
        if (z < 0 || x < 0 || y < 0) return null
        val k = key(z, x, y)
        memory.get(k)?.let { return it }
        val f = tileFile(ctx, z, x, y)
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        memory.put(k, bmp)
        return bmp
    }

    // --- importing -----------------------------------------------------------

    /**
     * Pull a terrain grid out of whatever he shared in: the Environment
     * Agency's download is a zip holding a `.tif` beside its metadata, and
     * the older archive is a bare `.asc`. Returns null when the bytes are
     * plainly not terrain, so the caller can fall back to treating a shared
     * file as a GPX.
     */
    fun gridFrom(name: String, bytes: ByteArray): Terrain.Grid? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> fromZip(bytes)
            lower.endsWith(".tif") || lower.endsWith(".tiff") -> TerrainTiff.read(bytes)
            lower.endsWith(".asc") -> Terrain.parseAsc(bytes.inputStream())
            // No usable name — sniff it. A TIFF says so in its first two
            // bytes, and a zip in its first four.
            bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                fromZip(bytes)
            bytes.size > 4 && (
                (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
                    (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte())
                ) -> TerrainTiff.read(bytes)
            else -> null
        }
    }

    private fun fromZip(bytes: ByteArray): Terrain.Grid? {
        var best: ByteArray? = null
        var bestName = ""
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.lowercase()
                // The .tif is the one that matters; the .xml, .tfw and
                // .gpkg beside it are metadata this does not need — the
                // georeferencing is inside the TIFF itself.
                if (!e.isDirectory && (n.endsWith(".tif") || n.endsWith(".tiff") || n.endsWith(".asc"))) {
                    val out = z.readBytes()
                    if (best == null || out.size > best!!.size) { best = out; bestName = n }
                }
                z.closeEntry()
            }
        }
        val b = best ?: return null
        return if (bestName.endsWith(".asc")) Terrain.parseAsc(b.inputStream())
        else TerrainTiff.read(b)
    }

    /**
     * Shade a square and write it out as tiles. Blocking and slow — several
     * seconds for a 5 km square — so callers run it off the main thread with
     * [onProgress] saying how far along it is.
     *
     * The picture is a hillshade of a **partial local relief model**: the
     * smoothed hillside is mostly subtracted, so a path worn thirty
     * centimetres into a Calderdale valley side is not lost against two
     * hundred metres of hill, but enough of the landscape is kept that the
     * result still reads as countryside rather than as grey noise.
     */
    fun importGrid(
        ctx: Context,
        name: String,
        grid: Terrain.Grid,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Square {
        // One at a time, and each let go as soon as the next exists: two
        // 25 MB float grids and a 25 MB shade is most of a phone's heap.
        var relief: Terrain.Grid? = Terrain.localRelief(grid, radius = 12, keep = 0.18)
        val shade = Terrain.hillshade(relief!!, exaggeration = 2.0)
        relief = null

        var done = 0
        val total = LEVELS.sumOf { z -> tilesOver(grid, z).size }
        for (z in LEVELS) {
            for ((tx, ty) in tilesOver(grid, z)) {
                writeTile(ctx, grid, shade, z, tx, ty)
                onProgress(++done, total)
            }
        }
        val s = Square(name, grid.west, grid.south, grid.east, grid.north)
        remember(ctx, s)
        memory.evictAll()
        return s
    }

    /** Every National Grid tile at [z] that the square touches. */
    private fun tilesOver(g: Terrain.Grid, z: Int): List<Pair<Int, Int>> {
        val x0 = TileGrid.tileX(g.west, z)
        val x1 = TileGrid.tileX(g.east - 0.001, z)
        val y0 = TileGrid.tileY(g.north - 0.001, z)
        val y1 = TileGrid.tileY(g.south, z)
        val out = ArrayList<Pair<Int, Int>>()
        for (x in x0..x1) for (y in y0..y1) out.add(x to y)
        return out
    }

    /**
     * One tile, sampled out of the shade. Cells the square does not cover
     * stay transparent, so two squares side by side join without a seam and
     * a part-covered tile shows terrain where there is terrain and the map
     * underneath everywhere else.
     */
    private fun writeTile(ctx: Context, g: Terrain.Grid, shade: IntArray, z: Int, tx: Int, ty: Int) {
        val px = TileGrid.TILE_PX
        val mpp = TileGrid.METRES_PER_PX[z]
        val west = TileGrid.tileWest(tx, z)
        val north = TileGrid.tileNorth(ty, z)
        val pixels = IntArray(px * px)
        var any = false
        for (row in 0 until px) {
            val n = north - (row + 0.5) * mpp
            val gr = ((g.north - n) / g.cellSize).toInt()
            if (gr < 0 || gr >= g.rows) continue
            for (col in 0 until px) {
                val e = west + (col + 0.5) * mpp
                val gc = ((e - g.west) / g.cellSize).toInt()
                if (gc < 0 || gc >= g.cols) continue
                val v = shade[gr * g.cols + gc]
                if (v < 0) continue // no survey here
                pixels[row * px + col] = Color.argb(255, v, v, v)
                any = true
            }
        }
        if (!any) return
        val f = tileFile(ctx, z, tx, ty)
        f.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
