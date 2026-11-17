// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CellKeyTest {
    @Test
    fun lteSectorsShareASite() {
        val a = CellKey(RadioType.LTE, 232, 1, 4711, (22183L shl 8) + 1)
        val b = CellKey(RadioType.LTE, 232, 1, 4712, (22183L shl 8) + 2)
        assertEquals(a.site, b.site) // a TAC change does not split the site
        assertEquals("LTE-232-1-eNB22183", a.site.toId())
        assertEquals(2, b.lteSector)
    }

    @Test
    fun otherRadiosAreUngrouped() {
        val g = CellKey(RadioType.GSM, 232, 3, 100, 4711)
        assertEquals("GSM-232-3-c4711", g.site.toId())
        assertNull(g.lteSector)
    }

    @Test
    fun idRoundTrips() {
        val k = CellKey(RadioType.NR, 310, 260, 12345, 123456789012L)
        assertEquals(k, CellKey.fromId(k.toId()))
        assertNull(CellKey.fromId("LTE-1-2-3"))
    }

    @Test
    fun timingAdvanceDistances() {
        assertEquals(39.06, TimingAdvance.distanceM(RadioType.LTE, 0)!!, 0.01)
        assertEquals(507.78, TimingAdvance.distanceM(RadioType.LTE, 6)!!, 0.01)
        assertEquals(276.75, TimingAdvance.distanceM(RadioType.GSM, 0)!!, 0.01)
        assertNull(TimingAdvance.distanceM(RadioType.LTE, 1283))
        assertNull(TimingAdvance.distanceM(RadioType.WCDMA, 3))
    }
}
