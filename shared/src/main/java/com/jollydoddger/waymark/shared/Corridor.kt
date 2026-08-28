package com.jollydoddger.waymark.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The offline promise: every tile within ~500 m of the route, zooms 5–9
 * (Landranger overview down to full Explorer detail), fetched at import time
 * so the walk still has its map in a dead zone. A typical day walk is a few
 * hundred tiles — a handful of megabytes, once.
 */
object Corridor {
    private const val BUFFER_M = 500.0
    private const val STEP_M = 200.0
    private val ZOOMS = 5..TileGrid.MAX_Z

    data class TileId(val z: Int, val x: Int, val y: Int)

    fun tilesFor(route: Route): List<TileId> {
        val ids = LinkedHashSet<TileId>()
        val pts = route.points
        for (z in ZOOMS) {
            fun cover(e: Double, n: Double) {
                for (x in TileGrid.tileX(e - BUFFER_M, z)..TileGrid.tileX(e + BUFFER_M, z)) {
                    for (y in TileGrid.tileY(n + BUFFER_M, z)..TileGrid.tileY(n - BUFFER_M, z)) {
                        if (x >= 0 && y >= 0) ids.add(TileId(z, x, y))
                    }
                }
            }
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val len = hypot(b.e - a.e, b.n - a.n)
                val steps = ceil(len / STEP_M).toInt().coerceAtLeast(1)
                for (s in 0..steps) {
                    val f = s.toDouble() / steps
                    cover(a.e + (b.e - a.e) * f, a.n + (b.n - a.n) * f)
                }
            }
        }
        return ids.toList()
    }

    /**
     * Fetch every corridor tile not already on disk. Returns the number that
     * could NOT be fetched — zero is the only number that means "offline is
     * covered", and the caller must say so either way.
     */
    suspend fun prefetch(
        store: TileStore,
        route: Route,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val all = tilesFor(route)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val gate = Semaphore(4)
        coroutineScope {
            all.map { t ->
                async {
                    gate.withPermit {
                        if (!store.download(t.z, t.x, t.y)) failed.incrementAndGet()
                        onProgress(done.incrementAndGet(), all.size)
                    }
                }
            }.awaitAll()
        }
        failed.get()
    }
}
