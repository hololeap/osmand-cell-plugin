// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A Web Mercator ("slippy map") tile: [x] grows east, [y] grows south, at zoom [z]. */
data class Tile(val z: Int, val x: Int, val y: Int) {
    /** The tile at a lower zoom [zoom] that contains this one. */
    fun ancestor(zoom: Int): Tile {
        require(zoom in 0..z) { "zoom $zoom is not above $z" }
        val shift = z - zoom
        return Tile(zoom, x shr shift, y shr shift)
    }

    fun west(): Double = Tiles.tileXToLon(x.toDouble(), z)
    fun east(): Double = Tiles.tileXToLon(x + 1.0, z)
    fun north(): Double = Tiles.tileYToLat(y.toDouble(), z)
    fun south(): Double = Tiles.tileYToLat(y + 1.0, z)
    fun center(): LatLon = LatLon(Tiles.tileYToLat(y + 0.5, z), Tiles.tileXToLon(x + 0.5, z))
}

/**
 * Web Mercator tile arithmetic (EPSG:3857 tiling as used by OSM and OsmAnd).
 *
 * The same grid, at [BIN_ZOOM], is the grid samples are binned on. A bin is a
 * zoom-20 tile, about 38 m x cos(latitude) on a side: 29 m at 40 degrees, 19 m
 * at 60. Using map tiles as bins means the heatmap tiles a bin falls in are
 * found by shifting its coordinates, with no reprojection.
 */
object Tiles {
    const val BIN_ZOOM = 20
    const val TILE_SIZE = 256

    /** Web Mercator is cut off here; beyond it tile rows do not exist. */
    const val MAX_LAT = 85.0511287798066

    /** Fractional tile column of [lon] at zoom [z]. */
    fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    /** Fractional tile row of [lat] at zoom [z]. */
    fun latToTileY(lat: Double, z: Int): Double {
        val phi = Geo.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0 * (1 shl z)
    }

    fun tileXToLon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double =
        Geo.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

    fun tileAt(lat: Double, lon: Double, z: Int): Tile {
        val max = (1 shl z) - 1
        return Tile(
            z,
            floor(lonToTileX(lon, z)).toInt().coerceIn(0, max),
            floor(latToTileY(lat, z)).toInt().coerceIn(0, max),
        )
    }

    /** The bin (a tile at [BIN_ZOOM]) that a position falls in. */
    fun binAt(lat: Double, lon: Double): Tile = tileAt(lat, lon, BIN_ZOOM)

    /** Ground size in metres of one tile edge at [lat]. */
    fun tileSizeM(lat: Double, z: Int): Double =
        2 * PI * EARTH_RADIUS_M * cos(Geo.toRadians(lat)) / (1 shl z)

    /**
     * Where a bin lands in the heatmap tile at [zoom] that contains it: the pixel
     * of its top-left corner and its edge length in pixels. Below one pixel the
     * size is reported as 1 so that the bin still colours a pixel.
     */
    fun binPixels(binX: Int, binY: Int, zoom: Int): BinPixels {
        require(zoom in 0..BIN_ZOOM)
        val shift = BIN_ZOOM - zoom // bins per tile edge = 2^shift
        val tile = Tile(zoom, binX shr shift, binY shr shift)
        val inTileX = binX - (tile.x shl shift)
        val inTileY = binY - (tile.y shl shift)
        // 256 px across 2^shift bins.
        return if (shift <= 8) {
            val size = TILE_SIZE shr shift
            BinPixels(tile, inTileX * size, inTileY * size, size)
        } else {
            val div = shift - 8
            BinPixels(tile, inTileX shr div, inTileY shr div, 1)
        }
    }
}

data class BinPixels(val tile: Tile, val px: Int, val py: Int, val size: Int)
