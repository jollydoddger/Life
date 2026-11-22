package com.jollydoddger.photoframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

object BitmapLoader {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Decodes at the smallest power-of-two size that still covers reqW x reqH. Handles JPEG/PNG/HEIC and EXIF rotation. */
    fun decode(file: File, reqW: Int, reqH: Int, software: Boolean = false): Bitmap? {
        try {
            val src = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                var sample = 1
                while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2
                decoder.setTargetSampleSize(sample)
                if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Throwable) {
            // Fallback for odd files
            return try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun load(file: File, reqW: Int, reqH: Int, cb: (Bitmap?) -> Unit) {
        pool.execute {
            val b = decode(file, reqW, reqH)
            main.post { cb(b) }
        }
    }

    /**
     * A slideshow photo plus, if [withBlur], a tiny pre-blurred copy for the background.
     * Blurring once here (a ~100 px image, a few ms) instead of a live RenderEffect means
     * the GPU isn't re-blurring a full-screen layer on every frame of the Ken Burns pan, all
     * day, and it works the same on Android 9–11, which have no RenderEffect.
     */
    fun loadSlide(file: File, reqW: Int, reqH: Int, withBlur: Boolean, cb: (Bitmap?, Bitmap?) -> Unit) {
        pool.execute {
            // Software so the pixels can be read for the blur; hardware otherwise (less app memory).
            val b = decode(file, reqW, reqH, software = withBlur)
            val blurred = if (b != null && withBlur) runCatching { blurredThumb(b) }.getOrNull() else null
            main.post { cb(b, blurred) }
        }
    }

    fun blurredThumb(src: Bitmap, width: Int = 96, radius: Int = 3): Bitmap {
        val w = width.coerceAtMost(src.width).coerceAtLeast(1)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { // three box blurs ≈ a Gaussian
            boxBlur(px, w, h, radius, horizontal = true)
            boxBlur(px, w, h, radius, horizontal = false)
        }
        // Dim it so the sharp photo in front stands out
        for (i in px.indices) {
            val c = px[i]
            px[i] = Color.rgb((Color.red(c) * 0.55f).toInt(), (Color.green(c) * 0.55f).toInt(), (Color.blue(c) * 0.55f).toInt())
        }
        if (small !== src) small.recycle()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One pass of a running-sum box blur along rows or columns, edges clamped. */
    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val rr = IntArray(len); val gg = IntArray(len); val bb = IntArray(len)
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = px[idx(i)]; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (i in 0 until len) {
                rr[i] = sr / div; gg[i] = sg / div; bb[i] = sb / div
                val add = px[idx(i + r + 1)]; val sub = px[idx(i - r)]
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
            for (i in 0 until len) px[idx(i)] = (0xFF shl 24) or (rr[i] shl 16) or (gg[i] shl 8) or bb[i]
        }
    }

    fun videoFrame(file: File, width: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, width)
                ?: r.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Returns 0/90/180/270 */
    fun videoRotation(file: File): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { r.release() }
        }
    }
}

object Thumbs {
    private const val SIZE = 360
    /** The Library tab can ask for dozens at once; decoding them all in parallel would swamp the TV. */
    private val gate = java.util.concurrent.Semaphore(3)

    fun get(dir: File, f: File): File? {
        dir.mkdirs()
        val key = (f.path + "|" + f.lastModified() + "|" + f.length()).hashCode().toUInt().toString(16)
        val out = File(dir, "$key.jpg")
        if (out.exists()) return out
        gate.acquire()
        try {
            if (out.exists()) return out // made by another request while this one waited
            val src = (if (MediaLibrary.kind(f) == true) BitmapLoader.videoFrame(f, SIZE) else BitmapLoader.decode(f, SIZE, SIZE, software = true))
                ?: return null
            val longest = maxOf(src.width, src.height)
            val bmp = if (longest > SIZE * 2) {
                val scale = SIZE * 2f / longest
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
            } else src
            // Written aside and renamed, so a half-written thumbnail is never served
            val tmp = File(dir, "$key.tmp")
            return try {
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (tmp.renameTo(out)) out else null
            } catch (_: Throwable) {
                tmp.delete(); null
            }
        } finally {
            gate.release()
        }
    }

    fun clear(dir: File) { dir.listFiles()?.forEach { it.delete() } }
}

object Qr {
    fun make(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

object Net {
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    fun uploadUrl(): String = "http://${localIp() ?: "TV-IP"}:${WebServer.PORT}"
}
