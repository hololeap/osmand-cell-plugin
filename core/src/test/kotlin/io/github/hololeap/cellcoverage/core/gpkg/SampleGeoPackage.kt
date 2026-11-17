// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.gpkg

import io.github.hololeap.cellcoverage.core.estimator.Estimate
import io.github.hololeap.cellcoverage.core.estimator.SyntheticSite
import io.github.hololeap.cellcoverage.core.estimator.TowerEstimator
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test

/**
 * Writes a GeoPackage of synthetic data, to open in QGIS or check with GDAL:
 *
 *     SAMPLE_GPKG=/tmp/sample.gpkg ./gradlew :core:test --tests '*SampleGeoPackage*'
 *
 * Does nothing unless SAMPLE_GPKG is set.
 */
class SampleGeoPackage {
    @Test
    fun write() {
        val out = System.getenv("SAMPLE_GPKG") ?: return
        Files.deleteIfExists(Path.of(out))
        val site = SyntheticSite(exponent = 3.5, shadowDb = 8.0, seed = 2)
        val samples = site.drive(SyntheticSite.lRoute(), withTa = true)
        val bins = site.bins(samples)
        val e = TowerEstimator().estimateSite(bins.all.toList()) as Estimate.Located
        DriverManager.getConnection("jdbc:sqlite:$out").use { db ->
            db.autoCommit = false
            db.createStatement().use { st -> GpkgSchema.createStatements.forEach { st.execute(it) } }
            db.prepareStatement(
                "INSERT INTO samples (geom, t, time, bx, by, accuracy, cell, site, radio, generation, mcc, mnc, area, cid, registered, dbm, ta, source) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'synthetic')",
            ).use { p ->
                for (s in samples) {
                    val bin = io.github.hololeap.cellcoverage.core.geo.Tiles.binAt(s.lat, s.lon)
                    p.setBytes(1, GpkgGeometry.encodePoint(s.lat, s.lon))
                    p.setLong(2, s.timeMs); p.setString(3, GpkgSchema.isoTime(s.timeMs))
                    p.setInt(4, bin.x); p.setInt(5, bin.y)
                    p.setDouble(6, s.accuracyM!!.toDouble())
                    p.setString(7, s.cell.toId()); p.setString(8, s.cell.site.toId())
                    p.setString(9, s.cell.radio.name); p.setInt(10, s.cell.radio.generation)
                    p.setInt(11, s.cell.mcc); p.setInt(12, s.cell.mnc); p.setInt(13, s.cell.area); p.setLong(14, s.cell.cid)
                    p.setBoolean(15, s.registered); p.setInt(16, s.dbm!!)
                    if (s.timingAdvance != null) p.setInt(17, s.timingAdvance!!) else p.setNull(17, java.sql.Types.INTEGER)
                    p.addBatch()
                }
                p.executeBatch()
            }
            db.prepareStatement(
                "INSERT INTO cell_bins (geom, cell, site, bx, by, n, mean_dbm, ta_n, ta_median, ta_hist, first_t, last_t) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { p ->
                for (b in bins.all) {
                    p.setBytes(1, GpkgGeometry.encodePoint(b.meanLat, b.meanLon))
                    p.setString(2, b.cell.toId()); p.setString(3, b.cell.site.toId())
                    p.setInt(4, b.bx); p.setInt(5, b.by); p.setInt(6, b.n); p.setDouble(7, b.meanDbm)
                    p.setInt(8, b.taCount)
                    if (b.taMedian != null) p.setInt(9, b.taMedian!!) else p.setNull(9, java.sql.Types.INTEGER)
                    p.setString(10, b.taHistogram()); p.setLong(11, b.firstT); p.setLong(12, b.lastT)
                    p.addBatch()
                }
                p.executeBatch()
            }
            db.prepareStatement(
                "INSERT INTO towers (geom, site, radio, mcc, mnc, node, source, cells, samples, est_lat, est_lon, est_radius, est_exponent, est_rms, est_bins, est_span, est_ambiguous, est_at_limit) " +
                    "VALUES (?,?,'LTE',232,1,?,'ESTIMATED',3,?,?,?,?,?,?,?,?,?,?)",
            ).use { p ->
                p.setBytes(1, GpkgGeometry.encodePoint(e.position.lat, e.position.lon))
                p.setString(2, site.cellOf(0).site.toId()); p.setLong(3, site.enb); p.setInt(4, samples.size)
                p.setDouble(5, e.position.lat); p.setDouble(6, e.position.lon); p.setDouble(7, e.errorRadiusM)
                p.setDouble(8, e.exponent); p.setDouble(9, e.residualRmsDb); p.setInt(10, e.bins)
                p.setDouble(11, e.angularSpanDeg); p.setBoolean(12, e.ambiguous); p.setBoolean(13, e.exponentAtLimit)
                p.executeUpdate()
            }
            // The true position, for comparison in QGIS; a synthetic-only extra row.
            db.prepareStatement("INSERT INTO towers (geom, site, radio, source) VALUES (?, 'truth', 'LTE', 'TRUTH')").use { p ->
                p.setBytes(1, GpkgGeometry.encodePoint(site.truth.lat, site.truth.lon)); p.executeUpdate()
            }
            db.commit()
        }
    }
}
