// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6_371_008.8

data class LatLon(val lat: Double, val lon: Double)

/** A position in metres on a local plane: [x] east, [y] north. */
data class PointM(val x: Double, val y: Double)

object Geo {
    fun toRadians(deg: Double): Double = deg * PI / 180.0
    fun toDegrees(rad: Double): Double = rad * 180.0 / PI

    /** Great-circle distance in metres. */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = toRadians(lat1)
        val p2 = toRadians(lat2)
        val dp = p2 - p1
        val dl = toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing in degrees, 0 = north, clockwise, of the vector ([dx] east, [dy] north). */
    fun bearingDeg(dx: Double, dy: Double): Double {
        val b = toDegrees(atan2(dx, dy))
        return if (b < 0) b + 360.0 else b
    }

    /** The signed difference a - b between two bearings, folded into [-180, 180). */
    fun angleDiffDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d < -180.0) d += 360.0
        if (d >= 180.0) d -= 360.0
        return d
    }
}

/**
 * An equirectangular projection around [origin]: good to well under a metre per
 * kilometre over the tens of kilometres a single cell site is seen from, and
 * cheap in both directions.
 */
class LocalProjection(val origin: LatLon) {
    private val cosLat = cos(Geo.toRadians(origin.lat))

    fun toLocal(lat: Double, lon: Double): PointM {
        var dLon = lon - origin.lon
        if (dLon > 180.0) dLon -= 360.0
        if (dLon < -180.0) dLon += 360.0
        return PointM(
            x = EARTH_RADIUS_M * Geo.toRadians(dLon) * cosLat,
            y = EARTH_RADIUS_M * Geo.toRadians(lat - origin.lat),
        )
    }

    fun toLatLon(x: Double, y: Double): LatLon {
        val lat = origin.lat + Geo.toDegrees(y / EARTH_RADIUS_M)
        var lon = origin.lon + Geo.toDegrees(x / (EARTH_RADIUS_M * cosLat))
        if (lon > 180.0) lon -= 360.0
        if (lon < -180.0) lon += 360.0
        return LatLon(lat, lon)
    }
}
