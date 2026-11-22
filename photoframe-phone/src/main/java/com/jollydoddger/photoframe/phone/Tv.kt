package com.jollydoddger.photoframe.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLEncoder

/** Where the TV is, remembered between launches, plus the few API calls the app makes itself. */
object Tv {
    const val PORT = 8080
    /** Must match FrameService.SERVICE_TYPE on the TV. */
    const val SERVICE_TYPE = "_photoframe._tcp"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun url(ctx: Context): String? = prefs(ctx).getString("url", null)
    fun save(ctx: Context, url: String) = prefs(ctx).edit().putString("url", url).apply()

    fun lastAlbum(ctx: Context): String = prefs(ctx).getString("album", "") ?: ""
    fun saveAlbum(ctx: Context, album: String) = prefs(ctx).edit().putString("album", album).apply()

    /** "192.168.1.20", "192.168.1.20:8080" or "http://192.168.1.20:8080/" → "http://192.168.1.20:8080". */
    fun normalise(input: String): String? {
        var s = input.trim().removeSuffix("/")
        if (s.isEmpty()) return null
        s = s.removePrefix("http://").removePrefix("https://")
        if (s.contains('/') || s.contains(' ')) return null
        val host = s.substringBefore(':')
        val port = s.substringAfter(':', PORT.toString()).toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return "http://$host:$port"
    }

    private fun get(url: String, timeoutMs: Int = 4000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { String(it.readBytes()) }
        } finally {
            c.disconnect()
        }
    }

    /** True if [base] answers like a photo frame. Blocking. */
    fun reachable(base: String): Boolean = runCatching { JSONObject(get("$base/api/status", 2500)).has("awake") }.getOrDefault(false)

    /** Album names, "" (Unsorted) first. Blocking. */
    fun albums(base: String): List<String> {
        val a = JSONArray(get("$base/api/albums"))
        return List(a.length()) { a.getJSONObject(it).getString("name") }
    }
}

/** Streams one file to the TV's upload endpoint. Plain JVM code so it can be tested off-device. */
class Uploader(private val base: String) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** @return the path the TV saved it under. Throws IOException with the TV's own message on failure. */
    fun upload(input: InputStream, length: Long, name: String, album: String, mtime: Long, onProgress: (Long) -> Unit = {}): String {
        val q = "album=${enc(album)}&name=${enc(name)}&mtime=$mtime"
        val c = URL("$base/api/upload?$q").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120_000 // the TV fsyncs a big video before answering
            c.setFixedLengthStreamingMode(length)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var sent = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    if (sent - lastReport >= 1_000_000 || sent == length) { onProgress(sent); lastReport = sent }
                }
                if (sent != length) throw IOException("File changed size while sending")
            }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (code != 200) throw IOException(json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "TV said HTTP $code")
            return json?.optString("path") ?: name
        } finally {
            c.disconnect()
        }
    }
}

/** Finds the TV via mDNS, which the TV advertises. Calls [onFound] on the main thread, at most once. */
class TvFinder(ctx: Context, private val onFound: (String) -> Unit) {
    private val app = ctx.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var done = false
    @Volatile private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { running = false }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onServiceFound(info: NsdServiceInfo) { if (!done) resolve(info) }
    }

    @Suppress("DEPRECATION") // resolveService: the replacement needs API 34
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceResolved(i: NsdServiceInfo) {
                val v4 = if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.firstOrNull { it is Inet4Address } else i.host
                val host = (v4 as? Inet4Address)?.hostAddress ?: return
                val url = "http://$host:${i.port}"
                main.post { if (!done) { done = true; stop(); onFound(url) } }
            }
        })
    }

    fun start() {
        if (running) return
        running = true
        runCatching {
            lock = app.getSystemService(WifiManager::class.java).createMulticastLock("photoframe-find").apply {
                setReferenceCounted(false); acquire()
            }
            nsd.discoverServices(Tv.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { running = false }
    }

    fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(discovery) }
        running = false
        runCatching { lock?.release() }
        lock = null
    }
}
