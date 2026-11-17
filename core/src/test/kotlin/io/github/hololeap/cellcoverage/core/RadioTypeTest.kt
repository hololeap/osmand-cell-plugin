// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioTypeTest {
    @Test
    fun parsesKnownNames() {
        assertEquals(RadioType.LTE, RadioType.fromName("LTE"))
        assertEquals(RadioType.WCDMA, RadioType.fromName("umts"))
        assertEquals(RadioType.NR, RadioType.fromName(" NR "))
    }

    @Test
    fun rejectsUnknownNames() {
        assertNull(RadioType.fromName("WIMAX"))
    }

    @Test
    fun generations() {
        assertEquals(4, RadioType.LTE.generation)
        assertEquals(5, RadioType.NR.generation)
    }
}
