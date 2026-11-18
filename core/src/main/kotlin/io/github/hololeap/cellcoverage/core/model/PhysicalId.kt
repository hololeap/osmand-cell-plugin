// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/**
 * What a phone can always tell about a cell, even a neighbour it reports without
 * a cell id: the radio, the channel and the physical-layer id (PCI for LTE and
 * NR, PSC for WCDMA, BSIC for GSM). Operators reuse physical ids every few
 * kilometres, so this is not unique; [mcc] and [mnc] are the serving network's,
 * since neighbour readings usually carry none of their own.
 */
data class PhysicalId(
    val radio: RadioType,
    val mcc: Int,
    val mnc: Int,
    val channel: Int,
    val pci: Int,
) {
    fun toId(): String = "${radio.name}-$mcc-$mnc-ch$channel-pci$pci"

    companion object {
        /** Parses [toId] text, e.g. `LTE-232-1-ch6300-pci123`. */
        fun fromId(s: String): PhysicalId? {
            val p = s.split('-')
            if (p.size != 5 || !p[3].startsWith("ch") || !p[4].startsWith("pci")) return null
            val radio = RadioType.entries.firstOrNull { it.name == p[0] } ?: return null
            return PhysicalId(
                radio,
                p[1].toIntOrNull() ?: return null,
                p[2].toIntOrNull() ?: return null,
                p[3].removePrefix("ch").toIntOrNull() ?: return null,
                p[4].removePrefix("pci").toIntOrNull() ?: return null,
            )
        }
    }
}

/** Where a sample's cell id came from. Stored as text in the `id_source` column. */
enum class IdSource {
    /** The phone reported the full cell id. */
    REPORTED,

    /** Matched from the physical id to one cell with high confidence. */
    RESOLVED,

    /**
     * Matched to a best guess, but other cells (or an unseen one) are plausible.
     * The guess is kept so it can be shown and revisited; the estimator ignores
     * these by default.
     */
    AMBIGUOUS,

    /** No identified cell with this physical id has been seen anywhere near. */
    UNRESOLVED,
}

/** One candidate for a physical id, with its share of the probability. */
data class Candidate(val cell: CellKey, val probability: Double, val distanceM: Double)

/**
 * The outcome of matching a physical id to a cell id. [cell] is the best guess
 * (null when [source] is UNRESOLVED). [confidence] is the guess's probability,
 * which includes the chance that the true cell is one never seen identified.
 * [candidates] lists every cell considered, best first, including the guess.
 */
data class Resolution(
    val source: IdSource,
    val cell: CellKey?,
    val confidence: Double,
    val candidates: List<Candidate>,
) {
    /** The candidates as stored in the `resolve_alts` column: "cellId:probability:distanceM;...". */
    fun candidatesText(): String =
        candidates.joinToString(";") { "${it.cell.toId()}:${"%.3f".format(java.util.Locale.ROOT, it.probability)}:${it.distanceM.toInt()}" }

    companion object {
        val REPORTED = Resolution(IdSource.REPORTED, null, 1.0, emptyList())
    }
}
