// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/**
 * Converts a timing advance to a one-way distance from the antenna.
 *
 * LTE: Android's `CellSignalStrengthLte.getTimingAdvance()` reports 0..1282 in
 * steps of 16 Ts (TS 36.213 section 4.2.3), 0.52 microseconds of round trip,
 * which is 78.12 m one way. GSM: 0..63 in bit periods (48/13 microseconds of
 * round trip), 553.5 m one way. The half step centres the distance in its
 * quantisation interval. Other radios return null: Android exposes no usable
 * timing advance for them at minSdk 29.
 */
object TimingAdvance {
    const val LTE_STEP_M = 78.12
    const val GSM_STEP_M = 553.5

    fun distanceM(radio: RadioType, ta: Int): Double? =
        when (radio) {
            RadioType.LTE -> if (ta in 0..1282) (ta + 0.5) * LTE_STEP_M else null
            RadioType.GSM -> if (ta in 0..63) (ta + 0.5) * GSM_STEP_M else null
            else -> null
        }

    /** The standard deviation to give a timing-advance ring, in metres. */
    fun sigmaM(radio: RadioType): Double =
        when (radio) {
            RadioType.GSM -> 400.0
            else -> 150.0
        }
}
