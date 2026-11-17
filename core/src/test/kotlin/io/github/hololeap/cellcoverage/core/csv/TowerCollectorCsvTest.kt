// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.csv

import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TowerCollectorCsvTest {

    // The header exactly as Tower Collector 3.0.2 writes it (CsvExportFormatter line 46),
    // and rows shaped like its output: quoted dates, empty optional fields, CRLF.
    private val header =
        "mcc,mnc,lac,cell_id,short_cell_id,rnc,psc,asu,dbm,ta,lat,lon,accuracy,speed,bearing,altitude," +
            "measured_at,net_type,neighboring,discovered_at,device,rsrp,rsrq,rssi,rssnr,cqi,rscp,csi_rsrp," +
            "csi_rsrq,csi_sinr,ss_rsrp,ss_rsrq,ss_sinr,cdma_dbm,cdma_ecio,evdo_dbm,evdo_ecio,evdo_snr,ec_no,arfcn"

    private val lte =
        "232,1,4711,5678849,22183,1,301,45,-95,7,47.123456,11.654321,8,12.5,90,600," +
            "\"2024-05-01T10:20:30.123Z\",LTE,false,\"2024-05-01T10:20:29.000Z\",\"Google Pixel 7\"," +
            "-97,-11,-65,12,,,,,,,,,,,,,,,6300"
    private val nr =
        "232,1,4711,123456789012,,,500,,-80,,47.2,11.7,5,0,0,0," +
            "\"2024-05-01T10:21:00.000Z\",NR,true,\"2024-05-01T10:21:00.000Z\",\"Google Pixel 7\"," +
            ",,,,,,-90,-12,10,-88,-11,15,,,,,,,632628"
    private val gsm =
        "232,3,100,4711,,,,20,-73,3,47.3,11.8,0,0,0,0," +
            "\"2024-05-01T10:22:00.000Z\",GSM,false,\"2024-05-01T10:22:00.000Z\",\"x\"," +
            ",,-73,,,,,,,,,,,,,,,,,62"

    private fun parse(vararg lines: String): Pair<TowerCollectorCsv.Stats, List<Sample>> {
        val out = ArrayList<Sample>()
        val stats = TowerCollectorCsv.parse(lines.joinToString("\r\n").reader().buffered()) { out += it }
        return stats to out
    }

    @Test
    fun readsLte() {
        val (stats, s) = parse(header, lte)
        assertEquals(1, stats.imported)
        val x = s.single()
        assertEquals(RadioType.LTE, x.cell.radio)
        assertEquals(232, x.cell.mcc)
        assertEquals(4711, x.cell.area)
        assertEquals(5678849L, x.cell.cid)
        assertEquals(22183L, x.cell.site.node) // eNB = 5678849 >> 8
        assertEquals(-97, x.dbm) // RSRP preferred over the generic dbm column
        assertEquals(-11, x.rsrq)
        assertEquals(12, x.sinr)
        assertEquals(7, x.timingAdvance)
        assertEquals(301, x.pci)
        assertEquals(6300, x.channel)
        assertEquals(1714558830123L, x.timeMs)
        assertEquals(8f, x.accuracyM)
        assertTrue(x.registered)
    }

    @Test
    fun readsNrNeighborWithLongCellId() {
        val (_, s) = parse(header, nr)
        val x = s.single()
        assertEquals(RadioType.NR, x.cell.radio)
        assertEquals(123456789012L, x.cell.cid)
        assertEquals(-88, x.dbm) // SS-RSRP, preferred over CSI-RSRP (-90)
        assertEquals(15, x.sinr)
        assertFalse(x.registered)
        assertFalse(x.cell.site.grouped)
    }

    @Test
    fun readsGsmAndTreatsZeroAccuracyAsMissing() {
        val x = parse(header, gsm).second.single()
        assertEquals(RadioType.GSM, x.cell.radio)
        assertEquals(-73, x.dbm)
        assertNull(x.accuracyM)
    }

    @Test
    fun skipsBadRowsAndSaysWhy() {
        val bad = lte.replace("LTE", "WIMAX")
        val (stats, s) = parse(header, lte, bad, "", gsm)
        assertEquals(3, stats.rows)
        assertEquals(2, stats.imported)
        assertEquals(1, stats.skipped)
        assertTrue(stats.problems.single().startsWith("line 3:"), stats.problems.toString())
        assertEquals(2, s.size)
    }

    @Test
    fun acceptsOlderFilesWithFewerColumns() {
        val (stats, s) = parse("mcc,mnc,lac,cell_id,dbm,lat,lon,measured_at,net_type",
            "232,1,4711,5678849,-95,47.1,11.6,\"2024-05-01T10:20:30.123Z\",LTE")
        assertEquals(1, stats.imported)
        assertEquals(-95, s.single().dbm)
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { parse("a,b,c", "1,2,3") }
    }

    @Test
    fun splitsQuotedFields() {
        assertEquals(listOf("a", "b,c", "d\"e", ""), TowerCollectorCsv.splitLine("a,\"b,c\",\"d\"\"e\","))
    }
}
