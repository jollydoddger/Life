package com.jollydoddger.waymark

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The watch updating itself, so a new build no longer needs ADB.
 *
 * *"Can we please integrate an update option into both of these apps so I
 * don't have to adb every time."* Every watch build meant developer
 * options, wireless debugging, a computer on the same Wi-Fi and
 * `adb install`.
 *
 * Waymark's watch can fetch for itself: it is a standalone app with its own
 * internet, and the repository is public, so the release asset
 * `waymark-watch.apk` is one unauthenticated request away. Newness is the
 * asset's upload time against when this copy was installed — the version is
 * the CI run number, which says nothing about a build installed from a
 * branch. The APK goes to the cache and then to Android's own installer as
 * an update of this app; same package and same committed key, so Android
 * takes it the way it takes any update.
 *
 * **What is not known until it is tried on his watch:** whether Wear OS
 * lets an app install over itself without the one-time "install unknown
 * apps" grant, which a watch usually has no screen for. Every failure that
 * might be that names the single ADB line which grants it for good — one
 * last ADB session rather than one per build.
 */
object WatchUpdate {

    private const val TAG = "WatchUpdate"

    private const val RELEASE =
        "https://api.github.com/repos/jollydoddger/Life/releases/tags/latest-debug"
    private const val ASSET = "waymark-watch.apk"

    /** How often opening the app may look for a new build on its own. */
    private const val CHECK_EVERY_MS = 6 * 3_600_000L

    /** An upload a few minutes after the build must not read as newer than it. */
    private const val SLACK_MS = 5 * 60_000L

    private const val PREFS = "watch_update"

    /** A newer build on the shelf. */
    data class Latest(val url: String, val builtAt: Long, val bytes: Long)

    /**
     * What the banner says. [offer] is a build waiting to be installed with
     * a tap; [busy] holds the screen on and takes taps away.
     */
    data class State(val text: String? = null, val busy: Boolean = false, val offer: Latest? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun busy(text: String) {
        _state.value = State(text, busy = true)
    }

    fun finish(text: String?) {
        _state.value = State(text)
    }

    fun dismiss() {
        if (!_state.value.busy) _state.value = State()
    }

    /**
     * Looks for a newer build. Quiet ([force] false) means at most every
     * six hours and saying nothing unless there is one — a banner about a
     * failed check he never asked for is noise. Forced means now, and says
     * how it went either way.
     */
    suspend fun check(context: Context, force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checkedAt", 0L) < CHECK_EVERY_MS) return
        if (_state.value.busy) return
        if (force) busy("Checking for a new build…")

        val found = withContext(Dispatchers.IO) { runCatching { latest() } }
        prefs.edit().putLong("checkedAt", now).apply()

        val latest = found.getOrElse {
            Log.w(TAG, "could not read the release", it)
            if (force) finish("Couldn't reach GitHub — ${it.message ?: "no answer"}.")
            return
        }
        when {
            latest == null -> if (force) finish("The release has no $ASSET in it.")
            latest.builtAt <= installedAt(context) + SLACK_MS ->
                if (force) finish("Up to date — this is build ${installedBuild(context)}.")
            else -> _state.value = State(
                text = "New watch build from ${WHEN.format(Date(latest.builtAt))} — tap to install",
                offer = latest,
            )
        }
    }

    /** Downloads [latest] and installs it over this app. */
    suspend fun install(context: Context, latest: Latest) {
        busy("Downloading the new build…")
        val file = File(File(context.cacheDir, "update").apply { mkdirs() }, ASSET)
        val ok = withContext(Dispatchers.IO) { download(latest, file) }
        if (!ok) {
            finish("The download didn't finish. Try again on a better connection.")
            return
        }
        withContext(Dispatchers.IO) { commit(context, file) }
    }

    private fun latest(): Latest? {
        val connection = (URL(RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Waymark")
        }
        val body = try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub answered ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val assets = JSONObject(body).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != ASSET) continue
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) return null
            return Latest(url, stamp(asset.optString("updated_at")), asset.optLong("size"))
        }
        return null
    }

    private fun download(latest: Latest, file: File): Boolean {
        runCatching { file.delete() }
        val ok = runCatching {
            val connection = (URL(latest.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Waymark")
            }
            try {
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var told = -1L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                            if (latest.bytes > 0) {
                                val step = total * 100 / latest.bytes / 10 * 10
                                if (step > told) {
                                    told = step
                                    busy("Downloading the new build — $step%")
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            // A short file installs as a corrupt package with an error that
            // says nothing; caught here, where the reason is known.
            latest.bytes <= 0 || file.length() == latest.bytes
        }.onFailure { Log.w(TAG, "download failed", it) }.getOrDefault(false)
        if (!ok) runCatching { file.delete() }
        return ok
    }

    /**
     * Hands [apk] to the installer as an update of this app. A session,
     * because a watch has no file manager to take an `ACTION_VIEW` and a
     * session is the platform's own API for an app updating itself.
     * `USER_ACTION_NOT_REQUIRED` lets a self-update go through without a
     * confirmation where Android allows it (12 and up); where it does not,
     * [InstallResultReceiver] puts the installer's own screen up.
     */
    private fun commit(context: Context, apk: File) {
        busy("Installing… Waymark will close and come back on the new build.")
        val failure = runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                // Mutable so the installer can fill the result in; explicit
                // because Android 14 refuses a mutable implicit one.
                val result = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        }.exceptionOrNull()
        if (failure != null) {
            Log.e(TAG, "could not start the install", failure)
            finish("Couldn't start the install: ${failure.message ?: "unknown error"}.${grantHint(context)}")
        }
    }

    /**
     * The one-time ADB line, said only when it is the likely cause: Android
     * has not let Waymark install apps, and a watch often has no screen to
     * allow it on.
     */
    fun grantHint(context: Context): String =
        if (!context.packageManager.canRequestPackageInstalls()) {
            " Waymark isn't allowed to install apps yet. Run once over ADB: " +
                "adb shell appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow"
        } else {
            ""
        }

    fun installedAt(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    fun installedBuild(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun stamp(iso: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(iso)?.time ?: 0L
    }.getOrDefault(0L)

    private val WHEN = SimpleDateFormat("EEE d MMM HH:mm", Locale.UK)
}

/** What the installer made of the update. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    WatchUpdate.finish("Android wanted a confirmation and gave nothing to show.${WatchUpdate.grantHint(context)}")
                    return
                }
                val shown = runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (shown) WatchUpdate.busy("Confirm the install on the screen that opened.")
                else WatchUpdate.finish("The watch wouldn't show the install screen. Open Waymark and try again.")
            }
            // Rarely seen: a successful update replaces this process.
            PackageInstaller.STATUS_SUCCESS -> WatchUpdate.finish("Updated.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> WatchUpdate.finish("Install cancelled.")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                WatchUpdate.finish(
                    "The install failed${if (why.isNotBlank()) ": $why" else ""}.${WatchUpdate.grantHint(context)}"
                )
            }
        }
    }
}
