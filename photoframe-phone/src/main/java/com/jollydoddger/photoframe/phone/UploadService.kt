package com.jollydoddger.photoframe.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends shared photos/videos to the TV one at a time, in the background, with a progress
 * notification. Holds a wake lock and a Wi-Fi lock while busy, so a locked phone keeps going.
 * More shares while it's running simply join the queue.
 */
class UploadService : Service() {
    companion object {
        private const val EXTRA_ALBUM = "album"
        private const val EXTRA_BASE = "base"
        private const val CHANNEL = "uploads"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2

        fun send(ctx: Context, base: String, album: String, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val clip = ClipData.newRawUri("media", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            val i = Intent(ctx, UploadService::class.java)
                .putExtra(EXTRA_ALBUM, album)
                .putExtra(EXTRA_BASE, base)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // passes the share's read grant on to us
            i.clipData = clip
            ctx.startForegroundService(i)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    // Queueing (onStartCommand) and the "all done?" check both run here, so a share arriving
    // just as the last upload finishes can't be lost between the check and stopSelf().
    private val main = Handler(Looper.getMainLooper())
    private val queued = AtomicInteger()
    private val finished = AtomicInteger()
    private val failed = AtomicInteger()
    @Volatile private var lastError = ""
    @Volatile private var lastAlbum = ""
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads to the TV", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(progress("Starting…", 0, 0))
        val clip = intent?.clipData
        val base = intent?.getStringExtra(EXTRA_BASE)
        if (clip == null || base == null) { finishIfIdle(); return START_NOT_STICKY }
        val album = intent.getStringExtra(EXTRA_ALBUM).orEmpty()
        lastAlbum = album
        holdLocks()
        val uris = List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
        queued.addAndGet(uris.size)
        for (u in uris) worker.execute { sendOne(base, album, u); main.post { finishIfIdle() } }
        return START_NOT_STICKY
    }

    private fun sendOne(base: String, album: String, uri: Uri) {
        val meta = describe(uri)
        val index = finished.get() + 1
        val total = queued.get()
        nm.notify(ID_PROGRESS, progress("${meta.name} · $index of $total", 0, 0))
        try {
            var tmp: File? = null
            var length = meta.size
            if (length <= 0) {
                // Some apps don't say how big the file is, and the TV needs to know up front
                tmp = File.createTempFile("share", null, cacheDir)
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                length = tmp.length()
            }
            try {
                val input = tmp?.inputStream() ?: contentResolver.openInputStream(uri) ?: throw IOException("Can't open ${meta.name}")
                input.use {
                    Uploader(base).upload(it, length, meta.name, album, meta.taken) { sent ->
                        val pct = (sent * 100 / length).toInt()
                        nm.notify(ID_PROGRESS, progress("${meta.name} · $index of $total", pct, 100))
                    }
                }
            } finally {
                tmp?.delete()
            }
        } catch (e: Exception) {
            failed.incrementAndGet()
            lastError = "${meta.name}: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            finished.incrementAndGet()
        }
    }

    private class Meta(val name: String, val size: Long, val taken: Long)

    private fun describe(uri: Uri): Meta {
        var name = uri.lastPathSegment ?: "photo.jpg"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        // Gallery shares MediaStore items, which know when they were taken. The TV prefers a photo's
        // own EXIF date anyway; this is what keeps videos' dates right.
        var taken = 0L
        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) taken = c.getLong(0)
            }
        }
        if (!name.contains('.')) {
            val ext = contentResolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) name = "$name.$ext"
        }
        return Meta(name, size, taken)
    }

    private fun finishIfIdle() {
        if (finished.get() < queued.get()) return
        val ok = finished.get() - failed.get()
        val where = if (lastAlbum.isEmpty()) "the TV" else "“$lastAlbum”"
        val title = when {
            queued.get() == 0 -> null
            failed.get() == 0 -> "Sent $ok to $where"
            ok == 0 -> "Couldn't send to the TV"
            else -> "Sent $ok to $where, ${failed.get()} failed"
        }
        if (title != null) {
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .apply { if (failed.get() > 0) setContentText(lastError).setStyle(Notification.BigTextStyle().bigText(lastError)) }
                .build()
            nm.notify(ID_DONE, n)
        }
        queued.set(0); finished.set(0); failed.set(0)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progress(text: String, value: Int, max: Int): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sending to the TV")
            .setContentText(text)
            .setProgress(max, value, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
    }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photoframe:upload").apply { setReferenceCounted(false); acquire(2 * 60 * 60_000L) }
        if (wifi == null) wifi = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "photoframe:upload").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.release() }; wake = null
        runCatching { wifi?.release() }; wifi = null
    }

    override fun onDestroy() {
        releaseLocks()
        worker.shutdownNow()
        super.onDestroy()
    }
}
