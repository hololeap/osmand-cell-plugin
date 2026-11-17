// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.gpkg

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The app's database is a GeoPackage, so QGIS (through GDAL) opens the file as
 * it is. That takes three things on top of ordinary SQLite tables:
 *
 *  1. `PRAGMA application_id` set to "GPKG" and `PRAGMA user_version` set to the
 *     GeoPackage version. The app therefore cannot use Android's
 *     `SQLiteOpenHelper`, which keeps its own number in `user_version`; the
 *     schema version lives in the `app_meta` table instead.
 *  2. Three metadata tables: `gpkg_spatial_ref_sys`, `gpkg_contents` and
 *     `gpkg_geometry_columns`.
 *  3. In each feature table an `INTEGER PRIMARY KEY AUTOINCREMENT` column and a
 *     geometry column holding [GpkgGeometry] blobs.
 *
 * There is no R-tree spatial index: it needs SQL functions (`ST_MinX` and
 * friends) that Android's SQLite does not have. QGIS works without it, just
 * slower on large tables, and can add one itself on a copy of the file.
 *
 * Every feature table carries the time twice: `t` in epoch milliseconds for the
 * app's own queries, and `time` as ISO-8601 text, which is the GeoPackage
 * DATETIME type and what QGIS's temporal tools read. Tables fed by position
 * fixes also carry the bin (`bx`, `by`: tile coordinates at zoom 20) so that
 * heatmap aggregation is a plain GROUP BY.
 */
object GpkgSchema {
    /** "GPKG" in ASCII. */
    const val APPLICATION_ID = 0x47504B47

    /** GeoPackage 1.3.0. */
    const val USER_VERSION = 10300

    /** Our own schema version, stored in `app_meta` under [META_SCHEMA_VERSION]. */
    const val SCHEMA_VERSION = 1
    const val META_SCHEMA_VERSION = "schema_version"

    const val GEOM = "geom"

    const val SAMPLES = "samples"
    const val CELL_BINS = "cell_bins"
    const val CELLS = "cells"
    const val TOWERS = "towers"
    const val PROBES = "probes"
    const val CONN_EVENTS = "conn_events"

    /** Feature tables with the description shown as the layer's abstract in QGIS. */
    val featureTables: List<Pair<String, String>> = listOf(
        SAMPLES to "One row per observed cell per saved GPS fix",
        CELL_BINS to "Samples of one cell averaged over a ~30 m grid; the tower estimator's input",
        CELLS to "One row per cell, at its online-database position when one is known",
        TOWERS to "One row per site, at the position currently shown (estimated or online)",
        PROBES to "Latency, loss and throughput probes; failed probes included",
        CONN_EVENTS to "Network lost, available and validated transitions",
    )

    private const val WKT_4326 =
        "GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563," +
            "AUTHORITY[\"EPSG\",\"7030\"]],AUTHORITY[\"EPSG\",\"6326\"]],PRIMEM[\"Greenwich\",0," +
            "AUTHORITY[\"EPSG\",\"8901\"]],UNIT[\"degree\",0.0174532925199433," +
            "AUTHORITY[\"EPSG\",\"9122\"]],AUTHORITY[\"EPSG\",\"4326\"]]"

    val pragmas: List<String> = listOf(
        "PRAGMA application_id = $APPLICATION_ID",
        "PRAGMA user_version = $USER_VERSION",
    )

    private val metadataTables: List<String> = listOf(
        """
        CREATE TABLE gpkg_spatial_ref_sys (
            srs_name TEXT NOT NULL,
            srs_id INTEGER NOT NULL PRIMARY KEY,
            organization TEXT NOT NULL,
            organization_coordsys_id INTEGER NOT NULL,
            definition TEXT NOT NULL,
            description TEXT
        )
        """,
        "INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined cartesian SRS', -1, 'NONE', -1, 'undefined', 'undefined cartesian coordinate reference system')",
        "INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined geographic SRS', 0, 'NONE', 0, 'undefined', 'undefined geographic coordinate reference system')",
        "INSERT INTO gpkg_spatial_ref_sys VALUES ('WGS 84 geodetic', 4326, 'EPSG', 4326, '$WKT_4326', 'longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid')",
        """
        CREATE TABLE gpkg_contents (
            table_name TEXT NOT NULL PRIMARY KEY,
            data_type TEXT NOT NULL,
            identifier TEXT UNIQUE,
            description TEXT DEFAULT '',
            last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            min_x DOUBLE,
            min_y DOUBLE,
            max_x DOUBLE,
            max_y DOUBLE,
            srs_id INTEGER,
            CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
        )
        """,
        """
        CREATE TABLE gpkg_geometry_columns (
            table_name TEXT NOT NULL,
            column_name TEXT NOT NULL,
            geometry_type_name TEXT NOT NULL,
            srs_id INTEGER NOT NULL,
            z TINYINT NOT NULL,
            m TINYINT NOT NULL,
            CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name),
            CONSTRAINT uk_gc_table_name UNIQUE (table_name),
            CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
            CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id)
        )
        """,
    )

    private val dataTables: List<String> = listOf(
        """
        CREATE TABLE app_meta (
            key TEXT NOT NULL PRIMARY KEY,
            value TEXT
        )
        """,
        "INSERT INTO app_meta VALUES ('$META_SCHEMA_VERSION', '$SCHEMA_VERSION')",
        """
        CREATE TABLE $SAMPLES (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            t INTEGER NOT NULL,
            time DATETIME,
            bx INTEGER,
            by INTEGER,
            accuracy REAL,
            speed REAL,
            bearing REAL,
            altitude REAL,
            cell TEXT NOT NULL,
            site TEXT NOT NULL,
            radio TEXT NOT NULL,
            generation INTEGER,
            mcc INTEGER,
            mnc INTEGER,
            area INTEGER,
            cid INTEGER,
            registered BOOLEAN,
            pci INTEGER,
            channel INTEGER,
            bands TEXT,
            dbm INTEGER,
            rsrq INTEGER,
            sinr INTEGER,
            ta INTEGER,
            calls TEXT,
            voice_state INTEGER,
            data_net_type INTEGER,
            display_override INTEGER,
            roaming BOOLEAN,
            services TEXT,
            link_down_kbps INTEGER,
            sub_id INTEGER,
            source TEXT
        )
        """,
        // Also what makes re-importing the same CSV harmless (INSERT OR IGNORE).
        "CREATE UNIQUE INDEX samples_t_cell ON $SAMPLES (t, cell)",
        "CREATE INDEX samples_bin ON $SAMPLES (bx, by)",
        """
        CREATE TABLE $CELL_BINS (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            cell TEXT NOT NULL,
            site TEXT NOT NULL,
            bx INTEGER NOT NULL,
            by INTEGER NOT NULL,
            n INTEGER NOT NULL,
            mean_dbm REAL NOT NULL,
            ta_n INTEGER NOT NULL DEFAULT 0,
            ta_median INTEGER,
            ta_hist TEXT,
            first_t INTEGER,
            last_t INTEGER
        )
        """,
        "CREATE UNIQUE INDEX cell_bins_key ON $CELL_BINS (cell, bx, by)",
        "CREATE INDEX cell_bins_site ON $CELL_BINS (site)",
        """
        CREATE TABLE $CELLS (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            cell TEXT NOT NULL UNIQUE,
            site TEXT NOT NULL,
            radio TEXT NOT NULL,
            mcc INTEGER,
            mnc INTEGER,
            area INTEGER,
            cid INTEGER,
            pci INTEGER,
            channel INTEGER,
            bands TEXT,
            samples INTEGER NOT NULL DEFAULT 0,
            first_t INTEGER,
            last_t INTEGER,
            est_azimuth REAL,
            est_power_dbm REAL,
            online_provider TEXT,
            online_status TEXT,
            online_lat REAL,
            online_lon REAL,
            online_range REAL,
            online_samples INTEGER,
            online_t INTEGER
        )
        """,
        "CREATE INDEX cells_site ON $CELLS (site)",
        """
        CREATE TABLE $TOWERS (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            site TEXT NOT NULL UNIQUE,
            radio TEXT NOT NULL,
            mcc INTEGER,
            mnc INTEGER,
            node INTEGER,
            source TEXT NOT NULL DEFAULT 'ESTIMATED',
            cells INTEGER,
            samples INTEGER,
            first_t INTEGER,
            last_t INTEGER,
            est_lat REAL,
            est_lon REAL,
            est_radius REAL,
            est_exponent REAL,
            est_rms REAL,
            est_bins INTEGER,
            est_span REAL,
            est_ambiguous BOOLEAN,
            est_at_limit BOOLEAN,
            est_t INTEGER,
            online_provider TEXT,
            online_lat REAL,
            online_lon REAL,
            online_range REAL,
            online_cells INTEGER,
            online_t INTEGER
        )
        """,
        """
        CREATE TABLE $PROBES (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            t INTEGER NOT NULL,
            time DATETIME,
            bx INTEGER,
            by INTEGER,
            accuracy REAL,
            kind TEXT NOT NULL,
            method TEXT,
            target TEXT,
            ok BOOLEAN,
            sent INTEGER,
            received INTEGER,
            loss REAL,
            rtt_min REAL,
            rtt_avg REAL,
            rtt_max REAL,
            bytes INTEGER,
            duration_ms INTEGER,
            kbps REAL,
            error TEXT,
            radio TEXT,
            cell TEXT,
            dbm INTEGER
        )
        """,
        "CREATE INDEX probes_t ON $PROBES (t)",
        "CREATE INDEX probes_bin ON $PROBES (bx, by)",
        """
        CREATE TABLE $CONN_EVENTS (
            fid INTEGER PRIMARY KEY AUTOINCREMENT,
            $GEOM POINT,
            t INTEGER NOT NULL,
            time DATETIME,
            bx INTEGER,
            by INTEGER,
            accuracy REAL,
            event TEXT NOT NULL,
            transport TEXT,
            radio TEXT,
            cell TEXT,
            detail TEXT
        )
        """,
        "CREATE INDEX conn_events_t ON $CONN_EVENTS (t)",
    )

    private val registrations: List<String> = featureTables.flatMap { (table, description) ->
        listOf(
            "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, srs_id) " +
                "VALUES ('$table', 'features', '$table', '${description.replace("'", "''")}', ${GpkgGeometry.SRS_WGS84})",
            "INSERT INTO gpkg_geometry_columns VALUES ('$table', '$GEOM', 'POINT', ${GpkgGeometry.SRS_WGS84}, 0, 0)",
        )
    }

    /** Everything needed to turn an empty SQLite file into our GeoPackage, in order. */
    val createStatements: List<String> =
        (pragmas + metadataTables + dataTables + registrations).map { it.trimIndent().trim() }

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Epoch milliseconds as GeoPackage DATETIME text, e.g. `2026-11-17T03:23:47.000Z`. */
    fun isoTime(epochMs: Long): String = ISO.format(Instant.ofEpochMilli(epochMs))

    /** The inverse of [isoTime]; also accepts other ISO-8601 instants. Null if unparseable. */
    fun parseIsoTime(text: String): Long? =
        try {
            Instant.parse(text.trim()).toEpochMilli()
        } catch (e: Exception) {
            null
        }
}
