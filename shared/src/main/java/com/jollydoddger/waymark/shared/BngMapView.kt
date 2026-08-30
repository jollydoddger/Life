package com.jollydoddger.waymark.shared

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One public right of way: its geometry in grid metres as [e0,n0,e1,n1,…]
 * and which kind of right it carries, since a bridleway and a footpath are
 * not the same permission.
 */
class ProwLine(val kind: Int, val pts: FloatArray)

/**
 * The map: OS Leisure tiles, the route line with direction arrows, and the
 * you-arrow — all in British National Grid metres with a plain linear
 * transform to the screen. North-up always; it is a paper map.
 *
 * Zoom is continuous ([zl]: metres-per-pixel = 896 / 2^zl, so whole numbers
 * land on the OS pyramid's own levels). Tiles are drawn from the pyramid
 * level nearest the current zoom minus a density bias, so the paper map's
 * lettering comes out roughly physical-print size on a dense screen rather
 * than microscopic. While a tile loads, its area is filled by scaling up the
 * nearest coarser tile already in memory.
 */
class BngMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    val tiles = TileStore(context).also { it.onTileReady = { postInvalidateOnAnimation() } }

    private val density = resources.displayMetrics.density
    private val bias = min(ln(density.toDouble()) / ln(2.0), 1.25)
    private val minZl = 1.0
    // +1.0 past the density-corrected native level = 2x magnification of
    // OS's finest tiles: bigger for tired eyes and gloved taps, still legible.
    private val maxZl = TileGrid.MAX_Z + bias + 1.0

    private var centreE = 400_000.0 // mid-GB until a fix or a route arrives
    private var centreN = 300_000.0
    private var zl = 6.0

    private var route: Route? = null
    private var routePts: List<En> = emptyList() // decimated for drawing
    private var cumDist: DoubleArray = DoubleArray(0)

    var routeReversed = false
        set(v) { field = v; invalidate() }

    /** Where he has already been this walk; drawn under the route. */
    private var trailPts: List<En> = emptyList()

    fun setTrail(points: List<En>) {
        trailPts = points
        invalidate()
    }

    /** What the assistant found — toilets, cafés, bins — as glyph markers. */
    private var pois: List<Poi> = emptyList()

    fun setPois(list: List<Poi>) {
        pois = list
        invalidate()
    }

    /**
     * A walk being looked at but not yet adopted — dashed, over the route, so
     * "this is what you'd get" never masquerades as "this is what you have".
     * Kept as separate polylines: an OSM relation's members can be gappy, and
     * joining them up would draw lines the walk doesn't contain.
     */
    private var previewLines: List<List<En>> = emptyList()

    fun setPreview(lines: List<List<En>>) {
        previewLines = lines
        invalidate()
    }

    /**
     * Mark-points mode: while on, a tap picks the nearest point of the route
     * instead of zooming. A mode rather than tap-proximity guessing — his
     * design, and the better one: a mode you switched on cannot fire by
     * accident, and it steals nothing from the zoom gestures when off.
     */
    var pickMode = false
        set(v) { field = v; invalidate() }

    /** Called with the snapped route point and its distance along the route
     *  (from the route's stored start, ignoring direction). */
    var onRoutePointPicked: ((En, Double) -> Unit)? = null

    /** Numbered flags at his marked points. */
    private var marks: List<Pair<En, Int>> = emptyList()

    fun setMarks(list: List<Pair<En, Int>>) {
        marks = list
        invalidate()
    }

    /**
     * Public GPS traces (phone only): each cell is a flat [e0,n0,e1,n1,…]
     * array of dots, drawn under everything the app itself owns.
     */
    private var traceCells: List<FloatArray> = emptyList()
    private var traceScratch = FloatArray(0)

    fun setTraces(cells: List<FloatArray>) {
        traceCells = cells
        val largest = cells.maxOfOrNull { it.size } ?: 0
        if (traceScratch.size < largest) traceScratch = FloatArray(largest)
        invalidate()
    }

    /**
     * Public rights of way (phone only): what he is legally entitled to
     * walk, drawn over the traces but under his own route and trail.
     */
    private var prowLines: List<ProwLine> = emptyList()

    fun setProw(lines: List<ProwLine>) {
        prowLines = lines
        invalidate()
    }

    /**
     * A bitmap that belongs on a lat/lon box rather than on the grid: a
     * rainfall-radar tile, or a rendered weather field. Both arrive in the
     * web's projection and this map lives in the National Grid, so each is
     * drawn through a warped mesh rather than pretending the two agree.
     */
    class MeshTile(
        val bitmap: android.graphics.Bitmap,
        val south: Double, val west: Double, val north: Double, val east: Double,
    )

    private var radarTiles: List<MeshTile> = emptyList()

    fun setRadar(tiles: List<MeshTile>) {
        radarTiles = tiles
        invalidate()
    }

    /** West, south, east, north of the visible map, in grid metres. */
    fun viewportBounds(): DoubleArray {
        val m = mpp(zl)
        return doubleArrayOf(
            centreE - width / 2.0 * m, centreN - height / 2.0 * m,
            centreE + width / 2.0 * m, centreN + height / 2.0 * m,
        )
    }

    /**
     * Fires ~600 ms after the viewport last moved (pan, zoom, or the fix
     * dragging a following map) — the hook the traces overlay fetches on,
     * so a fling across the country doesn't request every cell it crossed.
     */
    var onViewportSettled: (() -> Unit)? = null
    private val settleRunnable = Runnable { onViewportSettled?.invoke() }

    private fun viewportChanged() {
        // The streamline trails live in screen pixels, so a pan or a zoom
        // leaves them describing wind that was somewhere else. Wiped rather
        // than reprojected: they redraw in about a second.
        windTrail?.eraseColor(Color.TRANSPARENT)
        if (onViewportSettled == null) return
        removeCallbacks(settleRunnable)
        postDelayed(settleRunnable, 600)
    }

    private var fixE = 0.0
    private var fixN = 0.0
    private var hasFix = false
    private var fixStale = false
    private var headingDeg: Double? = null

    /** Follow-mode: the map tracks the fix until a drag says otherwise. */
    var follow = true
        private set(v) {
            if (field != v) { field = v; onFollowChanged?.invoke(v) }
        }
    var onFollowChanged: ((Boolean) -> Unit)? = null

    private fun mpp(z: Double) = 896.0 / 2.0.pow(z)

    // --- public surface -----------------------------------------------------

    fun setRoute(r: Route?) {
        route = r
        if (r == null) {
            routePts = emptyList(); cumDist = DoubleArray(0)
        } else {
            // Cap the drawn polyline; a 20k-point GPX gains nothing on screen.
            val stride = (r.points.size / 1500) + 1
            routePts = r.points.filterIndexed { i, _ -> i % stride == 0 || i == r.points.size - 1 }
            cumDist = DoubleArray(routePts.size)
            for (i in 1 until routePts.size) {
                cumDist[i] = cumDist[i - 1] +
                    hypot(routePts[i].e - routePts[i - 1].e, routePts[i].n - routePts[i - 1].n)
            }
            if (!hasFix) centreOnRoute()
        }
        invalidate()
    }

    fun setFix(e: Double, n: Double, stale: Boolean) {
        fixE = e; fixN = n; hasFix = true; fixStale = stale
        if (follow) { centreE = e; centreN = n; viewportChanged() }
        invalidate()
    }

    /** Grid bearing, degrees clockwise from grid north; null = no compass. */
    fun setHeading(deg: Double?) {
        headingDeg = deg
        invalidate()
    }

    /** His colours, from Prefs on the phone and over the link on the watch. */
    fun setColours(route: Int, arrow: Int, trail: Int) {
        routePaint.color = route
        routeArrowPaint.color = route
        herePaint.color = arrow
        trailPaint.color = trail
        invalidate()
    }

    fun zoomIn() = setZoom(zl + 1.0)
    fun zoomOut() = setZoom(zl - 1.0)

    /**
     * Follow me again — at whatever zoom the map is already at. It used to
     * zoom right in as well; he asked for that once and unasked it: the
     * button answers "where am I", and the zoom is his. With no fix yet it
     * centres on the route instead, again without touching the zoom —
     * re-fitting belongs to the first-layout path, not to a button pressed
     * on a map already set up the way he wants it.
     */
    fun recentre() {
        follow = true
        if (hasFix) {
            centreE = fixE; centreN = fixN
        } else {
            routePts.takeIf { it.isNotEmpty() }?.let { pts ->
                centreE = (pts.minOf { it.e } + pts.maxOf { it.e }) / 2
                centreN = (pts.minOf { it.n } + pts.maxOf { it.n }) / 2
            }
        }
        viewportChanged()
        invalidate()
    }

    private fun setZoom(z: Double) {
        zl = z.coerceIn(minZl, maxZl)
        viewportChanged()
        invalidate()
    }

    private fun centreOnRoute() = fit(routePts)

    /** Pan and zoom so [pts] fills the screen — how a preview is shown. */
    fun fitTo(pts: List<En>) {
        if (pts.isEmpty()) return
        follow = false
        fit(pts)
        invalidate()
    }

    private fun fit(pts: List<En>) {
        if (pts.isEmpty()) return
        centreE = (pts.minOf { it.e } + pts.maxOf { it.e }) / 2
        centreN = (pts.minOf { it.n } + pts.maxOf { it.n }) / 2
        if (width > 0) {
            val spanE = (pts.maxOf { it.e } - pts.minOf { it.e }) * 1.3 + 1.0
            val spanN = (pts.maxOf { it.n } - pts.minOf { it.n }) * 1.3 + 1.0
            val need = maxOf(spanE / width, spanN / height)
            setZoom(ln(896.0 / need) / ln(2.0))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A route set before layout couldn't pick its zoom; do it now.
        if (oldw == 0 && routePts.isNotEmpty() && !hasFix) centreOnRoute()
        viewportChanged()
    }

    // --- drawing ------------------------------------------------------------

    private val tilePaint = Paint().apply { isFilterBitmap = true }
    private val bgPaint = Paint().apply { color = Color.rgb(232, 234, 229) }
    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(190, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Colours.DEFAULT_ROUTE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Colours.DEFAULT_TRAIL
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    // The route's direction arrowheads and the you-arrow are separately
    // coloured, so neither can be left wearing the other's colour.
    private val routeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colours.DEFAULT_ROUTE }
    private val herePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colours.DEFAULT_ARROW }
    private val staleHerePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 128, 128, 128) }
    private val arrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val path = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawTiles(canvas)
        // Weather sits directly on the map paper and under everything he is
        // navigating by: a rain cell must never hide the line he is walking.
        drawField(canvas)
        drawRadar(canvas)
        // His stacking order, bottom to top: the GPX route first (able to be
        // covered), then the information layers — dots and rights of way —
        // then the trail he has actually walked on top of everything.
        drawRoute(canvas)
        drawTraces(canvas)
        drawProw(canvas)
        drawTrail(canvas)
        drawPreview(canvas)
        drawPois(canvas)
        drawRouteEnds(canvas)
        drawMarks(canvas)
        drawStreamlines(canvas)
        drawWind(canvas)
        drawHere(canvas)
    }

    private fun sx(e: Double, m: Double) = (width / 2f + (e - centreE) / m).toFloat()
    private fun sy(n: Double, m: Double) = (height / 2f - (n - centreN) / m).toFloat()

    private fun drawTiles(canvas: Canvas) {
        val m = mpp(zl)
        val level = (zl - bias).roundToInt().coerceIn(0, TileGrid.MAX_Z)
        val scale = TileGrid.METRES_PER_PX[level] / m // tile px → screen px
        val span = TileGrid.tileSpan(level)

        val west = centreE - width / 2.0 * m
        val east = centreE + width / 2.0 * m
        val north = centreN + height / 2.0 * m
        val south = centreN - height / 2.0 * m
        val x0 = TileGrid.tileX(west, level)
        val x1 = TileGrid.tileX(east, level)
        val y0 = TileGrid.tileY(north, level)
        val y1 = TileGrid.tileY(south, level)

        for (x in x0..x1) for (y in y0..y1) {
            val left = sx(TileGrid.tileWest(x, level), m)
            val top = sy(TileGrid.tileNorth(y, level), m)
            val size = (span / m).toFloat()
            dstRect.set(left, top, left + size, top + size)

            val bmp = tiles.bitmap(level, x, y)
            if (bmp != null) {
                srcRect.set(0, 0, bmp.width, bmp.height)
                canvas.drawBitmap(bmp, srcRect, dstRect, tilePaint)
            } else {
                // A coarser tile already in memory beats a grey square.
                for (up in 1..3) {
                    val zp = level - up
                    if (zp < 0) break
                    val parent = tiles.peek(zp, x shr up, y shr up) ?: continue
                    val q = TileGrid.TILE_PX shr up
                    val sxq = (x and ((1 shl up) - 1)) * q
                    val syq = (y and ((1 shl up) - 1)) * q
                    srcRect.set(sxq, syq, sxq + q, syq + q)
                    canvas.drawBitmap(parent, srcRect, dstRect, tilePaint)
                    break
                }
            }
        }
    }

    // Bright red, flashing: even the faintest trace on a footpath has to be
    // unmissable. The pulse bottoms out well above invisible on purpose — a
    // dot that vanishes half the time is a dot that can be missed, which is
    // the exact failure this exists to prevent.
    private val tracePaint = Paint().apply {
        color = Color.rgb(255, 40, 40)
        strokeCap = Paint.Cap.ROUND
    }
    private val tracePulse = Runnable { if (traceCells.isNotEmpty()) invalidate() }

    private fun drawTraces(canvas: Canvas) {
        if (traceCells.isEmpty()) return
        val m = mpp(zl)
        // One-second pulse between ~45% and full opacity: clearly flashing,
        // never gone.
        val phase = (System.currentTimeMillis() % 1000L) / 1000.0
        tracePaint.alpha = (115 + 140 * (0.5 - 0.5 * cos(2 * PI * phase))).toInt()
        tracePaint.strokeWidth = 5f * density
        for (cell in traceCells) {
            var n = 0
            var i = 0
            while (i + 1 < cell.size) {
                traceScratch[n++] = sx(cell[i].toDouble(), m)
                traceScratch[n++] = sy(cell[i + 1].toDouble(), m)
                i += 2
            }
            canvas.drawPoints(traceScratch, 0, n, tracePaint)
        }
        // Keep the flash alive while dots are on screen — one pending tick,
        // self-stopping the moment the cells are cleared.
        removeCallbacks(tracePulse)
        postDelayed(tracePulse, 50)
    }

    /**
     * Rain has to be seen at a glance on a bright OS sheet in daylight, so
     * this is deliberately heavy: nearly opaque, and drawn twice.
     *
     * The second pass is the part that matters. The radar PNG carries its own
     * alpha, so a light shower arrives as a nearly transparent wash that
     * vanishes over pale paper however high this alpha goes — the ceiling is
     * the tile's alpha, not the paint's. Compositing the same tile over
     * itself squares the transparency instead, which lifts the faint returns
     * hard while leaving heavy rain looking the same. Light rain is exactly
     * the rain worth warning about.
     */
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 235 }
    private val RADAR_PASSES = 2

    /**
     * How strongly the weather is painted, as a fraction of each layer's own
     * weight. It is his dial, not a constant: the same rain that reads
     * perfectly in a dark kitchen buries the contours in bright sun, and the
     * map underneath is what he is actually navigating by.
     */
    private var weatherOpacity = 0.85f
    private var radarBaseAlpha = 235
    private var fieldBaseAlpha = 150

    // Declared above [applyWeatherAlpha] rather than beside the field drawing
    // it belongs to, because that function dereferences it. Both its callers
    // happen to be public and post-construction today, so the order is safe
    // by luck; anything calling it from an init block or onSizeChanged would
    // get a null on a non-null property, with no warning of any kind.
    private var fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
    private var skyPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setWeatherOpacity(percent: Int) {
        weatherOpacity = (percent.coerceIn(0, 100)) / 100f
        applyWeatherAlpha()
        invalidate()
    }

    private fun applyWeatherAlpha() {
        radarPaint.alpha = (radarBaseAlpha * weatherOpacity).toInt().coerceIn(0, 255)
        fieldPaint.alpha = (fieldBaseAlpha * weatherOpacity).toInt().coerceIn(0, 255)
        skyPaint.alpha = (255 * weatherOpacity).toInt().coerceIn(0, 255)
    }

    /** Mesh resolution per tile: 6x6 quads is plenty at county scale. */
    private val radarMeshN = 6
    private val radarMesh = FloatArray((radarMeshN + 1) * (radarMeshN + 1) * 2)

    private fun drawRadar(canvas: Canvas) {
        if (radarTiles.isEmpty()) return
        val m = mpp(zl)
        for (t in radarTiles) {
            // Mercator latitude runs non-linearly; interpolate in Mercator Y
            // so each mesh row lands where that slice of the bitmap belongs.
            val yN = mercY(t.north)
            val yS = mercY(t.south)
            var i = 0
            for (r in 0..radarMeshN) {
                val lat = invMercY(yN + (yS - yN) * r / radarMeshN)
                for (c in 0..radarMeshN) {
                    val lon = t.west + (t.east - t.west) * c / radarMeshN
                    val en = Bng.fromWgs84(lat, lon)
                    radarMesh[i++] = sx(en.e, m)
                    radarMesh[i++] = sy(en.n, m)
                }
            }
            repeat(RADAR_PASSES) {
                canvas.drawBitmapMesh(
                    t.bitmap, radarMeshN, radarMeshN, radarMesh, 0, null, 0, radarPaint,
                )
            }
        }
    }

    /**
     * A rendered weather field — temperature, cloud, forecast rain — drawn
     * exactly like a radar tile, because it is the same problem: a bitmap on
     * a lat/lon box that has to be bent onto the grid. One at a time: two
     * colour washes over each other say nothing legible.
     */
    private var fieldTile: MeshTile? = null

    /**
     * The sky wash — cloud and fog — its own slot under the rain wash. The
     * old one-wash-at-a-time rule dated from when temperature was a wash
     * too: an opaque full-bleed ramp under anything said nothing. Cloud is
     * thin grey and rain saturated colour, and they layer legibly — which
     * is what makes the weather one picture instead of a choice.
     */
    private var skyTile: MeshTile? = null

    fun setSky(tile: MeshTile?) {
        skyTile = tile
        invalidate()
    }

    fun setField(tile: MeshTile?, alpha: Int) {
        fieldTile = tile
        fieldBaseAlpha = alpha
        applyWeatherAlpha()
        invalidate()
    }

    private fun drawField(canvas: Canvas) {
        skyTile?.let { meshTile(canvas, it, skyPaint) }
        fieldTile?.let { meshTile(canvas, it, fieldPaint) }
    }

    private fun meshTile(canvas: Canvas, t: MeshTile, paint: Paint) {
        val m = mpp(zl)
        val yN = mercY(t.north)
        val yS = mercY(t.south)
        var i = 0
        for (r in 0..radarMeshN) {
            val lat = invMercY(yN + (yS - yN) * r / radarMeshN)
            for (c in 0..radarMeshN) {
                val lon = t.west + (t.east - t.west) * c / radarMeshN
                val en = Bng.fromWgs84(lat, lon)
                radarMesh[i++] = sx(en.e, m)
                radarMesh[i++] = sy(en.n, m)
            }
        }
        canvas.drawBitmapMesh(t.bitmap, radarMeshN, radarMeshN, radarMesh, 0, null, 0, paint)
    }

    /**
     * One wind reading, placed on the grid. [fromDeg] is the direction the
     * wind blows *from*, which is how every forecast in the country states it
     * — and the opposite of the way the arrow points, which is the way it is
     * going. Both are needed: the number to compare against a forecast, the
     * arrow to see at a glance whether it is behind you on the climb.
     */
    class WindArrow(
        val e: Double, val n: Double,
        val speedMph: Double, val fromDeg: Double,
    )

    private var windArrows: List<WindArrow> = emptyList()

    fun setWind(arrows: List<WindArrow>) {
        windArrows = arrows
        invalidate()
    }

    private val windCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(210, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val windPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val windFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // --- wind as drifting streamlines ---------------------------------------

    /**
     * The wind over the viewport as two components in metres per second —
     * [u] eastward, [v] northward — on a regular grid covering the box from
     * ([west], [south]) to ([east], [north]) in National Grid metres. Row 0
     * is the northern edge, column 0 the western, matching the forecast grid
     * it is built from.
     *
     * Components rather than speed and bearing because the animation
     * interpolates between neighbouring readings every frame, and averaging
     * two bearings either side of north gives south.
     */
    class WindGrid(
        val west: Double, val south: Double, val east: Double, val north: Double,
        val n: Int, val u: DoubleArray, val v: DoubleArray,
    )

    /**
     * One drifting particle. It carries where it was as well as where it is,
     * because what gets drawn is the segment between the two — the trail is
     * the picture, not the dot.
     */
    private class Mote(var x: Float, var y: Float, var px: Float, var py: Float, var life: Int)

    private var windGrid: WindGrid? = null
    private var windStreamlines = true
    private val motes = ArrayList<Mote>()
    private var windTrail: Bitmap? = null
    private var windTrailCanvas: Canvas? = null
    private var lastWindFrameMs = 0L

    /** How many pixels a second the air appears to move, per mph of real
     *  wind. Real speed at map scale is imperceptible — a 20 mph gale would
     *  cross a viewport in three minutes — so this is honest exaggeration,
     *  and the arrows and the readout carry the actual number. */
    private val PX_PER_MPH = 2.6f

    /** Enough to read as flow, few enough to draw in a couple of
     *  milliseconds. Scaled to the screen so a tablet is not sparse. */
    private fun moteCount(): Int = ((width * height) / (9_000 * density)).toInt().coerceIn(120, 700)

    /** How fast a trail fades. Higher is shorter. */
    // 16, halved from 30 after "can hardly see em": a lower fade is a
    // roughly doubled tail, and the tail is the picture.
    private val TRAIL_FADE = 16

    /** Frames redraw at about 30 a second; a walking map does not need 60,
     *  and this runs while he is out with the screen on. */
    private val FRAME_MS = 33L

    private val windFade = Paint().apply {
        color = Color.argb(TRAIL_FADE, 0, 0, 0)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }
    private val windBlit = Paint()
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val windTick = Runnable { stepWind() }

    fun setWind(grid: WindGrid?, streamlines: Boolean) {
        windGrid = grid
        windStreamlines = streamlines
        if (grid == null || !streamlines) {
            removeCallbacks(windTick)
            motes.clear()
            windTrail?.eraseColor(Color.TRANSPARENT)
        } else {
            lastWindFrameMs = 0L
            removeCallbacks(windTick)
            post(windTick)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // Nothing about a map off the screen is worth thirty frames a second
        // of somebody's battery.
        removeCallbacks(windTick)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(windTick)
        if (visibility == VISIBLE && windGrid != null && windStreamlines) post(windTick)
    }

    /** The wind at a point on screen, as pixels of travel per second. */
    private fun sampleWind(g: WindGrid, x: Float, y: Float, m: Double, out: FloatArray): Boolean {
        val e = (x - width / 2f) * m + centreE
        val n = centreN - (y - height / 2f) * m
        val fc = (e - g.west) / (g.east - g.west) * (g.n - 1)
        val fr = (g.north - n) / (g.north - g.south) * (g.n - 1)
        // Off the forecast box is not "no wind", it is "not known here" —
        // the particle is retired rather than parked.
        if (fc < 0 || fr < 0 || fc > g.n - 1 || fr > g.n - 1) return false
        val eu = lerpGrid(g.u, g.n, fr, fc)
        val ev = lerpGrid(g.v, g.n, fr, fc)
        if (eu.isNaN() || ev.isNaN()) return false
        // Metres per second back to mph, then to pixels per second — so the
        // apparent speed is the same at every zoom, which is what makes it
        // readable as wind rather than as a zoom level.
        val k = PX_PER_MPH / 0.44704f
        out[0] = (eu * k).toFloat()
        out[1] = (-ev * k).toFloat() // screen y grows southward
        return true
    }

    /**
     * Bilinear over the grid, dropping absent corners and re-weighting what
     * is left — the forecast can come back with holes, and a NaN dragged
     * through the arithmetic would park every particle downwind of the gap.
     */
    private fun lerpGrid(vals: DoubleArray, n: Int, fr: Double, fc: Double): Double {
        val r0 = kotlin.math.floor(fr).toInt().coerceIn(0, n - 1)
        val c0 = kotlin.math.floor(fc).toInt().coerceIn(0, n - 1)
        val r1 = (r0 + 1).coerceAtMost(n - 1)
        val c1 = (c0 + 1).coerceAtMost(n - 1)
        val tr = fr - r0
        val tc = fc - c0
        var sum = 0.0
        var weight = 0.0
        fun take(r: Int, c: Int, w: Double) {
            val v = vals[r * n + c]
            if (!v.isNaN() && w > 0) { sum += v * w; weight += w }
        }
        take(r0, c0, (1 - tr) * (1 - tc))
        take(r0, c1, (1 - tr) * tc)
        take(r1, c0, tr * (1 - tc))
        take(r1, c1, tr * tc)
        return if (weight <= 0.0) Double.NaN else sum / weight
    }

    private fun spawn(mote: Mote) {
        mote.x = (Math.random() * width).toFloat()
        mote.y = (Math.random() * height).toFloat()
        mote.px = mote.x
        mote.py = mote.y
        // Staggered lifetimes, or every particle in the field restarts on the
        // same frame and the whole map blinks once a second.
        mote.life = 40 + (Math.random() * 90).toInt()
    }

    private val windSample = FloatArray(2)

    private fun stepWind() {
        val g = windGrid
        if (g == null || !windStreamlines || width == 0 || height == 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastWindFrameMs == 0L) 0.033f else ((now - lastWindFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastWindFrameMs = now

        var trail = windTrail
        if (trail == null || trail.width != width || trail.height != height) {
            trail?.recycle()
            trail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            windTrail = trail
            windTrailCanvas = Canvas(trail)
        }
        val c = windTrailCanvas ?: return

        val want = moteCount()
        while (motes.size < want) motes.add(Mote(0f, 0f, 0f, 0f, 0).also { spawn(it) })
        while (motes.size > want) motes.removeAt(motes.size - 1)

        // Fade what is already drawn rather than clearing it: the tail behind
        // each particle *is* the streamline.
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), windFade)

        val m = mpp(zl)
        motePaint.strokeWidth = 3.4f * density
        for (mote in motes) {
            mote.life--
            if (mote.life <= 0 || !sampleWind(g, mote.x, mote.y, m, windSample)) {
                spawn(mote)
                continue
            }
            mote.px = mote.x
            mote.py = mote.y
            mote.x += windSample[0] * dt
            mote.y += windSample[1] * dt
            if (mote.x < 0 || mote.y < 0 || mote.x > width || mote.y > height) {
                spawn(mote)
                continue
            }
            val mph = kotlin.math.hypot(windSample[0], windSample[1]) / PX_PER_MPH
            motePaint.color = windColour(mph.toDouble())
            c.drawLine(mote.px, mote.py, mote.x, mote.y, motePaint)
        }
        invalidate()
        postDelayed(windTick, FRAME_MS)
    }

    private fun drawStreamlines(canvas: Canvas) {
        if (!windStreamlines || windGrid == null) return
        val t = windTrail ?: return
        windBlit.alpha = (255 * weatherOpacity).toInt().coerceIn(0, 255)
        canvas.drawBitmap(t, 0f, 0f, windBlit)
    }


    /**
     * Wind by the feel of it rather than by a rainbow: a grey breath, a green
     * breeze, amber when it starts pushing you about, red when a walk on an
     * exposed ridge stops being a good idea.
     */
    private fun windColour(mph: Double): Int = when {
        mph < 8 -> Color.rgb(96, 116, 128)
        mph < 16 -> Color.rgb(24, 132, 72)
        mph < 25 -> Color.rgb(214, 132, 12)
        mph < 38 -> Color.rgb(206, 62, 24)
        else -> Color.rgb(146, 24, 120)
    }

    private fun drawWind(canvas: Canvas) {
        if (windStreamlines || windArrows.isEmpty()) return
        val m = mpp(zl)
        windCasing.strokeWidth = 6.5f * density
        windPaint.strokeWidth = 3.2f * density
        for (a in windArrows) {
            val x = sx(a.e, m)
            val y = sy(a.n, m)
            // In pixels this margin was smaller than the arrows themselves
            // on a dense screen, so an arrow with half of it still on screen
            // was dropped — visible as arrows popping in at the edges after
            // a pan.
            val edge = 48f * density
            if (x < -edge || y < -edge || x > width + edge || y > height + edge) continue
            // Downwind: where it is going, not where it came from. True north
            // rather than grid north — the convergence is a couple of degrees
            // in Wales, far below what an arrow this size can show.
            val going = Math.toRadians(a.fromDeg + 180.0)
            val len = (14f + (a.speedMph.coerceIn(0.0, 40.0) / 40.0 * 16f)).toFloat() * density
            val dx = kotlin.math.sin(going).toFloat()
            val dy = -kotlin.math.cos(going).toFloat()
            val x0 = x - dx * len / 2
            val y0 = y - dy * len / 2
            val x1 = x + dx * len / 2
            val y1 = y + dy * len / 2
            canvas.drawLine(x0, y0, x1, y1, windCasing)
            windPaint.color = windColour(a.speedMph)
            canvas.drawLine(x0, y0, x1, y1, windPaint)
            windFill.color = windPaint.color
            val bearing = Math.toDegrees(going).toFloat()
            drawArrowHead(canvas, x1, y1, bearing, 7f * density, windFill)
        }
    }

    private fun mercY(latDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        return ln(kotlin.math.tan(PI / 4 + lat / 2))
    }

    private fun invMercY(y: Double): Double =
        Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(y)) - PI / 2)

    // A right of way per kind, because the permissions differ: green for a
    // footpath, amber for a bridleway, purple for a restricted byway, brown
    // for one open to all traffic. Kept translucent so the OS map's own
    // green dashes stay readable underneath — where the two disagree, the
    // printed map is the one to believe.
    private val prowCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(170, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val prowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val prowColours = intArrayOf(
        Color.argb(205, 20, 145, 55), // public footpath
        Color.argb(205, 224, 138, 16), // public bridleway
        Color.argb(205, 138, 62, 180), // restricted byway
        Color.argb(205, 150, 74, 40), // byway open to all traffic
        Color.argb(170, 90, 100, 110), // any mapped path: the physical network
    )

    private fun drawProw(canvas: Canvas) {
        if (prowLines.isEmpty()) return
        val m = mpp(zl)
        // Casing for every line first, so one path's halo cannot sit on top
        // of a neighbouring path's colour.
        prowCasing.strokeWidth = 9f * density
        for (pass in 0..1) {
            for (line in prowLines) {
                val pts = line.pts
                if (pts.size < 4) continue
                path.rewind()
                path.moveTo(sx(pts[0].toDouble(), m), sy(pts[1].toDouble(), m))
                var i = 2
                while (i + 1 < pts.size) {
                    path.lineTo(sx(pts[i].toDouble(), m), sy(pts[i + 1].toDouble(), m))
                    i += 2
                }
                val kind = line.kind.coerceIn(0, prowColours.size - 1)
                if (pass == 0) {
                    // Plain mapped paths carry no legal claim, so no halo —
                    // the loud treatment is reserved for actual rights.
                    if (kind < 4) canvas.drawPath(path, prowCasing)
                } else {
                    prowPaint.color = prowColours[kind]
                    prowPaint.strokeWidth = (if (kind < 4) 5f else 2.5f) * density
                    canvas.drawPath(path, prowPaint)
                }
            }
        }
    }

    private val previewCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(190, 255, 255, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 122, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private fun drawPreview(canvas: Canvas) {
        if (previewLines.isEmpty()) return
        val m = mpp(zl)
        path.rewind()
        for (line in previewLines) {
            if (line.size < 2) continue
            path.moveTo(sx(line[0].e, m), sy(line[0].n, m))
            for (i in 1 until line.size) path.lineTo(sx(line[i].e, m), sy(line[i].n, m))
        }
        previewCasing.strokeWidth = 8f * density
        previewCasing.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(10f * density, 6f * density), 0f,
        )
        previewPaint.strokeWidth = 4.5f * density
        previewPaint.pathEffect = previewCasing.pathEffect
        canvas.drawPath(path, previewCasing)
        canvas.drawPath(path, previewPaint)
    }

    private fun drawTrail(canvas: Canvas) {
        val pts = trailPts
        if (pts.size < 2) return
        val m = mpp(zl)
        path.rewind()
        path.moveTo(sx(pts[0].e, m), sy(pts[0].n, m))
        for (i in 1 until pts.size) path.lineTo(sx(pts[i].e, m), sy(pts[i].n, m))
        trailPaint.strokeWidth = 4f * density
        canvas.drawPath(path, trailPaint)
    }

    private fun drawRoute(canvas: Canvas) {
        val pts = routePts
        if (pts.size < 2) return
        val m = mpp(zl)

        path.rewind()
        path.moveTo(sx(pts[0].e, m), sy(pts[0].n, m))
        for (i in 1 until pts.size) path.lineTo(sx(pts[i].e, m), sy(pts[i].n, m))
        casingPaint.strokeWidth = 9 * density
        routePaint.strokeWidth = 5.5f * density
        canvas.drawPath(path, casingPaint)
        canvas.drawPath(path, routePaint)

        // Direction arrows, one every ~140 dp of screen along the line.
        val total = cumDist.last()
        val spacing = 140.0 * density * m
        if (total < spacing / 2) return
        arrowOutline.strokeWidth = 1.5f * density
        var d = spacing / 2
        var seg = 1
        while (d < total) {
            while (seg < cumDist.size && cumDist[seg] < d) seg++
            if (seg >= cumDist.size) break
            val a = pts[seg - 1]; val b = pts[seg]
            val f = ((d - cumDist[seg - 1]) / (cumDist[seg] - cumDist[seg - 1])).coerceIn(0.0, 1.0)
            val e = a.e + (b.e - a.e) * f
            val n = a.n + (b.n - a.n) * f
            var bearing = Math.toDegrees(atan2(b.e - a.e, b.n - a.n))
            if (routeReversed) bearing += 180
            drawArrowHead(canvas, sx(e, m), sy(n, m), bearing.toFloat(), 7f * density, routeArrowPaint)
            d += spacing
        }
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, bearingDeg: Float, r: Float, fill: Paint) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(bearingDeg)
        path.rewind()
        path.moveTo(0f, -r)
        path.lineTo(r * 0.8f, r)
        path.lineTo(0f, r * 0.45f)
        path.lineTo(-r * 0.8f, r)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, arrowOutline)
        canvas.restore()
    }

    private val poiDisc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
    private val poiRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(200, 60, 60, 60)
    }
    private val poiText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun drawPois(canvas: Canvas) {
        if (pois.isEmpty()) return
        val m = mpp(zl)
        val r = 12f * density
        poiRing.strokeWidth = 1.5f * density
        poiText.textSize = 13f * density
        for (poi in pois) {
            val x = sx(poi.at.e, m)
            val y = sy(poi.at.n, m)
            if (x < -r || y < -r || x > width + r || y > height + r) continue
            canvas.drawCircle(x, y, r, poiDisc)
            canvas.drawCircle(x, y, r, poiRing)
            canvas.drawText(Pois.glyph(poi.kind), x, y + 4.5f * density, poiText)
        }
    }

    private fun drawHere(canvas: Canvas) {
        if (!hasFix) return
        val m = mpp(zl)
        val x = sx(fixE, m)
        val y = sy(fixN, m)
        val r = 11f * density
        // A stale fix goes visibly grey rather than sitting confidently wrong.
        val fill = if (fixStale) staleHerePaint else herePaint
        arrowOutline.strokeWidth = 2.5f * density
        val h = headingDeg
        if (h == null) {
            canvas.drawCircle(x, y, r * 0.55f, fill)
            canvas.drawCircle(x, y, r * 0.55f, arrowOutline)
        } else {
            drawArrowHead(canvas, x, y, h.toFloat(), r, fill)
        }
        arrowOutline.strokeWidth = 1.5f * density
    }

    // --- touch --------------------------------------------------------------

    /**
     * Snap a tapped screen point to the nearest drawn route vertex within a
     * generous thumb's reach. Nothing near enough is a no-op rather than a
     * zoom: while the mode is on, a tap means "this point", or nothing.
     */
    private fun pickAt(x: Float, y: Float) {
        if (routePts.isEmpty()) return
        val m = mpp(zl)
        val e = (x - width / 2f) * m + centreE
        val n = centreN - (y - height / 2f) * m
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in routePts.indices) {
            val d = hypot(routePts[i].e - e, routePts[i].n - n)
            if (d < bestD) { bestD = d; best = i }
        }
        // 28dp of screen, in metres at this zoom.
        if (best < 0 || bestD > 28.0 * density * m) return
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onRoutePointPicked?.invoke(routePts[best], cumDist[best])
    }

    private val markFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(178, 44, 38) }
    private val markRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val markText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val startFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 138, 62) }
    private val endFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 44, 48) }
    private val endRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE
    }
    private val endGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * Where the route begins and ends — the beginning most of all, since
     * "where do I actually start this thing" is the first question a loaded
     * GPX gets asked and the line alone never answers it. A green disc with
     * a play-triangle for the start, a dark disc with a square for the end;
     * both honour the reverse-arrows switch, and on a circular walk they sit
     * on top of each other with the start drawn last, because the start is
     * the one that matters.
     */
    private fun drawRouteEnds(canvas: Canvas) {
        if (routePts.size < 2) return
        val m = mpp(zl)
        val start = if (routeReversed) routePts.last() else routePts.first()
        val end = if (routeReversed) routePts.first() else routePts.last()
        val r = 10f * density
        endRing.strokeWidth = 2.5f * density

        val ex = sx(end.e, m)
        val ey = sy(end.n, m)
        if (ex > -r && ey > -r && ex < width + r && ey < height + r) {
            canvas.drawCircle(ex, ey, r * 0.9f, endFill)
            canvas.drawCircle(ex, ey, r * 0.9f, endRing)
            val q = r * 0.36f
            canvas.drawRect(ex - q, ey - q, ex + q, ey + q, endGlyph)
        }

        val sxp = sx(start.e, m)
        val syp = sy(start.n, m)
        if (sxp > -r && syp > -r && sxp < width + r && syp < height + r) {
            canvas.drawCircle(sxp, syp, r, startFill)
            canvas.drawCircle(sxp, syp, r, endRing)
            path.rewind()
            path.moveTo(sxp - r * 0.32f, syp - r * 0.5f)
            path.lineTo(sxp - r * 0.32f, syp + r * 0.5f)
            path.lineTo(sxp + r * 0.55f, syp)
            path.close()
            canvas.drawPath(path, endGlyph)
        }
    }

    private fun drawMarks(canvas: Canvas) {
        if (marks.isEmpty()) return
        val m = mpp(zl)
        val r = 11f * density
        markRing.strokeWidth = 2.5f * density
        markText.textSize = 12f * density
        for ((en, number) in marks) {
            val x = sx(en.e, m)
            val y = sy(en.n, m)
            if (x < -r || y < -r || x > width + r || y > height + r) continue
            canvas.drawCircle(x, y, r, markFill)
            canvas.drawCircle(x, y, r, markRing)
            canvas.drawText("$number", x, y + 4.2f * density, markText)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val m = mpp(zl)
            centreE += dx * m
            centreN -= dy * m
            follow = false
            viewportChanged()
            invalidate()
            return true
        }
        // Tap to zoom in, hold to zoom out. The watch's physical Back key
        // cannot do this: on Wear OS it is the same navigation path as
        // swipe-to-dismiss, so intercepting it for zoom would take away the
        // way out of the app. The screen is the honest place for it.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (pickMode) {
                pickAt(e.x, e.y)
                return true
            }
            zoomStep(e, +1.0)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // A zoom you cannot watch happen on a wrist needs to be felt.
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            zoomStep(e, -1.0)
        }

        /**
         * Zoom toward whatever was tapped — unless we are following, in which
         * case zoom about the centre, so the arrow he is watching stays under
         * his eye instead of sliding off and snapping back on the next fix.
         */
        private fun zoomStep(e: MotionEvent, by: Double) {
            if (follow) {
                zoomAround(width / 2f, height / 2f, zl + by)
            } else {
                zoomAround(e.x, e.y, zl + by)
            }
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(d.focusX, d.focusY, zl + ln(d.scaleFactor.toDouble()) / ln(2.0))
            return true
        }
    })

    /** Zoom keeping the world point under (fx, fy) fixed on screen. */
    private fun zoomAround(fx: Float, fy: Float, newZl: Double) {
        val m1 = mpp(zl)
        val we = centreE + (fx - width / 2.0) * m1
        val wn = centreN - (fy - height / 2.0) * m1
        zl = newZl.coerceIn(minZl, maxZl)
        val m2 = mpp(zl)
        centreE = we - (fx - width / 2.0) * m2
        centreN = wn + (fy - height / 2.0) * m2
        viewportChanged()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = scaler.onTouchEvent(event)
        val b = gestures.onTouchEvent(event)
        return a || b || super.onTouchEvent(event)
    }
}
