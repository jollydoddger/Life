package com.jollydoddger.photoframe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny HTTP server: serves the upload/settings page and streams uploads straight onto the USB stick.
 * Takes the page and the thumbnail cache folder rather than a Context, so the JVM tests can run it.
 */
class WebServer(
    private val page: () -> ByteArray,
    private val thumbDir: File,
    private val port: Int = PORT,
) {
    companion object {
        const val PORT = 8080

        fun forContext(ctx: Context) = WebServer(
            page = { ctx.assets.open("web/index.html").use { it.readBytes() } },
            thumbDir = File(ctx.cacheDir, "thumbs"),
        )
    }

    /** The port actually bound (useful when [port] is 0 in tests), or -1 before binding. */
    val boundPort: Int get() = socket?.localPort ?: -1

    @Volatile private var running = false
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    private class Req(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val input: InputStream,
        val out: OutputStream,
    ) {
        /** The client asked to hear back before sending the body (curl does for big files). */
        val expectsContinue get() = headers["expect"]?.equals("100-continue", ignoreCase = true) == true

        /** Tells such a client to go ahead. Sent only once the request has passed its checks, so a
         *  refused or duplicate upload costs no transfer at all. */
        fun goAhead() {
            if (expectsContinue) { out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush() }
        }
    }

    fun start() {
        running = true
        Thread({ loop() }, "web-server").start()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private fun loop() {
        while (running) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(port))
                    socket = ss
                    while (running) {
                        val c = ss.accept()
                        pool.execute { handle(c) }
                    }
                }
            } catch (_: Exception) {
                if (running) Thread.sleep(3000)
            }
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 16384) throw IOException("Header too long")
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }

    /** A stray "%" (e.g. a file called "100% me.jpg" sent unencoded) shouldn't kill the request. */
    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 60_000
                val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val line = readLine(inp) ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val target = parts[1]
                val req = Req(
                    parts[0].uppercase(),
                    target.substringBefore('?'),
                    parseQuery(target.substringAfter('?', "")),
                    headers,
                    inp,
                    out,
                )
                FrameState.touch() // someone's phone is using the page → someone's home
                try {
                    route(req, out)
                } catch (e: Exception) {
                    fail(out, 500, e.message ?: "Server error")
                }
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- routing ----------

    private fun route(r: Req, out: OutputStream) {
        val m = r.method
        when {
            m == "GET" && (r.path == "/" || r.path == "/index.html") ->
                send(out, 200, "text/html; charset=utf-8", page())

            m == "GET" && r.path == "/api/status" -> json(out, status())
            m == "GET" && r.path == "/api/settings" -> json(out, SettingsStore.current.toJson())
            m == "POST" && r.path == "/api/settings" -> {
                val body = String(readBody(r, 256 * 1024), Charsets.UTF_8)
                SettingsStore.update(JSONObject(body))
                json(out, SettingsStore.current.toJson())
            }

            m == "GET" && r.path == "/api/albums" -> json(out, albums())
            m == "POST" && r.path == "/api/albums" -> {
                val name = r.query["name"].orEmpty()
                if (name.isBlank()) return fail(out, 400, "Album name needed")
                MediaLibrary.albumDir(name)
                FrameState.libraryChanged()
                json(out, albums())
            }

            m == "GET" && r.path == "/api/media" -> json(out, media(r.query["album"]))
            m == "GET" && r.path == "/api/thumb" -> {
                val f = MediaLibrary.resolve(r.query["path"].orEmpty()) ?: return fail(out, 404, "Not found")
                val t = Thumbs.get(thumbDir, f) ?: return fail(out, 415, "Can't preview")
                sendFile(out, t, "image/jpeg", cache = true)
            }
            m == "GET" && r.path == "/api/file" -> {
                val f = MediaLibrary.resolve(r.query["path"].orEmpty()) ?: return fail(out, 404, "Not found")
                sendFile(out, f, mime(f), cache = true)
            }

            m == "POST" && r.path == "/api/upload" -> upload(r, out)
            m == "POST" && r.path == "/api/delete" -> {
                val f = MediaLibrary.resolve(r.query["path"].orEmpty()) ?: return fail(out, 404, "Not found")
                if (f.isDirectory) {
                    if (f.listFiles()?.isNotEmpty() == true) return fail(out, 409, "Album isn't empty")
                    f.delete()
                } else if (!f.delete()) return fail(out, 500, "Couldn't delete")
                FrameState.libraryChanged()
                json(out, JSONObject().put("ok", true))
            }

            m == "POST" && r.path == "/api/control" -> {
                val a = r.query["action"].orEmpty()
                if (a !in setOf("next", "prev", "pause", "info", "wake", "sleep", "reload")) return fail(out, 400, "Unknown action")
                FrameState.command(a)
                json(out, JSONObject().put("ok", true))
            }

            else -> fail(out, 404, "Not found")
        }
    }

    // ---------- handlers ----------

    private fun status(): JSONObject {
        val root = MediaLibrary.root
        val all = MediaLibrary.items()
        val s = SettingsStore.current
        val now = System.currentTimeMillis()
        val seen = FrameState.presence
        return JSONObject().apply {
            put("storage", MediaLibrary.storageLabel)
            put("onUsb", MediaLibrary.onUsb)
            put("allFilesAccess", MediaLibrary.hasAllFilesAccess)
            put("freeBytes", root.usableSpace)
            put("totalBytes", root.totalSpace)
            put("photos", all.count { !it.isVideo })
            put("videos", all.count { it.isVideo })
            put("awake", FrameState.awake)
            put("sleepReason", FrameState.sleepReason)
            put("url", Net.uploadUrl())
            put("phones", JSONArray().apply {
                s.phones.forEach { p ->
                    val t = seen[p.ip]
                    put(JSONObject().put("name", p.name).put("ip", p.ip).put("secondsAgo", if (t == null) -1 else (now - t) / 1000))
                }
            })
        }
    }

    private fun albums(): JSONArray = JSONArray().apply {
        put(JSONObject().put("name", "").put("count", MediaLibrary.items(album = "").size))
        MediaLibrary.albums().forEach { a -> put(JSONObject().put("name", a).put("count", MediaLibrary.items(album = a).size)) }
    }

    private fun media(album: String?): JSONArray {
        val list = (if (album == null) MediaLibrary.items() else MediaLibrary.items(album = album))
            .sortedByDescending { it.mtime }
        return JSONArray().apply {
            list.forEach {
                put(JSONObject()
                    .put("path", MediaLibrary.relPath(it.file))
                    .put("name", it.file.name)
                    .put("album", it.album)
                    .put("video", it.isVideo)
                    .put("size", it.file.length())
                    .put("mtime", it.mtime))
            }
        }
    }

    private fun upload(r: Req, out: OutputStream) {
        val len = r.headers["content-length"]?.toLongOrNull() ?: return fail(out, 411, "Missing Content-Length")
        val name = MediaLibrary.sanitize(r.query["name"] ?: "upload.jpg")
        if (MediaLibrary.kind(File(name)) == null) return fail(out, 415, "Not a photo or video: $name")
        val dir = MediaLibrary.albumDir(r.query["album"].orEmpty())
        if (dir.usableSpace < len + 50L * 1024 * 1024) return fail(out, 507, "Not enough space on the storage")

        // Same name and size already in this album: it's the same file sent again (a re-share, a
        // re-run of the Mac script). Say so instead of saving "IMG_1 (1).jpg".
        val same = File(dir, name)
        if (same.isFile && same.length() == len) {
            if (!r.expectsContinue) drain(r.input, len) // the body is on its way regardless
            FrameState.touch()
            return json(out, JSONObject().put("ok", true).put("path", MediaLibrary.relPath(same)).put("duplicate", true))
        }

        r.goAhead()
        // replace=1 (the Mac script): a re-exported, edited photo overwrites the old one, atomically.
        val target = if (r.query["replace"] == "1") File(dir, name) else MediaLibrary.uniqueFile(dir, name)
        val tmp = File(dir, ".${target.name}.part")
        try {
            FileOutputStream(tmp).use { fos ->
                val buf = ByteArray(256 * 1024)
                var left = len
                while (left > 0) {
                    val n = r.input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("Upload interrupted")
                    fos.write(buf, 0, n)
                    left -= n
                }
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Couldn't save file")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        // The photo's own capture date wins: iPhone Safari reports every picked photo as "now".
        val taken = if (MediaLibrary.kind(target) == false) exifDate(target) else null
        (taken ?: r.query["mtime"]?.toLongOrNull()?.takeIf { it > 0 })?.let { target.setLastModified(it) }
        FrameState.libraryChanged()
        json(out, JSONObject().put("ok", true).put("path", MediaLibrary.relPath(target)))
    }

    /** EXIF DateTimeOriginal (JPEG, HEIC, PNG, WebP) as epoch ms in the TV's time zone, or null. */
    private fun exifDate(f: File): Long? = runCatching {
        val e = android.media.ExifInterface(f.path)
        val v = e.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: e.getAttribute(android.media.ExifInterface.TAG_DATETIME) ?: return null
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(v.trim())?.time
    }.getOrNull()?.takeIf { it > 0 }

    // ---------- HTTP helpers ----------

    private fun drain(input: InputStream, len: Long) {
        val buf = ByteArray(256 * 1024)
        var left = len
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            left -= n
        }
    }

    private fun readBody(r: Req, max: Int): ByteArray {
        val len = r.headers["content-length"]?.toIntOrNull() ?: 0
        if (len > max) throw IOException("Body too large")
        r.goAhead()
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = r.input.read(b, off, len - off)
            if (n < 0) break
            off += n
        }
        return b
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 411 -> "Length Required"
        415 -> "Unsupported Media Type"; 507 -> "Insufficient Storage"; else -> "Error"
    }

    private fun header(out: OutputStream, code: Int, type: String, length: Long, cache: Boolean) {
        val h = StringBuilder()
            .append("HTTP/1.1 $code ${reason(code)}\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: $length\r\n")
            .append("Connection: close\r\n")
            .append(if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n")
            .append("\r\n")
        out.write(h.toString().toByteArray(Charsets.UTF_8))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        header(out, code, type, body.size.toLong(), false)
        out.write(body)
    }

    private fun sendFile(out: OutputStream, f: File, type: String, cache: Boolean) {
        header(out, 200, type, f.length(), cache)
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    private fun json(out: OutputStream, body: Any) = send(out, 200, "application/json; charset=utf-8", body.toString().toByteArray(Charsets.UTF_8))

    private fun fail(out: OutputStream, code: Int, msg: String) =
        send(out, code, "application/json; charset=utf-8", JSONObject().put("error", msg).toString().toByteArray(Charsets.UTF_8))

    private fun mime(f: File) = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"; "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }
}
