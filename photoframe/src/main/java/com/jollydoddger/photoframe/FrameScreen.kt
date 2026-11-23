package com.jollydoddger.photoframe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything on the frame's screen: the show, clock, info/QR panel, messages and the black
 * "off" screen. Shared by [FrameActivity] (the app) and [PhotoDream] (the screensaver).
 *
 * @param keepScreenOn asks the host to hold the screen on (true) or let the TV's own timer run.
 * @param introInfo show the QR/info panel for a while when the screen starts (the app does;
 *                  the screensaver doesn't, unless there's nothing to show yet).
 */
class FrameScreen(
    private val ctx: Context,
    private val keepScreenOn: (Boolean) -> Unit,
    private val introInfo: Boolean,
) : FrameState.Listener {
    private lateinit var stage: FrameLayout
    private lateinit var clock: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoBody: TextView
    private lateinit var qr: ImageView
    private lateinit var blackout: View
    private lateinit var flash: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var controller: ShowController? = null
    private var mixedPhaseWall = false
    private var libraryEmpty = false

    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

    private val clockTick = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now()
            clock.text = "${now.format(clockFmt)}\n${now.format(dateFmt)}"
            handler.postDelayed(this, 15_000)
        }
    }
    private val hideInfo = Runnable { if (!libraryEmpty) info.visibility = View.GONE }
    private val hideFlash = Runnable { flash.animate().alpha(0f).setDuration(400).start() }
    private val mixedSwitch = Runnable { mixedPhaseWall = !mixedPhaseWall; startShow() }
    private val refreshLibrary = Runnable {
        val c = controller
        val s = SettingsStore.current
        val items = MediaLibrary.items(s.albums, s.includeVideos)
        if (c == null || libraryEmpty || items.isEmpty()) startShow() else c.updateItems(items)
    }
    private val settingsListener: (FrameSettings) -> Unit = { startShow() }

    lateinit var root: FrameLayout
        private set

    init {
        SettingsStore.init(ctx)
        MediaLibrary.init(ctx)
        buildViews()
    }

    // ---------------- lifecycle ----------------

    fun start() {
        FrameState.add(this)
        SettingsStore.addListener(settingsListener)
        MediaLibrary.init(ctx) // the USB stick may have been plugged in since
        handler.post(clockTick)
        applyAwake(FrameState.awake)
        if (FrameState.awake && introInfo) showInfo(20_000)
    }

    fun stop() {
        FrameState.remove(this)
        SettingsStore.removeListener(settingsListener)
        handler.removeCallbacksAndMessages(null)
        controller?.stop()
        controller = null
    }

    // ---------------- UI ----------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun buildViews() {
        root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(ctx)
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        clock = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.END
            setShadowLayer(12f, 0f, 2f, Color.argb(200, 0, 0, 0))
            setLineSpacing(0f, 0.95f)
        }
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(40f), dp(28f))
        })

        info = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(20f), dp(28f), dp(20f))
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(Color.argb(215, 18, 18, 22))
            }
            visibility = View.GONE
        }
        qr = ImageView(ctx)
        info.addView(qr, LinearLayout.LayoutParams(dp(150f), dp(150f)).apply { marginEnd = dp(22f) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        infoTitle = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        infoBody = TextView(ctx).apply { setTextColor(Color.rgb(210, 210, 215)); textSize = 15f; setLineSpacing(dp(3f).toFloat(), 1f) }
        texts.addView(infoTitle)
        texts.addView(infoBody)
        info.addView(texts)
        root.addView(info, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(40f), 0, 0, dp(32f))
        })

        flash = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = dp(24f).toFloat(); setColor(Color.argb(190, 0, 0, 0)) }
            alpha = 0f
        }
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(36f)
        })

        blackout = View(ctx).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(blackout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    }

    private fun flashMessage(text: String) {
        flash.text = text
        flash.animate().cancel()
        flash.alpha = 1f
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 1800)
    }

    private fun showInfo(durationMs: Long) {
        val url = Net.uploadUrl()
        runCatching { qr.setImageBitmap(Qr.make(url, 300)) }
        val all = MediaLibrary.items()
        infoTitle.text = if (libraryEmpty) "Add photos from your phone" else "Photo Frame"
        val lines = mutableListOf(
            "Scan or open  $url",
            "Storage: ${MediaLibrary.storageLabel}",
            "${all.count { !it.isVideo }} photos · ${all.count { it.isVideo }} videos",
            "◀ ▶ skip   OK pause   ▲ change mode   ▼ this panel",
        )
        infoBody.text = lines.joinToString("\n")
        info.visibility = View.VISIBLE
        info.bringToFront()
        handler.removeCallbacks(hideInfo)
        if (durationMs > 0) handler.postDelayed(hideInfo, durationMs)
    }

    // ---------------- show logic ----------------

    private fun startShow() {
        handler.removeCallbacks(mixedSwitch)
        controller?.stop()
        controller = null
        stage.removeAllViews()
        val s = SettingsStore.current
        clock.visibility = if (s.showClock) View.VISIBLE else View.GONE
        if (!FrameState.awake) return

        val items = MediaLibrary.items(s.albums, s.includeVideos)
        libraryEmpty = items.isEmpty()
        if (libraryEmpty) { showInfo(0); return }
        if (info.visibility == View.VISIBLE) handler.postDelayed(hideInfo, 10_000)

        val photos = items.filter { !it.isVideo }
        val wall = (s.mode == "wall" || (s.mode == "mixed" && mixedPhaseWall)) && photos.size >= 2
        val c: ShowController = if (wall) WallController(stage, s, photos) else SlideshowController(stage, s, items)
        controller = c
        c.start()
        if (s.mode == "mixed") handler.postDelayed(mixedSwitch, s.mixedMinutes * 60_000L)
    }

    private fun applyAwake(awake: Boolean) {
        if (awake) {
            keepScreenOn(true)
            blackout.visibility = View.GONE
            startShow()
        } else {
            handler.removeCallbacks(mixedSwitch)
            controller?.stop()
            controller = null
            stage.removeAllViews()
            info.visibility = View.GONE
            clock.visibility = View.GONE
            blackout.visibility = View.VISIBLE
            blackout.bringToFront()
            // "sleep": let the TV's own screen-off/standby timer kick in
            if (SettingsStore.current.sleepAction == "sleep") keepScreenOn(false)
        }
    }

    // ---------------- FrameState.Listener ----------------

    override fun onAwakeChanged(awake: Boolean) = applyAwake(awake)

    override fun onLibraryChanged() {
        handler.removeCallbacks(refreshLibrary)
        handler.postDelayed(refreshLibrary, 2500)
    }

    override fun onCommand(cmd: String) {
        when (cmd) {
            "next" -> controller?.next()
            "prev" -> controller?.prev()
            "pause" -> togglePause()
            "info" -> showInfo(20_000)
            "reload" -> startShow()
        }
    }

    private fun togglePause() {
        val c = controller ?: return
        flashMessage(if (c.togglePause()) "❚❚  Paused" else "▶  Playing")
    }

    // ---------------- remote ----------------

    /** Remote keys while the app is open. @return true if handled. */
    fun onKey(keyCode: Int): Boolean {
        FrameState.touch()
        if (!FrameState.awake && keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_HOME) {
            FrameState.command("wake")
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller?.next(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller?.prev(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePause(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val modes = listOf("slideshow", "wall", "mixed")
                val next = modes[(modes.indexOf(SettingsStore.current.mode) + 1) % modes.size]
                SettingsStore.update(JSONObject().put("mode", next))
                flashMessage(when (next) { "wall" -> "Photo wall"; "mixed" -> "Mixed"; else -> "Slideshow" })
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (info.visibility == View.VISIBLE && !libraryEmpty) info.visibility = View.GONE else showInfo(30_000)
                return true
            }
        }
        return false
    }


}
