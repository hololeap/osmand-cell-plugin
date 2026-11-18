// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.resolve

import io.github.hololeap.cellcoverage.core.bins.CellBins
import io.github.hololeap.cellcoverage.core.estimator.Estimate
import io.github.hololeap.cellcoverage.core.estimator.SyntheticSite
import io.github.hololeap.cellcoverage.core.estimator.TowerEstimator
import io.github.hololeap.cellcoverage.core.geo.PointM
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.IdSource
import io.github.hololeap.cellcoverage.core.model.PhysicalId
import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeighbourResolverTest {
    private val pid = PhysicalId(RadioType.LTE, 232, 1, 6300, 123)
    private val a = CellKey(RadioType.LTE, 232, 1, 4711, 1000)
    private val b = CellKey(RadioType.LTE, 232, 1, 4711, 2000)

    private fun seen(cell: CellKey, lat: Double, lon: Double) =
        Sample(timeMs = 0, lat = lat, lon = lon, cell = cell, pci = pid.pci, channel = pid.channel, dbm = -90)

    @Test
    fun loneNearbyCandidateIsResolved() {
        val r = NeighbourResolver().apply { learn(seen(a, 47.0, 11.0)) }.resolve(pid, 47.0, 11.0)
        assertEquals(IdSource.RESOLVED, r.source)
        assertEquals(a, r.cell)
        assertEquals(0.88, r.confidence, 0.01) // the unseen-cell alternative keeps it below 1
    }

    @Test
    fun loneDistantCandidateIsOnlyAGuess() {
        // ~4.4 km away: as plausible as a cell never seen identified.
        val r = NeighbourResolver().apply { learn(seen(a, 47.04, 11.0)) }.resolve(pid, 47.0, 11.0)
        assertEquals(IdSource.AMBIGUOUS, r.source)
        assertEquals(a, r.cell)
        assertTrue(r.confidence in 0.4..0.6, "confidence ${r.confidence}")
    }

    @Test
    fun twoEquallyCloseCandidatesAreAmbiguousAndBothKept() {
        val res = NeighbourResolver().apply {
            learn(seen(a, 47.01, 11.0))
            learn(seen(b, 46.99, 11.0))
        }
        val r = res.resolve(pid, 47.0, 11.0)
        assertEquals(IdSource.AMBIGUOUS, r.source)
        assertEquals(setOf(a, b), r.candidates.map { it.cell }.toSet())
        assertTrue(r.candidates.all { it.probability in 0.3..0.5 }, r.candidatesText())
    }

    @Test
    fun nothingWithinReachIsUnresolved() {
        val res = NeighbourResolver().apply { learn(seen(a, 47.5, 11.0)) } // ~55 km
        val r = res.resolve(pid, 47.0, 11.0)
        assertEquals(IdSource.UNRESOLVED, r.source)
        assertNull(r.cell)
        assertEquals(IdSource.UNRESOLVED, res.resolve(pid.copy(pci = 7), 47.0, 11.0).source)
    }

    @Test
    fun onlyReportedIdsAreLearned() {
        val res = NeighbourResolver().apply { learn(seen(a, 47.0, 11.0).copy(idSource = IdSource.RESOLVED)) }
        assertEquals(IdSource.UNRESOLVED, res.resolve(pid, 47.0, 11.0).source)
    }

    /**
     * The point of the resolver: a phone that names only its serving cell. The
     * neighbour readings, once matched, give the estimator the sectors' back
     * lobes, which is what pins the site down.
     */
    @Test
    fun resolvedNeighboursImproveTheEstimate() {
        val est = TowerEstimator()
        fun p80(xs: List<Double>) = xs.sorted()[7]
        val servingOnly = ArrayList<Double>()
        val withResolved = ArrayList<Double>()
        var resolved = 0
        var total = 0
        for (seed in 1..10) {
            val site = SyntheticSite(exponent = 3.5, shadowDb = 8.0, seed = seed)
            val (serving, anon) = site.driveWithAnonymousNeighbours(SyntheticSite.loop())
            val res = NeighbourResolver().apply { serving.forEach(::learn) }
            val matched = anon.mapNotNull { n ->
                val r = res.resolve(n.physical, n.lat, n.lon)
                r.cell?.let { n.toSample(it, r) }
            }
            resolved += matched.count { it.idSource == IdSource.RESOLVED }
            total += anon.size
            val e1 = assertIs<Estimate.Located>(est.estimateSite(site.bins(serving).all.toList()))
            val bins = CellBins().also { b -> (serving + matched).forEach { b.add(it) } }
            val e2 = assertIs<Estimate.Located>(est.estimateSite(bins.all.toList()))
            servingOnly += site.distanceTo(e1.position)
            withResolved += site.distanceTo(e2.position)
        }
        println("serving only p80 ${p80(servingOnly).toInt()} m, with resolved neighbours ${p80(withResolved).toInt()} m, $resolved of $total readings resolved")
        assertTrue(resolved > total * 0.9, "$resolved of $total resolved")
        assertTrue(p80(withResolved) < p80(servingOnly) * 0.7, "serving only ${p80(servingOnly)} m, with neighbours ${p80(withResolved)} m")
    }

    /**
     * Two sites 8 km apart with the same PCIs (operators reuse them). Readings
     * near one site resolve to it; between them the resolver says it cannot tell.
     * (At 5 km apart a reading next to one site already gets only 0.79: the other
     * site's 4 km-distant sightings plus the unseen-cell alternative. Deliberate.)
     */
    @Test
    fun reusedPhysicalIdsAreSortedByDistance() {
        val siteA = SyntheticSite(seed = 1)
        val siteB = SyntheticSite(seed = 2, site = PointM(8000.0, 0.0), enbId = 30000L)
        val res = NeighbourResolver()
        siteA.drive(listOf(PointM(-1000.0, 300.0), PointM(1000.0, 300.0))).filter { it.registered }.forEach(res::learn)
        siteB.drive(listOf(PointM(7000.0, 300.0), PointM(9000.0, 300.0))).filter { it.registered }.forEach(res::learn)
        val pid = siteA.physicalOf(0)
        fun at(x: Double) = siteA.proj.toLatLon(x, 300.0).let { res.resolve(pid, it.lat, it.lon) }
        val nearA = at(0.0)
        assertEquals(IdSource.RESOLVED, nearA.source)
        assertEquals(siteA.enb, nearA.cell!!.site.node)
        val nearB = at(8000.0)
        assertEquals(IdSource.RESOLVED, nearB.source)
        assertEquals(siteB.enb, nearB.cell!!.site.node)
        val between = at(4000.0)
        assertEquals(IdSource.AMBIGUOUS, between.source)
        assertEquals(2, between.candidates.size)
    }
}
