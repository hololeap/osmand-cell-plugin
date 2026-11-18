// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.bins

import io.github.hololeap.cellcoverage.core.geo.Tiles
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.IdSource
import io.github.hololeap.cellcoverage.core.model.Sample

/**
 * All samples of one cell that fall in one zoom-20 tile (about 30 m), reduced
 * to running statistics. One bin is one observation for the estimator, which
 * removes the bias from sitting parked in one spot.
 *
 * The update is O(1) in the number of samples: a running mean of the signal and
 * of the position, and a histogram of timing-advance values (which are small
 * integers, and in practice take only a handful of values within 30 m).
 */
class CellBin(
    val cell: CellKey,
    val bx: Int,
    val by: Int,
) {
    /** Samples with a signal value. */
    var n: Int = 0
        private set
    var meanDbm: Double = 0.0
        private set
    var meanLat: Double = 0.0
        private set

    /**
     * Sum of the samples' id confidences: equal to [n] when every cell id was
     * reported by the phone, less when some were matched by the neighbour resolver.
     */
    var weightSum: Double = 0.0
        private set

    /** The bin's weight in the estimator: the mean id confidence of its samples. */
    val weight: Double get() = if (n == 0) 0.0 else weightSum / n
    var meanLon: Double = 0.0
        private set
    var firstT: Long = Long.MAX_VALUE
        private set
    var lastT: Long = Long.MIN_VALUE
        private set
    private val ta = sortedMapOf<Int, Int>()

    val taCount: Int get() = ta.values.sum()

    /** The median timing advance, the lower one when the count is even; null if none. */
    val taMedian: Int?
        get() {
            val total = taCount
            if (total == 0) return null
            var seen = 0
            for ((value, count) in ta) {
                seen += count
                if (2 * seen >= total) return value
            }
            return null
        }

    fun add(s: Sample) {
        val dbm = s.dbm ?: return
        n++
        weightSum += s.idConfidence
        meanDbm += (dbm - meanDbm) / n
        meanLat += (s.lat - meanLat) / n
        meanLon += (s.lon - meanLon) / n
        if (s.timeMs < firstT) firstT = s.timeMs
        if (s.timeMs > lastT) lastT = s.timeMs
        s.timingAdvance?.let { ta[it] = (ta[it] ?: 0) + 1 }
    }

    /** The timing-advance histogram as stored in the database: "value:count,...". */
    fun taHistogram(): String = ta.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        fun keyOf(s: Sample): Triple<CellKey, Int, Int> {
            val t = Tiles.binAt(s.lat, s.lon)
            return Triple(s.cell, t.x, t.y)
        }

        /** Rebuilds a bin from its stored columns. */
        fun restore(
            cell: CellKey, bx: Int, by: Int, n: Int, meanDbm: Double,
            meanLat: Double, meanLon: Double, firstT: Long, lastT: Long, taHistogram: String?,
            weightSum: Double = n.toDouble(),
        ): CellBin = CellBin(cell, bx, by).also { b ->
            b.n = n
            b.weightSum = weightSum
            b.meanDbm = meanDbm
            b.meanLat = meanLat
            b.meanLon = meanLon
            b.firstT = firstT
            b.lastT = lastT
            taHistogram?.split(',')?.forEach { pair ->
                val (v, c) = pair.split(':').takeIf { it.size == 2 } ?: return@forEach
                val value = v.toIntOrNull() ?: return@forEach
                val count = c.toIntOrNull() ?: return@forEach
                b.ta[value] = count
            }
        }
    }
}

/**
 * An in-memory set of bins, keyed by cell and tile.
 *
 * Samples whose cell id was only guessed (AMBIGUOUS) are left out unless
 * [includeAmbiguous] is set; RESOLVED samples go in with their confidence as
 * weight. UNRESOLVED readings never reach here, since they have no cell.
 */
class CellBins(private val includeAmbiguous: Boolean = false) {
    private val bins = LinkedHashMap<Triple<CellKey, Int, Int>, CellBin>()

    val size: Int get() = bins.size
    val all: Collection<CellBin> get() = bins.values

    /** Adds a sample; returns the bin it landed in, or null if it had no signal. */
    fun add(s: Sample): CellBin? {
        if (s.dbm == null) return null
        if (s.idSource == IdSource.UNRESOLVED) return null
        if (s.idSource == IdSource.AMBIGUOUS && !includeAmbiguous) return null
        val key = CellBin.keyOf(s)
        val bin = bins.getOrPut(key) { CellBin(key.first, key.second, key.third) }
        bin.add(s)
        return bin
    }

    fun put(bin: CellBin) {
        bins[Triple(bin.cell, bin.bx, bin.by)] = bin
    }

    fun bySite(): Map<io.github.hololeap.cellcoverage.core.model.SiteKey, List<CellBin>> =
        bins.values.groupBy { it.cell.site }
}
