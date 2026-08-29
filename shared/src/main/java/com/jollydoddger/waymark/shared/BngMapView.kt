package com.jollydoddger.waymark.shared

import android.annotation.SuppressLint
import android.content.Context
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
     * Follow me again, and zoom right in while doing it: the button is asked
     * "where am I", and an answer at a mile to the inch is not one.
     */
    fun recentre() {
        follow = true
        if (hasFix) {
            centreE = fixE; centreN = fixN
            setZoom(maxZl)
        } else {
            centreOnRoute()
        }
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

    fun setWeatherOpacity(percent: Int) {
        weatherOpacity = (percent.coerceIn(0, 100)) / 100f
        applyWeatherAlpha()
        invalidate()
    }

    private fun applyWeatherAlpha() {
        radarPaint.alpha = (radarBaseAlpha * weatherOpacity).toInt().coerceIn(0, 255)
        fieldPaint.alpha = (fieldBaseAlpha * weatherOpacity).toInt().coerceIn(0, 255)
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
    private var fieldPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }

    fun setField(tile: MeshTile?, alpha: Int) {
        fieldTile = tile
        fieldBaseAlpha = alpha
        applyWeatherAlpha()
        invalidate()
    }

    private fun drawField(canvas: Canvas) {
        val t = fieldTile ?: return
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
        canvas.drawBitmapMesh(t.bitmap, radarMeshN, radarMeshN, radarMesh, 0, null, 0, fieldPaint)
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
        if (windArrows.isEmpty()) return
        val m = mpp(zl)
        windCasing.strokeWidth = 6.5f * density
        windPaint.strokeWidth = 3.2f * density
        for (a in windArrows) {
            val x = sx(a.e, m)
            val y = sy(a.n, m)
            if (x < -40 || y < -40 || x > width + 40 || y > height + 40) continue
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
