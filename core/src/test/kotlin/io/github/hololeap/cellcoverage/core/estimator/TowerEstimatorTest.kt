// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.estimator

import io.github.hololeap.cellcoverage.core.geo.PointM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Synthetic three-sector sites (see [SyntheticSite]). Each scenario runs ten
 * seeds and asserts on the 80th-percentile error, so one unlucky draw does not
 * fail the build and one lucky draw does not pass it.
 *
 * The bounds are what the estimator achieves today, with some slack, not what
 * one would wish for: with 8 dB of correlated shadowing and a steep exponent,
 * errors near a kilometre are normal unless the route surrounds the site.
 */
class TowerEstimatorTest {

    private val est = TowerEstimator()
    private val seeds = 1..10

    private data class Run(val error: Double, val e: Estimate.Located)

    private fun runs(
        route: List<PointM>, exponent: Double, shadow: Double,
        withTa: Boolean = false, neighbors: Boolean = true, estimator: TowerEstimator = est,
    ): List<Run> = seeds.map { seed ->
        val site = SyntheticSite(exponent = exponent, shadowDb = shadow, seed = seed, neighbors = neighbors)
        val bins = site.bins(site.drive(route, withTa = withTa))
        val e = assertIs<Estimate.Located>(estimator.estimateSite(bins.all.toList()))
        Run(site.distanceTo(e.position), e)
    }

    private fun List<Run>.p80(): Double = map { it.error }.sorted()[(size * 8 + 9) / 10 - 1]

    @Test
    fun gentleTerrainIsAccurate() {
        for ((name, route) in listOf("loop" to SyntheticSite.loop(), "L" to SyntheticSite.lRoute())) {
            val r = runs(route, 2.7, 4.0)
            assertTrue(r.p80() < 150, "$name, n=2.7, 4 dB: p80 error ${r.p80()} m")
            assertTrue(r.count { it.e.ambiguous } <= 1, "$name, n=2.7, 4 dB: flagged ambiguous")
        }
    }

    @Test
    fun harshTerrainStaysWithinAKilometreOrTwo() {
        val loop = runs(SyntheticSite.loop(), 3.5, 8.0)
        assertTrue(loop.p80() < 1500, "loop, n=3.5, 8 dB: p80 error ${loop.p80()} m")
        val l = runs(SyntheticSite.lRoute(), 3.5, 8.0)
        assertTrue(l.p80() < 2500, "L, n=3.5, 8 dB: p80 error ${l.p80()} m")
    }

    @Test
    fun errorRadiusIsNotWildlyOverconfident() {
        // In the harsh case at least half the true positions fall inside the radius.
        val r = runs(SyntheticSite.loop(), 3.5, 8.0)
        val inside = r.count { it.error <= it.e.errorRadiusM }
        assertTrue(inside >= 5, "only $inside of 10 true positions inside the error radius")
    }

    @Test
    fun straightRoadIsAmbiguous() {
        val r = runs(SyntheticSite.straightRoad(), 3.5, 8.0)
        val flagged = r.count { it.e.ambiguous }
        assertTrue(flagged >= 7, "only $flagged of 10 straight-road runs were flagged ambiguous")
    }

    @Test
    fun timingAdvanceTightensTheResult() {
        val route = SyntheticSite.lRoute()
        val without = runs(route, 3.5, 8.0)
        val with = runs(route, 3.5, 8.0, withTa = true)
        assertTrue(with.p80() < without.p80(), "p80 error with TA ${with.p80()} m, without ${without.p80()} m")
        val rWith = with.map { it.e.errorRadiusM }.average()
        val rWithout = without.map { it.e.errorRadiusM }.average()
        assertTrue(rWith < rWithout, "mean error radius with TA $rWith m, without $rWithout m")
    }

    @Test
    fun duplicateSamplesInOneSpotDoNotMoveTheEstimate() {
        val site = SyntheticSite(seed = 3)
        val samples = site.drive(SyntheticSite.lRoute())
        val before = assertIs<Estimate.Located>(est.estimateSite(site.bins(samples).all.toList()))
        // Park for an hour: 1200 more readings of the first sample, same spot, same level.
        val parked = samples.first()
        val dupes = (1..1200).map { parked.copy(timeMs = parked.timeMs + it * 3000L) }
        val after = assertIs<Estimate.Located>(est.estimateSite(site.bins(samples + dupes).all.toList()))
        assertEquals(before.position.lat, after.position.lat, 1e-12)
        assertEquals(before.position.lon, after.position.lon, 1e-12)
        assertEquals(before.bins, after.bins)
    }

    @Test
    fun tooLittleDataPublishesNothing() {
        val site = SyntheticSite()
        val short = site.drive(listOf(PointM(0.0, 800.0), PointM(150.0, 800.0)))
        assertIs<Estimate.Insufficient>(est.estimateSite(site.bins(short).all.toList()))
    }

    @Test
    fun recomputePolicy() {
        assertTrue(TowerEstimator.shouldRecompute(0, 5))
        assertFalse(TowerEstimator.shouldRecompute(100, 109))
        assertTrue(TowerEstimator.shouldRecompute(100, 110))
    }

    /**
     * Not an assertion: prints the model variants side by side, the evidence for
     * the defaults. It takes about eight minutes, so it only runs when asked:
     *
     *     ESTIMATOR_COMPARE=1 ./gradlew :core:test --tests '*compareModels*' -i
     */
    @Test
    fun compareModels() {
        if (System.getenv("ESTIMATOR_COMPARE") == null) return
        val variants = listOf(
            "isotropic" to TowerEstimator(EstimatorConfig(useSectorPattern = false, sharedIntercept = false)),
            "sector" to TowerEstimator(EstimatorConfig(useSectorPattern = true, sharedIntercept = false)),
            "sector+shared" to TowerEstimator(),
        )
        val routes = listOf(
            "loop" to SyntheticSite.loop(),
            "L" to SyntheticSite.lRoute(),
            "straight" to SyntheticSite.straightRoad(),
        )
        println("p80 error in metres over 10 seeds (and true positions inside the error radius)")
        println("route     n    dB  neighbours  " + variants.joinToString("") { "%-17s".format(it.first) })
        for ((rn, route) in routes) for ((n, sh) in listOf(2.7 to 4.0, 2.7 to 8.0, 3.5 to 4.0, 3.5 to 8.0)) for (nb in listOf(true, false)) {
            val cells = variants.joinToString("") { (_, e) ->
                val r = runs(route, n, sh, neighbors = nb, estimator = e)
                "%6.0f (%2d/10)    ".format(r.p80(), r.count { it.error <= it.e.errorRadiusM })
            }
            println("%-9s %.1f  %2.0f  %-10s  %s".format(rn, n, sh, if (nb) "yes" else "no", cells))
        }
    }
}
