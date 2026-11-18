// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.resolve

import io.github.hololeap.cellcoverage.core.geo.Geo
import io.github.hololeap.cellcoverage.core.geo.Tiles
import io.github.hololeap.cellcoverage.core.model.Candidate
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.IdSource
import io.github.hololeap.cellcoverage.core.model.PhysicalId
import io.github.hololeap.cellcoverage.core.model.Resolution
import io.github.hololeap.cellcoverage.core.model.Sample
import kotlin.math.exp

data class ResolverConfig(
    /** Grid the sightings are kept on: zoom-16 tiles, about 600 m x cos(latitude). */
    val sightingZoom: Int = 16,
    /** How fast a candidate's plausibility falls with distance from where it was seen. */
    val scaleM: Double = 2000.0,
    /** Beyond this a cell is not a candidate at all. */
    val cutoffM: Double = 15_000.0,
    /**
     * The plausibility given to "a cell with this physical id that was never seen
     * identified", as a distance on the same scale: an unseen cell competes like
     * a seen one this far away. With 4 km and a 2 km scale, a lone candidate
     * right here gets 0.88 confidence, one 4 km away 0.5.
     */
    val unseenDistanceM: Double = 4000.0,
    /** At or above this confidence a match counts as RESOLVED, below as AMBIGUOUS. */
    val resolvedConfidence: Double = 0.8,
)

/**
 * Maps neighbour readings that carry only a physical id (channel + PCI) to cell
 * ids, using where each identified cell has been seen.
 *
 * Every identified sample (the serving cell, or a neighbour the phone named in
 * full) is a sighting: "cell C uses physical id P and was heard here". For a
 * reading of P at position x, every cell seen with P within [ResolverConfig.cutoffM]
 * is a candidate, weighted by exp(-d^2 / 2 s^2) where d is the distance from x
 * to the nearest place the cell was seen. A pseudo-candidate stands for a cell
 * that uses P but has never been seen identified. The best candidate's share of
 * the total weight is the confidence.
 *
 * Resolutions are derived data: as sightings accumulate a reading can move from
 * UNRESOLVED to RESOLVED or from one cell to another, so the raw physical id is
 * always kept and readings can be resolved again.
 */
class NeighbourResolver(private val config: ResolverConfig = ResolverConfig()) {

    /** For each physical id, each cell seen using it, and the tiles where it was seen. */
    private val sightings = HashMap<PhysicalId, HashMap<CellKey, HashSet<Long>>>()

    val physicalIds: Int get() = sightings.size

    /** Records an identified sample. Samples without channel or PCI are ignored. */
    fun learn(s: Sample) {
        if (s.idSource != IdSource.REPORTED) return
        val pid = physicalIdOf(s) ?: return
        val t = Tiles.tileAt(s.lat, s.lon, config.sightingZoom)
        sightings.getOrPut(pid) { HashMap() }.getOrPut(s.cell) { HashSet() }.add(pack(t.x, t.y))
    }

    fun resolve(pid: PhysicalId, lat: Double, lon: Double): Resolution {
        val cells = sightings[pid] ?: return unresolved()
        val found = ArrayList<Pair<CellKey, Double>>()
        for ((cell, tiles) in cells) {
            val d = tiles.minOf { distanceToTileM(it, lat, lon) }
            if (d <= config.cutoffM) found += cell to d
        }
        if (found.isEmpty()) return unresolved()
        val unseen = weight(config.unseenDistanceM)
        val total = found.sumOf { weight(it.second) } + unseen
        val candidates = found
            .map { (c, d) -> Candidate(c, weight(d) / total, d) }
            .sortedByDescending { it.probability }
        val best = candidates.first()
        val source = if (best.probability >= config.resolvedConfidence) IdSource.RESOLVED else IdSource.AMBIGUOUS
        return Resolution(source, best.cell, best.probability, candidates)
    }

    private fun unresolved() = Resolution(IdSource.UNRESOLVED, null, 0.0, emptyList())

    private fun weight(d: Double): Double {
        val z = d / config.scaleM
        return exp(-0.5 * z * z)
    }

    private fun distanceToTileM(packed: Long, lat: Double, lon: Double): Double {
        val x = (packed shr 32).toInt()
        val y = packed.toInt()
        val z = config.sightingZoom
        // Distance to the tile's centre, less half its diagonal-ish size, floored
        // at zero: being inside the tile counts as "seen right here".
        val cLat = Tiles.tileYToLat(y + 0.5, z)
        val cLon = Tiles.tileXToLon(x + 0.5, z)
        val half = Tiles.tileSizeM(cLat, z) / 2
        return (Geo.haversineM(lat, lon, cLat, cLon) - half).coerceAtLeast(0.0)
    }

    companion object {
        fun physicalIdOf(s: Sample): PhysicalId? {
            val ch = s.channel ?: return null
            val pci = s.pci ?: return null
            return PhysicalId(s.cell.radio, s.cell.mcc, s.cell.mnc, ch, pci)
        }

        private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    }
}
