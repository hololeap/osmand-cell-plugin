// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/**
 * The globally unique identity of a cell: radio, network (MCC and MNC), area
 * (TAC for LTE and NR, LAC otherwise) and the cell id (CI, or NCI for NR).
 */
data class CellKey(
    val radio: RadioType,
    val mcc: Int,
    val mnc: Int,
    val area: Int,
    val cid: Long,
) {
    /**
     * The physical site this cell is assumed to sit on. The sectors of one LTE
     * site share an eNB id, which is the cell id without its low 8 bits. Other
     * radios are left ungrouped: each cell is its own site.
     */
    val site: SiteKey
        get() = when (radio) {
            RadioType.LTE -> SiteKey(radio, mcc, mnc, cid shr 8, grouped = true)
            else -> SiteKey(radio, mcc, mnc, cid, grouped = false)
        }

    /** LTE only: the sector number within the eNB (the low 8 bits of the cell id). */
    val lteSector: Int?
        get() = if (radio == RadioType.LTE) (cid and 0xFF).toInt() else null

    /** A stable text id, e.g. `LTE-260-2-1234-5678901`. */
    fun toId(): String = "${radio.name}-$mcc-$mnc-$area-$cid"

    companion object {
        fun fromId(id: String): CellKey? {
            val p = id.split('-')
            if (p.size != 5) return null
            val radio = RadioType.entries.firstOrNull { it.name == p[0] } ?: return null
            return CellKey(
                radio,
                p[1].toIntOrNull() ?: return null,
                p[2].toIntOrNull() ?: return null,
                p[3].toIntOrNull() ?: return null,
                p[4].toLongOrNull() ?: return null,
            )
        }
    }
}

/**
 * A site: for LTE an eNB ([node] is the eNB id and [grouped] is true), for every
 * other radio a single cell ([node] is the cell id). The area code is left out
 * on purpose, since an operator can re-plan tracking areas without moving a mast.
 */
data class SiteKey(
    val radio: RadioType,
    val mcc: Int,
    val mnc: Int,
    val node: Long,
    val grouped: Boolean,
) {
    /** A stable text id, e.g. `LTE-260-2-eNB22183` or `GSM-260-2-c4711`. */
    fun toId(): String = "${radio.name}-$mcc-$mnc-${if (grouped) "eNB" else "c"}$node"
}
