// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TilesTest {
    @Test
    fun knownTile() {
        // Innsbruck at zoom 10, checked with an independent Python version of the OSM wiki formula.
        assertEquals(Tile(10, 544, 359), Tiles.tileAt(47.2692, 11.4041, 10))
        assertEquals(Tile(0, 0, 0), Tiles.tileAt(0.0, 0.0, 0))
    }

    @Test
    fun roundTripsTileCorners() {
        val t = Tile(15, 17_456, 11_532)
        assertEquals(t, Tiles.tileAt(t.center().lat, t.center().lon, 15))
        assertTrue(t.north() > t.south())
        assertTrue(t.east() > t.west())
        assertEquals(t.north(), Tile(15, t.x, t.y - 1).south(), 1e-12)
    }

    @Test
    fun clampsThePoles() {
        assertEquals(0, Tiles.tileAt(89.9, 0.0, 5).y)
        assertEquals(31, Tiles.tileAt(-89.9, 0.0, 5).y)
        assertEquals(31, Tiles.tileAt(0.0, 180.0, 5).x)
    }

    @Test
    fun binSize() {
        // A zoom-20 bin is ~38 m at the equator, ~26 m in the Alps.
        assertEquals(38.2, Tiles.tileSizeM(0.0, 20), 0.1)
        assertEquals(26.1, Tiles.tileSizeM(47.0, 20), 0.2)
    }

    @Test
    fun ancestorsAndBinPixels() {
        val bin = Tiles.binAt(47.2692, 11.4041)
        val z15 = bin.ancestor(15)
        assertEquals(Tiles.tileAt(47.2692, 11.4041, 15), z15)
        // At zoom 15 a bin is 256 / 2^5 = 8 px.
        val p15 = Tiles.binPixels(bin.x, bin.y, 15)
        assertEquals(z15, p15.tile)
        assertEquals(8, p15.size)
        assertEquals((bin.x - (z15.x shl 5)) * 8, p15.px)
        // At zoom 8 many bins share a pixel.
        val p8 = Tiles.binPixels(bin.x, bin.y, 8)
        assertEquals(bin.ancestor(8), p8.tile)
        assertEquals(1, p8.size)
        assertTrue(p8.px in 0..255 && p8.py in 0..255)
    }

    @Test
    fun localProjectionRoundTrips() {
        val proj = LocalProjection(LatLon(47.0, 11.0))
        val p = proj.toLocal(47.01, 11.02)
        val back = proj.toLatLon(p.x, p.y)
        assertEquals(47.01, back.lat, 1e-9)
        assertEquals(11.02, back.lon, 1e-9)
        // Agrees with the great-circle distance to within 0.1% at this range.
        val d = Geo.haversineM(47.0, 11.0, 47.01, 11.02)
        assertEquals(d, kotlin.math.hypot(p.x, p.y), d * 1e-3)
    }

    @Test
    fun angles() {
        assertEquals(90.0, Geo.bearingDeg(1.0, 0.0), 1e-9)
        assertEquals(270.0, Geo.bearingDeg(-1.0, 0.0), 1e-9)
        assertEquals(-20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(20.0, Geo.angleDiffDeg(10.0, 350.0), 1e-9)
    }
}
