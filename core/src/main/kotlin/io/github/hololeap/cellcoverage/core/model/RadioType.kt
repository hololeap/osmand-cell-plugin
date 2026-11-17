// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/** Radio access technology of a cell, with the generation shown to the user. */
enum class RadioType(val generation: Int) {
    GSM(2),
    CDMA(2),
    WCDMA(3),
    TDSCDMA(3),
    LTE(4),
    NR(5);

    companion object {
        /** Parses the names Tower Collector writes in the `net_type` CSV column. */
        fun fromName(name: String): RadioType? =
            when (name.trim().uppercase()) {
                "GSM" -> GSM
                "CDMA" -> CDMA
                "UMTS", "WCDMA" -> WCDMA
                "TD-SCDMA", "TDSCDMA" -> TDSCDMA
                "LTE" -> LTE
                "NR", "5G" -> NR
                else -> null
            }
    }
}
