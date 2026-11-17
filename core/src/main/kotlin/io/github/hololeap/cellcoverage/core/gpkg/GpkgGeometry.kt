// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.gpkg

import io.github.hololeap.cellcoverage.core.geo.LatLon
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GeoPackage geometry blobs for points (OGC 12-128, "GeoPackageBinary"): an
 * 8-byte header followed by a standard WKB point.
 *
 *     47 50      magic "GP"
 *     00         version 0
 *     01         flags: little-endian, no envelope, not empty
 *     E6 10 0 0  srs_id 4326
 *     01         WKB byte order: little-endian
 *     01 0 0 0   WKB type 1 = Point
 *     x, y       two doubles: longitude, latitude
 */
object GpkgGeometry {
    const val SRS_WGS84 = 4326
    const val POINT_BLOB_SIZE = 29

    fun encodePoint(lat: Double, lon: Double): ByteArray {
        val b = ByteBuffer.allocate(POINT_BLOB_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x47).put(0x50).put(0).put(0x01)
        b.putInt(SRS_WGS84)
        b.put(1).putInt(1)
        b.putDouble(lon).putDouble(lat)
        return b.array()
    }

    /** Decodes a blob written by [encodePoint]; null for anything else. */
    fun decodePoint(blob: ByteArray?): LatLon? {
        if (blob == null || blob.size != POINT_BLOB_SIZE) return null
        val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        if (b.get().toInt() != 0x47 || b.get().toInt() != 0x50) return null
        b.get()
        if (b.get().toInt() != 0x01) return null
        b.getInt()
        if (b.get().toInt() != 1 || b.getInt() != 1) return null
        val lon = b.getDouble()
        val lat = b.getDouble()
        return LatLon(lat, lon)
    }
}
