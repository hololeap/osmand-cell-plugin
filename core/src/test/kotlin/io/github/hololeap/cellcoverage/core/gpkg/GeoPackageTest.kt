// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.gpkg

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Builds the schema in a real SQLite file and checks what QGIS and GDAL look for. */
class GeoPackageTest {
    private val file = Files.createTempFile("cells", ".gpkg")
    private lateinit var db: Connection

    @BeforeTest
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite:$file")
        db.createStatement().use { st -> GpkgSchema.createStatements.forEach { st.execute(it) } }
    }

    @AfterTest
    fun close() {
        db.close()
        Files.deleteIfExists(file)
    }

    private fun long(sql: String): Long = db.createStatement().use { it.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }

    @Test
    fun pragmasMarkTheFileAsAGeoPackage() {
        assertEquals(0x47504B47L, long("PRAGMA application_id"))
        assertEquals(10300L, long("PRAGMA user_version"))
        // And the magic bytes really are at offset 68 of the file header.
        db.close()
        val header = Files.readAllBytes(file).copyOfRange(68, 72)
        assertContentEquals("GPKG".toByteArray(), header)
        db = DriverManager.getConnection("jdbc:sqlite:$file")
    }

    @Test
    fun everyFeatureTableIsRegistered() {
        for ((table, _) in GpkgSchema.featureTables) {
            assertEquals(1L, long("SELECT count(*) FROM gpkg_contents WHERE table_name='$table' AND data_type='features' AND srs_id=4326"), table)
            assertEquals(1L, long("SELECT count(*) FROM gpkg_geometry_columns WHERE table_name='$table' AND column_name='geom' AND geometry_type_name='POINT'"), table)
        }
        assertEquals(3L, long("SELECT count(*) FROM gpkg_spatial_ref_sys WHERE srs_id IN (-1, 0, 4326)"))
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_key_check").use { r -> assertEquals(false, r.next(), "foreign key violations") }
        }
    }

    @Test
    fun pointsRoundTrip() {
        db.prepareStatement("INSERT INTO samples (geom, t, time, cell, site, radio) VALUES (?, ?, ?, 'c', 's', 'LTE')").use { p ->
            p.setBytes(1, GpkgGeometry.encodePoint(47.25, 11.5))
            p.setLong(2, 1714558830123L)
            p.setString(3, GpkgSchema.isoTime(1714558830123L))
            p.executeUpdate()
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT geom, time FROM samples").use { r ->
                r.next()
                val blob = r.getBytes(1)
                assertEquals(GpkgGeometry.POINT_BLOB_SIZE, blob.size)
                val ll = assertNotNull(GpkgGeometry.decodePoint(blob))
                assertEquals(47.25, ll.lat)
                assertEquals(11.5, ll.lon)
                assertEquals("2024-05-01T10:20:30.123Z", r.getString(2))
            }
        }
    }

    @Test
    fun pointBlobLayout() {
        val b = GpkgGeometry.encodePoint(1.0, 2.0)
        // "GP", version 0, flags 0x01 (little-endian, no envelope), srs 4326 LE, WKB LE point.
        assertContentEquals(byteArrayOf(0x47, 0x50, 0, 1, 0xE6.toByte(), 0x10, 0, 0, 1, 1, 0, 0, 0), b.copyOfRange(0, 13))
    }

    @Test
    fun reimportingASampleIsIgnored() {
        val sql = "INSERT OR IGNORE INTO samples (t, cell, site, radio) VALUES (1, 'c', 's', 'LTE')"
        db.createStatement().use { it.executeUpdate(sql); it.executeUpdate(sql) }
        assertEquals(1L, long("SELECT count(*) FROM samples"))
    }
}
