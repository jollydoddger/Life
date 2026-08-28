package com.jollydoddger.waymark.shared

import kotlin.math.floor

/**
 * The OS Maps API Leisure_27700 tile pyramid — the one style serving the
 * classic paper-map cartography (1:250k road atlas → Landranger 1:50k →
 * Explorer 1:25k as you zoom), and served only in EPSG:27700, which is why
 * this app has its own map view instead of a Mercator map library.
 *
 * Grid facts from the OS Maps API technical specification: 256 px tiles,
 * top-left origin (−238375, 1376256) in grid metres, zooms 0–9 halving from
 * 896 m/px down to 1.75 m/px.
 */
object TileGrid {
    const val TILE_PX = 256
    const val ORIGIN_E = -238375.0
    const val ORIGIN_N = 1376256.0
    val METRES_PER_PX = doubleArrayOf(896.0, 448.0, 224.0, 112.0, 56.0, 28.0, 14.0, 7.0, 3.5, 1.75)
    const val MAX_Z = 9

    /** Width/height of one tile in grid metres at zoom [z]. */
    fun tileSpan(z: Int): Double = TILE_PX * METRES_PER_PX[z]

    fun tileX(e: Double, z: Int): Int = floor((e - ORIGIN_E) / tileSpan(z)).toInt()
    fun tileY(n: Double, z: Int): Int = floor((ORIGIN_N - n) / tileSpan(z)).toInt()

    /** Grid coordinates of tile (x, y)'s top-left corner. */
    fun tileWest(x: Int, z: Int): Double = ORIGIN_E + x * tileSpan(z)
    fun tileNorth(y: Int, z: Int): Double = ORIGIN_N - y * tileSpan(z)

    fun url(z: Int, x: Int, y: Int, key: String): String =
        "https://api.os.uk/maps/raster/v1/zxy/Leisure_27700/$z/$x/$y.png?key=$key"
}
