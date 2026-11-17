// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.csv

import io.github.hololeap.cellcoverage.core.gpkg.GpkgSchema
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Sample
import java.io.BufferedReader

/**
 * Reads Tower Collector's CSV export (the format written by its
 * `CsvExportFormatter`). Columns are looked up by header name, so files from
 * older versions with fewer columns load too.
 *
 * Column meanings, from Tower Collector's source:
 *  - `lac` holds the TAC for LTE and NR; `cell_id` the CI or NCI.
 *  - `psc` holds the PCI for LTE and NR and the PSC for WCDMA.
 *  - `dbm` is Android's generic level; the radio-specific columns (`rsrp`,
 *    `ss_rsrp`, `rscp`, `rssi`) are preferred when present.
 *  - `accuracy` of 0 means "not available".
 */
object TowerCollectorCsv {

    data class Stats(
        var rows: Int = 0,
        var imported: Int = 0,
        var skipped: Int = 0,
        /** The first few reasons rows were skipped, as "line N: why". */
        val problems: MutableList<String> = mutableListOf(),
    )

    private const val MAX_PROBLEMS = 20
    private val REQUIRED = listOf("mnc", "lac", "cell_id", "lat", "lon", "measured_at", "net_type")

    /**
     * Parses [reader] and hands each valid row to [onSample].
     * @throws IllegalArgumentException when the header lacks a required column.
     */
    fun parse(reader: BufferedReader, onSample: (Sample) -> Unit): Stats {
        val stats = Stats()
        val headerLine = reader.readLine() ?: throw IllegalArgumentException("The file is empty")
        val header = splitLine(headerLine.removePrefix("﻿")).map { it.trim().lowercase() }
        val col = header.withIndex().associate { (i, name) -> name to i }
        val missing = REQUIRED.filter { it !in col }
        require(missing.isEmpty()) {
            "Not a Tower Collector CSV export: missing column(s) ${missing.joinToString()}"
        }

        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isBlank()) continue
            stats.rows++
            val f = splitLine(line)
            fun text(name: String): String? = col[name]?.let { f.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String): Int? = text(name)?.toIntOrNull()?.takeIf { it != Int.MAX_VALUE }
            fun dbl(name: String): Double? = text(name)?.toDoubleOrNull()

            val problem: String? = run {
                val radio = text("net_type")?.let(RadioType::fromName) ?: return@run "unknown net_type '${text("net_type")}'"
                val mnc = int("mnc") ?: return@run "no mnc"
                val area = int("lac") ?: return@run "no lac"
                val cid = text("cell_id")?.toLongOrNull()?.takeIf { it != Long.MAX_VALUE } ?: return@run "no cell_id"
                val lat = dbl("lat")?.takeIf { it in -90.0..90.0 } ?: return@run "bad lat"
                val lon = dbl("lon")?.takeIf { it in -180.0..180.0 } ?: return@run "bad lon"
                val time = text("measured_at")?.let(GpkgSchema::parseIsoTime) ?: return@run "bad measured_at"

                val generic = int("dbm")
                val dbm = when (radio) {
                    RadioType.LTE -> int("rsrp")
                    RadioType.NR -> int("ss_rsrp") ?: int("csi_rsrp")
                    RadioType.WCDMA, RadioType.TDSCDMA -> int("rscp")
                    RadioType.GSM -> int("rssi")
                    RadioType.CDMA -> int("cdma_dbm")
                } ?: generic

                onSample(
                    Sample(
                        timeMs = time,
                        lat = lat,
                        lon = lon,
                        accuracyM = dbl("accuracy")?.toFloat()?.takeIf { it > 0f },
                        speedMps = dbl("speed")?.toFloat(),
                        bearingDeg = dbl("bearing")?.toFloat(),
                        altitudeM = dbl("altitude"),
                        cell = CellKey(radio, int("mcc") ?: 0, mnc, area, cid),
                        registered = text("neighboring")?.equals("true", ignoreCase = true) != true,
                        pci = int("psc"),
                        channel = int("arfcn"),
                        dbm = dbm?.takeIf { it in -160..-20 },
                        rsrq = when (radio) {
                            RadioType.LTE -> int("rsrq")
                            RadioType.NR -> int("ss_rsrq")
                            RadioType.WCDMA -> int("ec_no")
                            else -> null
                        },
                        sinr = when (radio) {
                            RadioType.LTE -> int("rssnr")
                            RadioType.NR -> int("ss_sinr")
                            else -> null
                        },
                        timingAdvance = int("ta")?.takeIf { it >= 0 },
                    ),
                )
                null
            }
            if (problem == null) {
                stats.imported++
            } else {
                stats.skipped++
                if (stats.problems.size < MAX_PROBLEMS) stats.problems += "line $lineNo: $problem"
            }
        }
        return stats
    }

    /** Splits one CSV line on commas, honouring double-quoted fields and "" escapes. */
    internal fun splitLine(line: String): List<String> {
        val out = ArrayList<String>(48)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
