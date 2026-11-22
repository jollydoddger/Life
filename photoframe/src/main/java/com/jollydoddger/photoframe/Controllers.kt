package com.jollydoddger.photoframe

import android.graphics.Color
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.random.Random

interface ShowController {
    fun start()
    fun stop()
    fun next()
    fun prev()
    /** @return true if now paused */
    fun togglePause(): Boolean
    fun updateItems(items: List<MediaItem>)
}

private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

// =====================================================================
// Full-screen slideshow: photos with crossfade / Ken Burns, videos inline
// =====================================================================
class SlideshowController(
    private val stage: FrameLayout,
    private val s: FrameSettings,
    items: List<MediaItem>,
) : ShowController {
    private val ctx = stage.context
    private val handler = Handler(Looper.getMainLooper())
    private val playlist = Playlist(items, s.shuffle)
    private var currentView: View? = null
    private var currentPlayer: MediaPlayer? = null
    private var paused = false
    private var stopped = false
    private var token = 0
    private val fadeMs = if (s.transition == "none") 0L else 1200L
    private val advance = Runnable { show(playlist.next()) }

    override fun start() {
        if (stage.width == 0) { stage.post { if (!stopped) start() }; return }
        show(playlist.next())
    }

    override fun stop() {
        stopped = true
        token++
        handler.removeCallbacksAndMessages(null)
        currentPlayer = null
        stage.removeAllViews() // releases any video player via onSurfaceTextureDestroyed
    }

    override fun next() { if (!stopped) { handler.removeCallbacks(advance); show(playlist.next()) } }
    override fun prev() { if (!stopped) { handler.removeCallbacks(advance); show(playlist.prev()) } }

    override fun togglePause(): Boolean {
        paused = !paused
        if (paused) {
            handler.removeCallbacks(advance)
            runCatching { currentPlayer?.pause() }
        } else {
            val p = currentPlayer
            if (p != null) {
                runCatching { p.start() }
                val remaining = runCatching { (p.duration - p.currentPosition).toLong() }.getOrDefault(s.intervalSec * 1000L)
                schedule(remaining.coerceIn(1000L, s.videoMaxSec * 1000L))
            } else schedule(s.intervalSec * 1000L)
        }
        return paused
    }

    override fun updateItems(items: List<MediaItem>) = playlist.update(items)

    private fun schedule(ms: Long) {
        handler.removeCallbacks(advance)
        if (!paused && !stopped) handler.postDelayed(advance, ms)
    }

    private fun show(item: MediaItem?) {
        if (item == null || stopped) return
        val t = ++token
        if (item.isVideo) { showVideo(item, t); return }
        val w = stage.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val h = stage.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        BitmapLoader.loadSlide(item.file, w, h, withBlur = s.fit == "blur") { bmp, blurred ->
            if (t != token || stopped) return@loadSlide
            if (bmp == null) { schedule(300); return@loadSlide } // unreadable file: skip
            swapIn(photoSlide(bmp, blurred, w, h), alreadyAdded = false)
            currentPlayer = null
            schedule(s.intervalSec * 1000L)
        }
    }

    private fun photoSlide(bmp: android.graphics.Bitmap, blurred: android.graphics.Bitmap?, w: Int, h: Int): View {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        if (blurred != null) {
            // A ~96 px pre-blurred copy, stretched with bilinear filtering: smooth, and static.
            val bg = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(blurred)
            }
            frame.addView(bg, matchParent())
        }
        val fg = ImageView(ctx).apply {
            scaleType = if (s.fit == "fill") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        frame.addView(fg, matchParent())
        if (s.transition == "kenburns") {
            val zoomIn = Random.nextBoolean()
            val from = if (zoomIn) 1.0f else 1.12f
            val to = if (zoomIn) 1.12f else 1.0f
            fg.scaleX = from; fg.scaleY = from
            fg.animate()
                .scaleX(to).scaleY(to)
                .translationX((Random.nextFloat() - 0.5f) * w * 0.05f)
                .translationY((Random.nextFloat() - 0.5f) * h * 0.05f)
                .setDuration(s.intervalSec * 1000L + fadeMs * 2)
                .setInterpolator(LinearInterpolator())
                .start()
        }
        return frame
    }

    private fun swapIn(v: View, alreadyAdded: Boolean) {
        val old = currentView
        currentView = v
        if (!alreadyAdded) {
            v.alpha = 0f
            stage.addView(v, matchParent())
        }
        v.bringToFront()
        v.animate().alpha(1f).setDuration(fadeMs).withEndAction {
            if (old != null && old !== currentView) stage.removeView(old)
        }.start()
    }

    private fun showVideo(item: MediaItem, t: Int) {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val tv = TextureView(ctx)
        frame.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        val mp = MediaPlayer()
        var released = false
        fun release() { if (!released) { released = true; runCatching { mp.release() } } }
        fun bail() {
            release()
            handler.post { if (frame !== currentView) stage.removeView(frame) }
            if (t == token) schedule(300)
        }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                try {
                    mp.setSurface(Surface(st))
                    mp.setDataSource(item.file.path)
                    if (!s.videoSound) mp.setVolume(0f, 0f)
                    mp.setOnPreparedListener { p ->
                        if (t != token || stopped) { bail(); return@setOnPreparedListener }
                        var vw = p.videoWidth
                        var vh = p.videoHeight
                        val rot = BitmapLoader.videoRotation(item.file)
                        if ((rot == 90 || rot == 270) && vw > vh) { val x = vw; vw = vh; vh = x }
                        if (vw > 0 && vh > 0) {
                            val scale = minOf(stage.width / vw.toFloat(), stage.height / vh.toFloat())
                            tv.layoutParams = FrameLayout.LayoutParams((vw * scale).toInt(), (vh * scale).toInt(), Gravity.CENTER)
                        } else tv.layoutParams = matchParent()
                        p.start()
                        if (paused) p.pause()
                        currentPlayer = p
                        swapIn(frame, alreadyAdded = true)
                        val dur = if (p.duration > 0) p.duration.toLong() else s.videoMaxSec * 1000L
                        schedule(minOf(dur + 300, s.videoMaxSec * 1000L))
                    }
                    mp.setOnCompletionListener { if (t == token) schedule(0) }
                    mp.setOnErrorListener { _, _, _ -> bail(); true }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    bail()
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (currentPlayer === mp) currentPlayer = null
                release()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // TextureView only gets a surface once attached, so add it invisibly first
        stage.addView(frame, matchParent())
    }
}

// =====================================================================
// Photo wall: a collage of tiles that swap one at a time
// =====================================================================
class WallController(
    private val stage: FrameLayout,
    private val s: FrameSettings,
    items: List<MediaItem>,
) : ShowController {
    private val ctx = stage.context
    private val handler = Handler(Looper.getMainLooper())
    private val playlist = Playlist(items.filter { !it.isVideo }, true)
    private val tiles = ArrayList<FrameLayout>()
    private var stopped = false
    private var paused = false
    private var lastTile = -1
    private val gap = (8 * ctx.resources.displayMetrics.density).toInt()
    private val swap = Runnable { swapRandom(); schedule() }

    override fun start() {
        if (stage.width == 0) { stage.post { if (!stopped) start() }; return }
        build()
        schedule()
    }

    override fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stage.removeAllViews()
        tiles.clear()
    }

    override fun next() { if (!stopped) { swapRandom(); schedule() } }
    override fun prev() { if (!stopped) { build(); schedule() } }

    override fun togglePause(): Boolean {
        paused = !paused
        if (paused) handler.removeCallbacks(swap) else schedule()
        return paused
    }

    override fun updateItems(items: List<MediaItem>) = playlist.update(items.filter { !it.isVideo })

    private fun schedule() {
        handler.removeCallbacks(swap)
        if (!paused && !stopped) handler.postDelayed(swap, (s.intervalSec * 1000L / 2).coerceAtLeast(3000L))
    }

    private fun grid(cols: Int, rows: Int) = (0 until rows).flatMap { r ->
        (0 until cols).map { c -> RectF(c / cols.toFloat(), r / rows.toFloat(), (c + 1) / cols.toFloat(), (r + 1) / rows.toFloat()) }
    }

    private fun template(): List<RectF> = when (s.wallLayout) {
        "grid" -> grid(3, 2)
        "grid12" -> grid(4, 3)
        else -> listOf( // one hero photo + four smaller
            RectF(0f, 0f, 0.5f, 1f),
            RectF(0.5f, 0f, 0.75f, 0.5f), RectF(0.75f, 0f, 1f, 0.5f),
            RectF(0.5f, 0.5f, 0.75f, 1f), RectF(0.75f, 0.5f, 1f, 1f),
        )
    }

    private fun build() {
        stage.removeAllViews()
        tiles.clear()
        val w = stage.width
        val h = stage.height
        template().forEachIndexed { i, r ->
            val tile = FrameLayout(ctx).apply {
                setBackgroundColor(0xFF101010.toInt())
                clipChildren = true
            }
            val lp = FrameLayout.LayoutParams(
                ((r.right - r.left) * w).toInt() - gap,
                ((r.bottom - r.top) * h).toInt() - gap,
            ).apply {
                leftMargin = (r.left * w).toInt() + gap / 2
                topMargin = (r.top * h).toInt() + gap / 2
            }
            stage.addView(tile, lp)
            tiles.add(tile)
            handler.postDelayed({ if (!stopped) loadInto(tile) }, i * 250L)
        }
    }

    private fun swapRandom() {
        if (tiles.isEmpty()) return
        var i = Random.nextInt(tiles.size)
        if (tiles.size > 1 && i == lastTile) i = (i + 1) % tiles.size
        lastTile = i
        loadInto(tiles[i])
    }

    private fun loadInto(tile: FrameLayout) {
        val item = playlist.next() ?: return
        val lp = tile.layoutParams
        BitmapLoader.load(item.file, lp.width.coerceAtLeast(64), lp.height.coerceAtLeast(64)) { bmp ->
            if (stopped || bmp == null || tile.parent == null) return@load
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
                alpha = 0f
            }
            tile.addView(iv, matchParent())
            iv.animate().alpha(1f).setDuration(1500).withEndAction {
                while (tile.childCount > 1) tile.removeViewAt(0)
            }.start()
        }
    }
}
