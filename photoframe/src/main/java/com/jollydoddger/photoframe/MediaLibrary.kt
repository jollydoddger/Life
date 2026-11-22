package com.jollydoddger.photoframe

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** [mtime] is read once at scan time: sorting thousands of files by stat()-ing a USB stick inside a comparator is slow. */
data class MediaItem(val file: File, val album: String, val isVideo: Boolean, val mtime: Long = file.lastModified())

/**
 * Where the photos live. Preference order:
 *  1. A "PhotoFrame" folder in the root of the USB stick (needs All-files access, granted once via ADB or Settings)
 *  2. The app's own folder on the USB stick (no permission needed, but wiped if the app is uninstalled)
 *  3. The TV's internal storage
 */
object MediaLibrary {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp")

    @Volatile var root: File = File("/")
        private set
    @Volatile var storageLabel = ""
        private set
    @Volatile var onUsb = false
        private set
    @Volatile var hasAllFilesAccess = false
        private set

    @Synchronized
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        var chosen: File? = null
        var label = ""
        var usb = false

        hasAllFilesAccess = false
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            hasAllFilesAccess = true
            val sm = app.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                if (!v.isRemovable || v.state != Environment.MEDIA_MOUNTED) continue
                val dir = v.directory ?: continue
                val f = File(dir, "PhotoFrame")
                if ((f.isDirectory || f.mkdirs()) && f.canWrite()) {
                    chosen = f
                    label = "USB stick (${v.getDescription(app)}) › PhotoFrame"
                    usb = true
                    break
                }
            }
        }

        if (chosen == null) {
            for (d in app.getExternalFilesDirs(null)) {
                if (d == null) continue
                val removable = runCatching { Environment.isExternalStorageRemovable(d) }.getOrDefault(false)
                if (!removable) continue
                val f = File(d, "media")
                if ((f.isDirectory || f.mkdirs()) && f.canWrite()) {
                    chosen = f
                    label = "USB stick (app folder — grant All-files access to use a normal folder)"
                    usb = true
                    break
                }
            }
        }

        if (chosen == null) {
            val base = app.getExternalFilesDir(null) ?: app.filesDir
            val f = File(base, "media")
            f.mkdirs()
            chosen = f
            label = "TV internal storage (no USB stick found)"
        }

        useRoot(chosen ?: File(app.filesDir, "media"), label, usb)
    }

    /** Points the library at [dir]. Called by [init]; also lets the JVM tests use a temp folder. */
    @Synchronized
    fun useRoot(dir: File, label: String = dir.path, usb: Boolean = false) {
        root = dir
        storageLabel = label
        onUsb = usb
    }

    fun kind(f: File): Boolean? = when (f.extension.lowercase()) {
        in IMAGE_EXT -> false
        in VIDEO_EXT -> true
        else -> null
    }

    fun albums(): List<String> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sortedBy { it.lowercase() }
            ?: emptyList()

    fun items(albumFilter: List<String> = emptyList(), includeVideos: Boolean = true, album: String? = null): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        fun scan(dir: File, name: String) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.startsWith(".")) {
                    val v = kind(f) ?: return@forEach
                    if (includeVideos || !v) out.add(MediaItem(f, name, v))
                }
            }
        }
        if (album != null) {
            scan(if (album.isEmpty()) root else File(root, album), album)
            return out
        }
        if (albumFilter.isEmpty() || "" in albumFilter) scan(root, "")
        for (a in albums()) if (albumFilter.isEmpty() || a in albumFilter) scan(File(root, a), a)
        return out
    }

    fun relPath(f: File): String = f.canonicalPath.removePrefix(root.canonicalPath).trimStart('/')

    /** Resolves a client-supplied relative path, refusing anything outside the library. */
    fun resolve(rel: String): File? {
        val base = root.canonicalPath
        val f = File(root, rel).canonicalFile
        return if (f.path.startsWith(base + File.separator) && f.exists()) f else null
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().trimStart('.').take(120).ifBlank { "file" }

    fun albumDir(album: String): File {
        if (album.isBlank()) return root
        return File(root, sanitize(album)).apply { mkdirs() }
    }

    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return f
    }
}
