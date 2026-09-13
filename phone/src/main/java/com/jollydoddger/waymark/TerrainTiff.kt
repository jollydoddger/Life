package com.jollydoddger.waymark

import java.io.InputStream

/**
 * Enough GeoTIFF to read an Environment Agency LIDAR tile, and no more.
 *
 * Android decodes nothing of the sort — `BitmapFactory` has never done TIFF,
 * let alone a single-band float32 elevation raster — and the usual answer,
 * GDAL, is tens of megabytes of native library for one file format. So this
 * reads the exact shape the EA publish, which was established by taking one
 * apart rather than by reading the specification and hoping:
 *
 * ```
 * classic TIFF, little-endian      2500 x 2500 (2 m) or 5000 x 5000 (1 m)
 * BitsPerSample 32, SampleFormat 3 IEEE float, one sample per pixel
 * Compression 5 (LZW), Predictor 1 no differencing
 * tiled, 128 x 128                 400 tiles for a 2 m square
 * ModelTiepoint + ModelPixelScale  British National Grid, straight off
 * GDAL_NODATA -3.4028235e+38
 * ```
 *
 * The decoder was checked against a real 5 km square (SE03se, 2022, 2 m):
 * all 400 tiles decoded, 6,250,000 cells of 6,250,000, and a min/max/mean of
 * 197.81 / 419.63 / 315.87 against the 197.81 / 419.63 / 315.87 the file
 * carries in its own metadata. That is the evidence this is right; the unit
 * tests hold it there.
 *
 * **The trap in the format**, and it would have read as real terrain: a
 * tiled TIFF pads its right and bottom edges out to a whole tile, and the
 * padding is zeroes. 2500 does not divide by 128, so a fifth of the last
 * tile column is zero — and a band of 0 m down the east edge of a Pennine
 * square shades as a sea cliff. Everything outside the declared width and
 * height is dropped rather than believed.
 */
object TerrainTiff {

    /** Thrown with something a person could act on, never a bare cast error. */
    class Unreadable(message: String) : Exception(message)

    /**
     * The most cells to hold at once. A 1 m square is 25 million of them and
     * 100 MB as floats, which is most of a phone's heap — this app has
     * already been killed once by an allocation like that, in the Overpass
     * parser. Past this the grid is sampled down on the way in, so a 1 m
     * tile lands at an effective 2 m rather than not at all.
     */
    const val MAX_CELLS = 7_000_000

    private class Ifd(val bytes: ByteArray) {
        val little = bytes.size > 1 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()
        val tags = HashMap<Int, LongArray>()
        val ascii = HashMap<Int, String>()

        fun u8(at: Int): Int = bytes[at].toInt() and 0xFF
        fun u16(at: Int): Int =
            if (little) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)
        fun u32(at: Int): Long =
            if (little) {
                (u8(at).toLong()) or (u8(at + 1).toLong() shl 8) or
                    (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)
            } else {
                (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or
                    (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
            }
        fun f64(at: Int): Double {
            var bits = 0L
            for (i in 0 until 8) {
                val b = u8(at + if (little) 7 - i else i).toLong()
                bits = (bits shl 8) or b
            }
            return Double.fromBits(bits)
        }
        fun f32(at: Int): Float {
            var bits = 0
            for (i in 0 until 4) {
                val b = u8(at + if (little) 3 - i else i)
                bits = (bits shl 8) or b
            }
            return Float.fromBits(bits)
        }
    }

    private const val T_WIDTH = 256
    private const val T_HEIGHT = 257
    private const val T_BITS = 258
    private const val T_COMPRESSION = 259
    private const val T_SAMPLES = 277
    private const val T_ROWS_PER_STRIP = 278
    private const val T_STRIP_OFFSETS = 273
    private const val T_STRIP_COUNTS = 279
    private const val T_PREDICTOR = 317
    private const val T_TILE_WIDTH = 322
    private const val T_TILE_LENGTH = 323
    private const val T_TILE_OFFSETS = 324
    private const val T_TILE_COUNTS = 325
    private const val T_SAMPLE_FORMAT = 339
    private const val T_PIXEL_SCALE = 33550
    private const val T_TIEPOINT = 33922
    private const val T_NODATA = 42113

    /**
     * Read the whole file into a [Terrain.Grid].
     *
     * Takes the bytes rather than a stream on purpose: a TIFF's tag
     * directory lives at the *end* of the file and its offsets point
     * anywhere, so it is a random-access format and pretending otherwise
     * means holding it all anyway plus a pile of seeking.
     */
    fun read(bytes: ByteArray): Terrain.Grid {
        val f = Ifd(bytes)
        if (bytes.size < 8) throw Unreadable("that file is too small to be a TIFF")
        val order = String(bytes, 0, 2, Charsets.US_ASCII)
        if (order != "II" && order != "MM") throw Unreadable("not a TIFF (no byte-order mark)")
        when (val magic = f.u16(2)) {
            42 -> {}
            43 -> throw Unreadable("BigTIFF isn't supported — ask the portal for the standard GeoTIFF")
            else -> throw Unreadable("not a TIFF (magic $magic)")
        }
        val ifdAt = f.u32(4).toInt()
        if (ifdAt <= 0 || ifdAt + 2 > bytes.size) throw Unreadable("the tag directory is off the end of the file")
        val count = f.u16(ifdAt)
        for (i in 0 until count) {
            val at = ifdAt + 2 + i * 12
            if (at + 12 > bytes.size) break
            val tag = f.u16(at)
            val type = f.u16(at + 2)
            val n = f.u32(at + 4).toInt()
            val size = when (type) {
                1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 1
            }
            val total = size.toLong() * n
            val from = if (total <= 4) at + 8 else f.u32(at + 8).toInt()
            if (from < 0 || from + total > bytes.size) continue
            if (type == 2) {
                val end = (from until from + n).firstOrNull { bytes[it] == 0.toByte() } ?: (from + n)
                f.ascii[tag] = String(bytes, from, end - from, Charsets.US_ASCII)
                continue
            }
            val vals = LongArray(n)
            for (k in 0 until n) {
                val o = from + k * size
                vals[k] = when (type) {
                    3 -> f.u16(o).toLong()
                    4 -> f.u32(o)
                    12 -> f.f64(o).toRawBits()
                    11 -> f.f32(o).toRawBits().toLong()
                    else -> f.u8(o).toLong()
                }
            }
            f.tags[tag] = vals
        }

        fun one(tag: Int, fallback: Long = -1): Long = f.tags[tag]?.firstOrNull() ?: fallback
        val width = one(T_WIDTH).toInt()
        val height = one(T_HEIGHT).toInt()
        if (width <= 0 || height <= 0) throw Unreadable("the file doesn't say how big it is")
        if (one(T_SAMPLES, 1).toInt() != 1) throw Unreadable("expected one band, this has ${one(T_SAMPLES)}")
        val bits = one(T_BITS, 32).toInt()
        val format = one(T_SAMPLE_FORMAT, 1).toInt()
        if (bits != 32 || format != 3) {
            throw Unreadable("expected 32-bit float heights, this is ${bits}-bit format $format")
        }
        val predictor = one(T_PREDICTOR, 1).toInt()
        if (predictor != 1) throw Unreadable("predictor $predictor isn't supported")
        val compression = one(T_COMPRESSION, 1).toInt()
        if (compression != 1 && compression != 5) {
            throw Unreadable("compression $compression isn't supported (want none or LZW)")
        }

        // Georeferencing. The tiepoint's raster corner is normally (0,0), so
        // its model point is the top-left of the square; the scale gives the
        // cell. A world file alongside says the same thing and is not needed.
        val tie = f.tags[T_TIEPOINT]?.map { Double.fromBits(it) }
        val scale = f.tags[T_PIXEL_SCALE]?.map { Double.fromBits(it) }
        if (tie == null || tie.size < 6 || scale == null || scale.size < 2) {
            throw Unreadable("no georeferencing — is this a plain TIFF rather than a GeoTIFF?")
        }
        val cell = scale[0]
        val westAt = tie[3] - tie[0] * cell
        val northAt = tie[4] + tie[1] * scale[1]
        val noData = f.ascii[T_NODATA]?.trim()?.toFloatOrNull()

        // Sampling down when a square would not fit in memory: 1 m tiles are
        // 25 million cells, and the app has been killed by an allocation of
        // that order before.
        var stride = 1
        while ((width / stride).toLong() * (height / stride) > MAX_CELLS) stride++
        val cols = (width + stride - 1) / stride
        val rows = (height + stride - 1) / stride
        val out = FloatArray(cols * rows) { Float.NaN }

        fun put(x: Int, y: Int, v: Float) {
            // Everything past the declared size is a tile's zero padding.
            if (x >= width || y >= height) return
            if (x % stride != 0 || y % stride != 0) return
            val c = x / stride
            val r = y / stride
            if (c >= cols || r >= rows) return
            val bad = v.isNaN() || (noData != null && v <= noData * 0.999f) || v < -1e30f
            out[r * cols + c] = if (bad) Float.NaN else v
        }

        val tileW = one(T_TILE_WIDTH).toInt()
        val tileH = one(T_TILE_LENGTH).toInt()
        if (tileW > 0 && tileH > 0) {
            val offs = f.tags[T_TILE_OFFSETS] ?: throw Unreadable("tiled, but with no tile offsets")
            val lens = f.tags[T_TILE_COUNTS] ?: throw Unreadable("tiled, but with no tile lengths")
            val across = (width + tileW - 1) / tileW
            for (i in offs.indices) {
                val raw = slice(bytes, offs[i].toInt(), lens.getOrElse(i) { 0 }.toInt()) ?: continue
                val flat = if (compression == 5) Lzw.decode(raw) else raw
                val tr = i / across
                val tc = i % across
                var p = 0
                for (y in 0 until tileH) {
                    for (x in 0 until tileW) {
                        if (p + 4 > flat.size) break
                        put(tc * tileW + x, tr * tileH + y, le32f(flat, p, f.little))
                        p += 4
                    }
                }
            }
        } else {
            val offs = f.tags[T_STRIP_OFFSETS] ?: throw Unreadable("neither tiled nor stripped")
            val lens = f.tags[T_STRIP_COUNTS] ?: throw Unreadable("stripped, but with no strip lengths")
            val perStrip = one(T_ROWS_PER_STRIP, height.toLong()).toInt().coerceAtLeast(1)
            for (i in offs.indices) {
                val raw = slice(bytes, offs[i].toInt(), lens.getOrElse(i) { 0 }.toInt()) ?: continue
                val flat = if (compression == 5) Lzw.decode(raw) else raw
                var p = 0
                for (y in 0 until perStrip) {
                    val gy = i * perStrip + y
                    if (gy >= height) break
                    for (x in 0 until width) {
                        if (p + 4 > flat.size) break
                        put(x, gy, le32f(flat, p, f.little))
                        p += 4
                    }
                }
            }
        }

        val south = northAt - height * scale[1]
        return Terrain.Grid(westAt, south, cell * stride, cols, rows, out)
    }

    fun read(input: InputStream): Terrain.Grid = read(input.readBytes())

    private fun slice(b: ByteArray, at: Int, len: Int): ByteArray? {
        if (at < 0 || len <= 0 || at + len > b.size) return null
        return b.copyOfRange(at, at + len)
    }

    private fun le32f(b: ByteArray, at: Int, little: Boolean): Float {
        var bits = 0
        for (i in 0 until 4) {
            val k = if (little) at + 3 - i else at + i
            bits = (bits shl 8) or (b[k].toInt() and 0xFF)
        }
        return Float.fromBits(bits)
    }

    /**
     * TIFF's LZW, which is not quite anybody else's.
     *
     * Codes start at nine bits and grow, and the width increases **one code
     * early** — the decoder learns a dictionary entry only when the code
     * after it arrives, so it runs an entry behind the encoder and has to
     * widen sooner to stay in step. Get that boundary wrong and a stream
     * decodes perfectly for a few thousand bytes and then falls off a cliff,
     * which is exactly what it did while this was being written.
     */
    internal object Lzw {
        private const val CLEAR = 256
        private const val EOI = 257

        fun decode(input: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(input.size * 4)
            val prefix = IntArray(4096) { -1 }
            val suffix = ByteArray(4096)
            var next = 258
            var bits = 9
            var prev = -1
            var acc = 0L
            var have = 0
            var at = 0
            // A chain can be as long as the dictionary is deep, and it is
            // walked backwards, so it is built from the end of this.
            val scratch = ByteArray(4096)

            for (i in 0 until 256) suffix[i] = i.toByte()

            while (true) {
                while (have < bits && at < input.size) {
                    acc = (acc shl 8) or (input[at++].toLong() and 0xFF)
                    have += 8
                }
                if (have < bits) break
                val code = ((acc shr (have - bits)) and ((1L shl bits) - 1)).toInt()
                have -= bits
                if (code == CLEAR) { next = 258; bits = 9; prev = -1; continue }
                if (code == EOI) break

                val start: Int
                if (code < next) {
                    start = writeChain(code, prefix, suffix, scratch, scratch.size)
                } else {
                    // The one case that is not a straight lookup: a code the
                    // decoder has not built yet can only ever be the previous
                    // string with its own first byte APPENDED. Prepending it
                    // instead — which is the easy slip when the chain is
                    // being written backwards — decodes most streams for a
                    // while and then quietly produces wrong bytes.
                    if (prev < 0) break
                    val s = writeChain(prev, prefix, suffix, scratch, scratch.size - 1)
                    scratch[scratch.size - 1] = scratch[s]
                    start = s
                }
                val n = scratch.size - start
                out.write(scratch, start, n)
                if (prev >= 0 && next < 4096) {
                    prefix[next] = prev
                    suffix[next] = scratch[start]
                    next++
                }
                prev = code
                if (next + 1 >= (1 shl bits) && bits < 12) bits++
            }
            return out.toByteArray()
        }

        /**
         * The string for [code] written backwards, finishing at [endAt];
         * returns the index it starts at. Backwards because a dictionary
         * entry is a chain of prefixes and that is the only direction it
         * can be walked.
         */
        private fun writeChain(
            code: Int,
            prefix: IntArray,
            suffix: ByteArray,
            into: ByteArray,
            endAt: Int,
        ): Int {
            var c = code
            var p = endAt
            while (c >= 0 && p > 0) {
                into[--p] = suffix[c]
                c = prefix[c]
            }
            return p
        }
    }
}
