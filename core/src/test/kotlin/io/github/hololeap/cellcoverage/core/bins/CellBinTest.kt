// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.bins

import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CellBinTest {
    private val cell = CellKey(RadioType.LTE, 232, 1, 4711, 5678849)
    private fun s(dbm: Int?, ta: Int? = null, lat: Double = 47.0, t: Long = 0) =
        Sample(timeMs = t, lat = lat, lon = 11.0, cell = cell, dbm = dbm, timingAdvance = ta)

    @Test
    fun runningMeanAndMedian() {
        val bins = CellBins()
        listOf(-90 to 5, -100 to 7, -80 to 5, -110 to 9).forEachIndexed { i, (d, ta) -> bins.add(s(d, ta, t = i.toLong())) }
        val b = bins.all.single()
        assertEquals(4, b.n)
        assertEquals(-95.0, b.meanDbm, 1e-9)
        assertEquals(5, b.taMedian)
        assertEquals(0L, b.firstT)
        assertEquals(3L, b.lastT)
    }

    @Test
    fun samplesWithoutSignalAreIgnored() {
        val bins = CellBins()
        assertNull(bins.add(s(null)))
        assertEquals(0, bins.size)
    }

    @Test
    fun separatePlacesMakeSeparateBins() {
        val bins = CellBins()
        bins.add(s(-90, lat = 47.0))
        bins.add(s(-90, lat = 47.001)) // ~110 m north
        assertEquals(2, bins.size)
    }

    @Test
    fun restoresFromStoredColumns() {
        val b = CellBins().also { it.add(s(-90, 3)); it.add(s(-92, 4)); it.add(s(-94, 4)) }.all.single()
        val r = CellBin.restore(cell, b.bx, b.by, b.n, b.meanDbm, b.meanLat, b.meanLon, b.firstT, b.lastT, b.taHistogram())
        assertEquals("3:1,4:2", r.taHistogram())
        assertEquals(4, r.taMedian)
        r.add(s(-80))
        assertEquals(4, r.n)
        assertEquals(-89.0, r.meanDbm, 1e-9)
    }
}
