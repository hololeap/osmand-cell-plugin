// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.hololeap.cellcoverage.core.bins.CellBin
import io.github.hololeap.cellcoverage.core.geo.Tiles
import io.github.hololeap.cellcoverage.core.gpkg.GpkgGeometry
import io.github.hololeap.cellcoverage.core.gpkg.GpkgSchema
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.IdSource
import io.github.hololeap.cellcoverage.core.model.NeighbourReading
import io.github.hololeap.cellcoverage.core.model.PhysicalId
import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Resolution
import io.github.hololeap.cellcoverage.core.model.Sample
import io.github.hololeap.cellcoverage.core.resolve.NeighbourResolver
import java.io.File

/**
 * The app's database: one GeoPackage file that QGIS opens as it is.
 *
 * It lives in the app's external files folder, which `adb pull` reaches:
 *
 *     adb pull /sdcard/Android/data/io.github.hololeap.cellcoverage/files/coverage.gpkg
 *
 * The file uses a rollback journal, not WAL, so that the .gpkg file alone is
 * always complete when copied off the phone. Android's SQLiteOpenHelper is not
 * used because it keeps its own version in `user_version`, which a GeoPackage
 * needs for its own; see [GpkgSchema].
 *
 * Not thread-safe: use it from one thread (the collector's worker).
 */
class CoverageDb private constructor(val file: File, private val db: SQLiteDatabase) {

    val resolver = NeighbourResolver()

    /** Teaches the resolver every reported cell with a physical id. Call once after opening. */
    fun loadResolver() {
        db.rawQuery(
            "SELECT geom, cell, pci, channel FROM samples WHERE id_source = 'REPORTED' AND pci IS NOT NULL AND channel IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val ll = GpkgGeometry.decodePoint(c.getBlob(0)) ?: continue
                val cell = CellKey.fromId(c.getString(1)) ?: continue
                resolver.learn(Sample(timeMs = 0, lat = ll.lat, lon = ll.lon, cell = cell, pci = c.getInt(2), channel = c.getInt(3)))
            }
        }
    }

    fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Stores a sample with a cell id, updates its bin, and teaches the resolver.
     * Returns false if the same sample (time and cell) was already stored.
     */
    fun insertSample(s: Sample, source: String, phys: PhysicalId? = null, resolution: Resolution? = null): Boolean {
        val bin = Tiles.binAt(s.lat, s.lon)
        val v = ContentValues().apply {
            put(GpkgSchema.GEOM, GpkgGeometry.encodePoint(s.lat, s.lon))
            put("t", s.timeMs)
            put("time", GpkgSchema.isoTime(s.timeMs))
            put("bx", bin.x)
            put("by", bin.y)
            s.accuracyM?.let { put("accuracy", it) }
            s.speedMps?.let { put("speed", it) }
            s.bearingDeg?.let { put("bearing", it) }
            s.altitudeM?.let { put("altitude", it) }
            put("cell", s.cell.toId())
            put("site", s.cell.site.toId())
            put("radio", s.cell.radio.name)
            put("generation", s.cell.radio.generation)
            put("mcc", s.cell.mcc)
            put("mnc", s.cell.mnc)
            put("area", s.cell.area)
            put("cid", s.cell.cid)
            put("registered", s.registered)
            put("id_source", s.idSource.name)
            put("id_confidence", s.idConfidence)
            (phys ?: NeighbourResolver.physicalIdOf(s))?.let { put("phys", it.toId()) }
            resolution?.let {
                put("resolve_n", it.candidates.size)
                put("resolve_alts", it.candidatesText())
            }
            putSignal(s.pci, s.channel, s.bands, s.dbm, s.rsrq, s.sinr, s.timingAdvance)
            s.service?.let { sv ->
                put("calls", sv.calls.name)
                sv.voiceState?.let { put("voice_state", it) }
                sv.dataNetworkType?.let { put("data_net_type", it) }
                sv.displayOverride?.let { put("display_override", it) }
                sv.roaming?.let { put("roaming", it) }
                sv.availableServices?.let { put("services", it) }
                sv.linkDownKbps?.let { put("link_down_kbps", it) }
            }
            s.subscriptionId?.let { put("sub_id", it) }
            put("source", source)
        }
        val id = db.insertWithOnConflict(GpkgSchema.SAMPLES, null, v, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return false
        if (s.idSource == IdSource.REPORTED) resolver.learn(s)
        addToBin(s)
        return true
    }

    /** Stores a neighbour that has no cell id yet. */
    fun insertUnresolved(r: NeighbourReading, source: String): Boolean {
        val bin = Tiles.binAt(r.lat, r.lon)
        val v = ContentValues().apply {
            put(GpkgSchema.GEOM, GpkgGeometry.encodePoint(r.lat, r.lon))
            put("t", r.timeMs)
            put("time", GpkgSchema.isoTime(r.timeMs))
            put("bx", bin.x)
            put("by", bin.y)
            r.accuracyM?.let { put("accuracy", it) }
            put("radio", r.physical.radio.name)
            put("generation", r.physical.radio.generation)
            put("mcc", r.physical.mcc)
            put("mnc", r.physical.mnc)
            put("registered", false)
            put("id_source", IdSource.UNRESOLVED.name)
            put("id_confidence", 0.0)
            put("phys", r.physical.toId())
            put("resolve_n", 0)
            putSignal(r.physical.pci, r.physical.channel, null, r.dbm, r.rsrq, r.sinr, null)
            r.subscriptionId?.let { put("sub_id", it) }
            put("source", source)
        }
        return db.insertWithOnConflict(GpkgSchema.SAMPLES, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Resolves [r] against what is known now and stores it either way. */
    fun insertNeighbour(r: NeighbourReading, source: String): IdSource {
        val res = resolver.resolve(r.physical, r.lat, r.lon)
        val cell = res.cell
        if (cell == null) {
            insertUnresolved(r, source)
        } else {
            insertSample(r.toSample(cell, res), source, r.physical, res)
        }
        return res.source
    }

    private fun ContentValues.putSignal(pci: Int?, channel: Int?, bands: String?, dbm: Int?, rsrq: Int?, sinr: Int?, ta: Int?) {
        pci?.let { put("pci", it) }
        channel?.let { put("channel", it) }
        bands?.let { put("bands", it) }
        dbm?.let { put("dbm", it) }
        rsrq?.let { put("rsrq", it) }
        sinr?.let { put("sinr", it) }
        ta?.let { put("ta", it) }
    }

    private fun addToBin(s: Sample) {
        if (s.dbm == null || s.idSource == IdSource.AMBIGUOUS || s.idSource == IdSource.UNRESOLVED) return
        val (cell, bx, by) = CellBin.keyOf(s)
        val bin = loadBin(cell, bx, by) ?: CellBin(cell, bx, by)
        bin.add(s)
        saveBin(bin)
    }

    private fun loadBin(cell: CellKey, bx: Int, by: Int): CellBin? =
        db.rawQuery(
            "SELECT n, mean_dbm, geom, first_t, last_t, ta_hist, w_sum FROM cell_bins WHERE cell = ? AND bx = ? AND by = ?",
            arrayOf(cell.toId(), bx.toString(), by.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val ll = GpkgGeometry.decodePoint(c.getBlob(2)) ?: return null
            CellBin.restore(cell, bx, by, c.getInt(0), c.getDouble(1), ll.lat, ll.lon, c.getLong(3), c.getLong(4), c.getString(5), c.getDouble(6))
        }

    private fun saveBin(b: CellBin) {
        val v = ContentValues().apply {
            put(GpkgSchema.GEOM, GpkgGeometry.encodePoint(b.meanLat, b.meanLon))
            put("cell", b.cell.toId())
            put("site", b.cell.site.toId())
            put("bx", b.bx)
            put("by", b.by)
            put("n", b.n)
            put("w_sum", b.weightSum)
            put("mean_dbm", b.meanDbm)
            put("ta_n", b.taCount)
            b.taMedian?.let { put("ta_median", it) } ?: putNull("ta_median")
            put("ta_hist", b.taHistogram())
            put("first_t", b.firstT)
            put("last_t", b.lastT)
        }
        db.insertWithOnConflict(GpkgSchema.CELL_BINS, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Resolves every non-reported neighbour again with everything learned so
     * far, then rebuilds all bins from the samples (a match that changed cell
     * must leave its old bin). Returns the count per outcome.
     */
    fun reResolveAll(): Map<IdSource, Int> = transaction {
        val counts = HashMap<IdSource, Int>()
        val updates = ArrayList<Pair<Long, Resolution>>()
        db.rawQuery("SELECT fid, geom, phys FROM samples WHERE id_source <> 'REPORTED' AND phys IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val ll = GpkgGeometry.decodePoint(c.getBlob(1)) ?: continue
                val pid = PhysicalId.fromId(c.getString(2)) ?: continue
                updates += c.getLong(0) to resolver.resolve(pid, ll.lat, ll.lon)
            }
        }
        for ((fid, r) in updates) {
            counts[r.source] = (counts[r.source] ?: 0) + 1
            val cell = r.cell
            val v = ContentValues().apply {
                put("id_source", r.source.name)
                put("id_confidence", r.confidence)
                put("resolve_n", r.candidates.size)
                if (r.candidates.isEmpty()) putNull("resolve_alts") else put("resolve_alts", r.candidatesText())
                if (cell == null) {
                    putNull("cell"); putNull("site"); putNull("area"); putNull("cid")
                } else {
                    put("cell", cell.toId()); put("site", cell.site.toId()); put("area", cell.area); put("cid", cell.cid)
                }
            }
            db.update(GpkgSchema.SAMPLES, v, "fid = ?", arrayOf(fid.toString()))
        }
        rebuildBins()
        counts
    }

    /** Recomputes `cell_bins` from scratch. */
    fun rebuildBins() {
        db.delete(GpkgSchema.CELL_BINS, null, null)
        val bins = io.github.hololeap.cellcoverage.core.bins.CellBins()
        db.rawQuery(
            "SELECT geom, t, cell, dbm, ta, id_source, id_confidence FROM samples WHERE cell IS NOT NULL AND dbm IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val ll = GpkgGeometry.decodePoint(c.getBlob(0)) ?: continue
                val cell = CellKey.fromId(c.getString(2)) ?: continue
                bins.add(
                    Sample(
                        timeMs = c.getLong(1), lat = ll.lat, lon = ll.lon, cell = cell, dbm = c.getInt(3),
                        timingAdvance = if (c.isNull(4)) null else c.getInt(4),
                        idSource = IdSource.valueOf(c.getString(5)), idConfidence = c.getDouble(6),
                    ),
                )
            }
        }
        bins.all.forEach(::saveBin)
    }

    data class Counts(val samples: Long, val bySource: Map<String, Long>, val cells: Long, val bins: Long)

    fun counts(): Counts {
        val by = HashMap<String, Long>()
        db.rawQuery("SELECT id_source, count(*) FROM samples GROUP BY id_source", null).use { c ->
            while (c.moveToNext()) by[c.getString(0)] = c.getLong(1)
        }
        fun one(sql: String) = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getLong(0) }
        return Counts(by.values.sum(), by, one("SELECT count(DISTINCT cell) FROM samples WHERE cell IS NOT NULL"), one("SELECT count(*) FROM cell_bins"))
    }

    fun close() = db.close()

    companion object {
        const val FILE_NAME = "coverage.gpkg"

        fun open(context: Context): CoverageDb {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, FILE_NAME)
            // No localized collators: otherwise Android adds an android_metadata table.
            val db = SQLiteDatabase.openDatabase(
                file.path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            db.disableWriteAheadLogging()
            val appId = db.rawQuery("PRAGMA application_id", null).use { c -> c.moveToFirst(); c.getInt(0) }
            if (appId != GpkgSchema.APPLICATION_ID) {
                val empty = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'", null)
                    .use { c -> c.moveToFirst(); c.getInt(0) == 0 }
                check(empty) { "${file.path} exists but is not this app's GeoPackage; move it away" }
                db.beginTransaction()
                try {
                    GpkgSchema.createStatements.forEach { sql ->
                        // PRAGMA statements that set values return nothing; execSQL is fine for them.
                        db.execSQL(sql)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            return CoverageDb(file, db).also { it.loadResolver() }
        }
    }
}
