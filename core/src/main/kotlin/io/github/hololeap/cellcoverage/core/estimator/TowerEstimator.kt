// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.estimator

import io.github.hololeap.cellcoverage.core.bins.CellBin
import io.github.hololeap.cellcoverage.core.geo.Geo
import io.github.hololeap.cellcoverage.core.geo.LatLon
import io.github.hololeap.cellcoverage.core.geo.LocalProjection
import io.github.hololeap.cellcoverage.core.geo.PointM
import io.github.hololeap.cellcoverage.core.model.TimingAdvance
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One input observation: a bin of one sector, in local metres. */
data class BinObservation(
    val sector: Int,
    val p: PointM,
    val dbm: Double,
    /** One-way distance implied by the bin's median timing advance, if any. */
    val taDistanceM: Double? = null,
)

data class EstimatorConfig(
    /** Antenna height stand-in that keeps log(d) finite under the mast. */
    val heightM: Double = 30.0,
    /** Shadowing standard deviation assumed by the likelihood, dB. */
    val sigmaDb: Double = 8.0,
    /** Huber threshold, in units of sigma. */
    val huberK: Double = 1.5,
    /**
     * How many neighbouring bins share one independent shadowing value. Shadowing
     * is correlated over tens to hundreds of metres, so 30 m bins are not
     * independent, and treating them as independent makes the posterior far too
     * narrow. The score is divided by this before it becomes a weight.
     */
    val correlationBins: Double = 3.0,
    val taSigmaM: Double = 150.0,
    val minExponent: Double = 2.0,
    val maxExponent: Double = 5.0,
    /** Use the 3GPP TR 36.814 horizontal sector pattern (false: isotropic). */
    val useSectorPattern: Boolean = true,
    /**
     * One intercept for all sectors of a site instead of one each. Sectors of a
     * site normally transmit at the same power, and sharing the intercept ties
     * their levels together across sector boundaries. Without that tie, a site
     * seen only through its serving cells (the usual case: Android rarely gives
     * neighbours a cell id) is badly under-determined.
     */
    val sharedIntercept: Boolean = true,
    val sectorBeamwidthDeg: Double = 65.0,
    val sectorMaxAttenuationDb: Double = 20.0,
    val gridSize: Int = 48,
    val refinements: Int = 2,
    val refineFactor: Double = 4.0,
    /** A second mode holding more than this share of the weight flags the result. */
    val ambiguityShare: Double = 0.20,
    val minBins: Int = 8,
    val minExtentM: Double = 300.0,
    val minPaddingM: Double = 2000.0,
)

sealed interface Estimate {
    /** Too little data to publish anything. */
    data class Insufficient(val bins: Int, val extentM: Double) : Estimate

    data class Located(
        val position: LatLon,
        val local: PointM,
        /** Posterior covariance in m^2: east-east, east-north, north-north. */
        val covEE: Double,
        val covEN: Double,
        val covNN: Double,
        /** Two standard deviations along the posterior's long axis, m. */
        val errorRadiusM: Double,
        val exponent: Double,
        /** True when the exponent was clamped to its allowed range. */
        val exponentAtLimit: Boolean,
        /** Intercept per sector index, dBm at 1 m (a fit parameter, not a real power). */
        val powerDbm: Map<Int, Double>,
        /** Azimuth per sector index, degrees from north; only with the sector pattern. */
        val azimuthDeg: Map<Int, Double>,
        val residualRmsDb: Double,
        val bins: Int,
        /** How much of the horizon the bins cover, seen from the estimate, degrees. */
        val angularSpanDeg: Double,
        val ambiguous: Boolean,
        /** Where the second mode is, when there is a separate one. */
        val alternative: LatLon?,
        val alternativeShare: Double,
    ) : Estimate
}

/**
 * Estimates a cell site's position from signal strength versus position.
 *
 * Model, for a candidate site position x and bin i of sector k:
 *
 *     d_i = sqrt(|p_i - x|^2 + h^2)
 *     s_i = P_k - 10 n log10(d_i) - G_i + noise,   noise ~ N(0, sigma^2)
 *
 * G_i is the sector pattern's attenuation (zero in the isotropic model). For a
 * fixed x the model is linear in n and P_k, so they are solved in closed form
 * and x is found by a grid search over the profile likelihood. See the README
 * for the full write-up.
 */
class TowerEstimator(private val config: EstimatorConfig = EstimatorConfig()) {

    /** Convenience entry point: bins of one site, sector = cell's index within the site. */
    fun estimateSite(bins: List<CellBin>): Estimate {
        if (bins.isEmpty()) return Estimate.Insufficient(0, 0.0)
        val origin = LatLon(bins.map { it.meanLat }.average(), bins.map { it.meanLon }.average())
        val proj = LocalProjection(origin)
        val sectors = bins.map { it.cell }.distinct().withIndex().associate { (i, c) -> c to i }
        val obs = bins.map { b ->
            BinObservation(
                sector = sectors.getValue(b.cell),
                p = proj.toLocal(b.meanLat, b.meanLon),
                dbm = b.meanDbm,
                taDistanceM = b.taMedian?.let { TimingAdvance.distanceM(b.cell.radio, it) },
            )
        }
        return estimate(obs, proj)
    }

    fun estimate(obs: List<BinObservation>, proj: LocalProjection): Estimate {
        val minX = obs.minOf { it.p.x }
        val maxX = obs.maxOf { it.p.x }
        val minY = obs.minOf { it.p.y }
        val maxY = obs.maxOf { it.p.y }
        val diag = hypot(maxX - minX, maxY - minY)
        if (obs.size < config.minBins || diag < config.minExtentM) {
            return Estimate.Insufficient(obs.size, diag)
        }
        val fit = Fit(obs)

        // Coarse grid over the bins' bounding box, grown on every side.
        val pad = max(config.minPaddingM, diag)
        val coarse = Grid(
            cx = (minX + maxX) / 2, cy = (minY + maxY) / 2,
            halfW = (maxX - minX) / 2 + pad, halfH = (maxY - minY) / 2 + pad,
            n = config.gridSize, fit = fit,
        )
        val modes = coarse.modes()
        val best = modes[0]
        val total = modes.sumOf { it.mass }
        val second = modes.drop(1).maxByOrNull { it.mass }
        val secondShare = if (second == null || total <= 0.0) 0.0 else second.mass / total

        // Refine around the two best modes, then keep whichever fits better.
        val refined = listOfNotNull(best, second).map { refine(coarse, it, fit) }
        val winner = refined.minBy { it.bestScore }
        val loser = refined.firstOrNull { it !== winner }

        val m = winner.stats
        val pos = PointM(m.meanX, m.meanY)
        val solved = fit.solve(pos.x, pos.y)
        val spanDeg = angularSpan(obs, pos)
        val l1 = largestEigen(m.cxx, m.cxy, m.cyy)
        return Estimate.Located(
            position = proj.toLatLon(pos.x, pos.y),
            local = pos,
            covEE = m.cxx, covEN = m.cxy, covNN = m.cyy,
            errorRadiusM = max(2 * sqrt(l1), winner.cellSize),
            exponent = solved.n,
            exponentAtLimit = solved.atLimit,
            powerDbm = solved.power,
            azimuthDeg = if (config.useSectorPattern) solved.azimuth else emptyMap(),
            residualRmsDb = solved.rms,
            bins = obs.size,
            angularSpanDeg = spanDeg,
            ambiguous = secondShare > config.ambiguityShare,
            alternative = loser?.let { proj.toLatLon(it.stats.meanX, it.stats.meanY) },
            alternativeShare = secondShare,
        )
    }

    // ---------------------------------------------------------------- fitting

    private data class Solved(
        val n: Double,
        val atLimit: Boolean,
        val power: Map<Int, Double>,
        val azimuth: Map<Int, Double>,
        val score: Double,
        val rms: Double,
    )

    /** The profile likelihood for one fixed set of observations. */
    private inner class Fit(val obs: List<BinObservation>) {
        private val sectors = obs.map { it.sector }.distinct().sorted()
        private val sectorIndex = sectors.withIndex().associate { (i, s) -> s to i }
        private val sec = IntArray(obs.size) { sectorIndex.getValue(obs[it].sector) }

        /** Intercept group of each bin: its sector, or 0 for all when the intercept is shared. */
        private val grp = if (config.sharedIntercept) IntArray(obs.size) else sec
        private val k = sectors.size
        private val h2 = config.heightM * config.heightM

        // Scratch arrays, reused across candidates (the estimator is single-threaded).
        private val d = DoubleArray(obs.size)
        private val u = DoubleArray(obs.size)
        private val y = DoubleArray(obs.size)
        private val bearing = DoubleArray(obs.size)
        private val sumU = DoubleArray(k)
        private val sumY = DoubleArray(k)
        private val cnt = IntArray(k)
        private val az = DoubleArray(k)
        private val azSsr = DoubleArray(AZ_STEPS)

        fun score(x: Double, yy: Double): Double = solve(x, yy, full = false).score

        fun solve(x: Double, yy: Double): Solved = solve(x, yy, full = true)

        private fun solve(x: Double, yy: Double, full: Boolean): Solved {
            val m = obs.size
            sumU.fill(0.0); sumY.fill(0.0); cnt.fill(0)
            for (i in 0 until m) {
                val dx = obs[i].p.x - x
                val dy = obs[i].p.y - yy
                d[i] = sqrt(dx * dx + dy * dy + h2)
                u[i] = -10.0 * log10(d[i])
                if (config.useSectorPattern) bearing[i] = atan2(dx, dy)
            }
            if (config.useSectorPattern) fitAzimuths(m)
            for (i in 0 until m) {
                y[i] = obs[i].dbm + gain(i, az[sec[i]])
                sumU[grp[i]] += u[i]
                sumY[grp[i]] += y[i]
                cnt[grp[i]]++
            }
            var sxy = 0.0
            var sxx = 0.0
            for (i in 0 until m) {
                val s = grp[i]
                val du = u[i] - sumU[s] / cnt[s]
                sxy += du * (y[i] - sumY[s] / cnt[s])
                sxx += du * du
            }
            val raw = if (sxx > 0) sxy / sxx else config.minExponent
            val n = raw.coerceIn(config.minExponent, config.maxExponent)
            var score = 0.0
            var ss = 0.0
            for (i in 0 until m) {
                val s = grp[i]
                val p = sumY[s] / cnt[s] - n * sumU[s] / cnt[s]
                val r = y[i] - p - n * u[i]
                ss += r * r
                score += huber(r / config.sigmaDb)
                val ta = obs[i].taDistanceM
                if (ta != null) {
                    val z = (d[i] - ta) / config.taSigmaM
                    score += z * z
                }
            }
            if (!full) return Solved(n, false, emptyMap(), emptyMap(), score, 0.0)
            return Solved(
                n = n,
                atLimit = raw != n,
                power = sectors.withIndex().associate { (j, s) ->
                    val g = if (config.sharedIntercept) 0 else j
                    s to sumY[g] / cnt[g] - n * sumU[g] / cnt[g]
                },
                azimuth = sectors.withIndex().associate { (j, s) ->
                    s to Geo.toDegrees(az[j]).let { if (it < 0) it + 360 else it }
                },
                score = score,
                rms = sqrt(ss / m),
            )
        }

        /** Pattern attenuation of bin [i] for a sector pointing at [azRad], dB. */
        private fun gain(i: Int, azRad: Double): Double {
            if (!config.useSectorPattern) return 0.0
            val diff = Geo.angleDiffDeg(Geo.toDegrees(bearing[i]), Geo.toDegrees(azRad))
            val r = diff / config.sectorBeamwidthDeg
            return min(12.0 * r * r, config.sectorMaxAttenuationDb)
        }

        /**
         * Chooses each sector's azimuth for the current candidate. The brief
         * suggested the circular mean of the bearings to the sector's bins; that
         * fails whenever a sector is also heard behind its back as a neighbour
         * (common: the bins then surround the site and the mean is noise). So the
         * azimuth is fitted instead: with the exponent held at a typical 3.2, each
         * sector's azimuth is scanned in 10 degree steps for the least squared
         * residual (its intercept re-solved in closed form at each step), then
         * refined with a parabola through the best step and its neighbours. The
         * exponent and intercepts are then solved jointly as usual.
         */
        private fun fitAzimuths(m: Int) {
            val n0 = 3.2
            for (j in 0 until k) {
                var bestA = 0
                for (a in 0 until AZ_STEPS) {
                    val azRad = 2 * Math.PI * a / AZ_STEPS
                    var sum = 0.0
                    var sum2 = 0.0
                    var c = 0
                    for (i in 0 until m) {
                        if (sec[i] != j) continue
                        val v = obs[i].dbm + gain(i, azRad) - n0 * u[i]
                        sum += v
                        sum2 += v * v
                        c++
                    }
                    azSsr[a] = sum2 - sum * sum / c
                    if (azSsr[a] < azSsr[bestA]) bestA = a
                }
                val l = azSsr[(bestA + AZ_STEPS - 1) % AZ_STEPS]
                val c0 = azSsr[bestA]
                val r = azSsr[(bestA + 1) % AZ_STEPS]
                val denom = l - 2 * c0 + r
                val offset = if (denom > 0) (0.5 * (l - r) / denom).coerceIn(-0.5, 0.5) else 0.0
                az[j] = 2 * Math.PI * (bestA + offset) / AZ_STEPS
            }
        }

        /** Huber's loss, scaled so that it equals z^2 in the middle (a chi-square term). */
        private fun huber(z: Double): Double {
            val a = abs(z)
            val c = config.huberK
            return if (a <= c) z * z else 2 * c * a - c * c
        }
    }

    // ---------------------------------------------------------------- grids

    private class Stats(
        val meanX: Double, val meanY: Double,
        val cxx: Double, val cxy: Double, val cyy: Double,
    )

    private class Mode(val i: Int, val j: Int, val x: Double, val y: Double, val mass: Double)

    private inner class Grid(
        val cx: Double, val cy: Double, val halfW: Double, val halfH: Double,
        val n: Int, fit: Fit,
    ) {
        val dx = 2 * halfW / n
        val dy = 2 * halfH / n
        val score = DoubleArray(n * n)
        val weight = DoubleArray(n * n)
        val bestScore: Double

        init {
            var best = Double.POSITIVE_INFINITY
            for (j in 0 until n) for (i in 0 until n) {
                val s = fit.score(x(i), y(j))
                score[j * n + i] = s
                if (s < best) best = s
            }
            bestScore = best
            // Weights relative to the best cell, so they never underflow to all-zero.
            for (c in score.indices) weight[c] = exp(-(score[c] - best) / (2 * config.correlationBins))
        }

        fun x(i: Int) = cx - halfW + (i + 0.5) * dx
        fun y(j: Int) = cy - halfH + (j + 0.5) * dy
        val cellSize: Double get() = max(dx, dy)

        fun stats(): Stats {
            var w = 0.0; var mx = 0.0; var my = 0.0
            for (j in 0 until n) for (i in 0 until n) {
                val v = weight[j * n + i]
                w += v; mx += v * x(i); my += v * y(j)
            }
            mx /= w; my /= w
            var xx = 0.0; var xy = 0.0; var yy = 0.0
            for (j in 0 until n) for (i in 0 until n) {
                val v = weight[j * n + i]
                val ex = x(i) - mx
                val ey = y(j) - my
                xx += v * ex * ex; xy += v * ex * ey; yy += v * ey * ey
            }
            // Add the variance of a uniform cell so a one-cell spike is not zero.
            return Stats(mx, my, xx / w + dx * dx / 12, xy / w, yy / w + dy * dy / 12)
        }

        /**
         * Separate modes, best first. Every cell climbs to a local maximum of the
         * weight; the cells that reach the same maximum form its basin, and the
         * basin's weight is the mode's mass. Maxima within three cells of a
         * heavier one are merged into it, so noise ripples do not count as modes.
         */
        fun modes(): List<Mode> {
            val peakOf = IntArray(n * n) { -1 }
            fun climb(start: Int): Int {
                var c = start
                val path = ArrayList<Int>()
                while (true) {
                    if (peakOf[c] >= 0) { val p = peakOf[c]; path.forEach { peakOf[it] = p }; return p }
                    path += c
                    val ci = c % n
                    val cj = c / n
                    var next = c
                    for (dj in -1..1) for (di in -1..1) {
                        val ni = ci + di
                        val nj = cj + dj
                        if (ni !in 0 until n || nj !in 0 until n) continue
                        val nc = nj * n + ni
                        if (score[nc] < score[next]) next = nc
                    }
                    if (next == c) { path.forEach { peakOf[it] = c }; return c }
                    c = next
                }
            }
            val mass = HashMap<Int, Double>()
            for (c in 0 until n * n) {
                val p = climb(c)
                mass[p] = (mass[p] ?: 0.0) + weight[c]
            }
            val peaks = mass.keys.sortedBy { score[it] }
            val merged = ArrayList<Pair<Int, Double>>()
            for (p in peaks) {
                val target = merged.indexOfFirst { (q, _) ->
                    abs(q % n - p % n) <= 3 && abs(q / n - p / n) <= 3
                }
                if (target >= 0) merged[target] = merged[target].first to merged[target].second + mass.getValue(p)
                else merged += p to mass.getValue(p)
            }
            return merged.map { (p, m) -> Mode(p % n, p / n, x(p % n), y(p / n), m) }
                .sortedByDescending { it.mass }
        }
    }

    private class Refined(val stats: Stats, val bestScore: Double, val cellSize: Double)

    /**
     * Zooms in on a mode: each step centres a grid [EstimatorConfig.refineFactor]
     * times smaller on the best cell so far. A step is skipped when the posterior
     * is already wider than the smaller grid could hold, because the smaller grid
     * would then cut off its tails and understate the error.
     */
    private fun refine(coarse: Grid, mode: Mode, fit: Fit): Refined {
        // Start from a window around the mode only, so the other mode cannot leak in.
        var grid = Grid(
            mode.x, mode.y,
            coarse.halfW / config.refineFactor, coarse.halfH / config.refineFactor,
            config.gridSize, fit,
        )
        var stats = grid.stats()
        repeat(config.refinements - 1) {
            val nextHalfW = grid.halfW / config.refineFactor
            val nextHalfH = grid.halfH / config.refineFactor
            val sd = sqrt(largestEigen(stats.cxx, stats.cxy, stats.cyy))
            if (3 * sd > min(nextHalfW, nextHalfH)) return Refined(stats, grid.bestScore, grid.cellSize)
            val bi = grid.score.indices.minBy { grid.score[it] }
            grid = Grid(grid.x(bi % grid.n), grid.y(bi / grid.n), nextHalfW, nextHalfH, config.gridSize, fit)
            stats = grid.stats()
        }
        return Refined(stats, grid.bestScore, grid.cellSize)
    }

    private fun largestEigen(a: Double, b: Double, c: Double): Double {
        val tr = a + c
        val det = a * c - b * b
        return tr / 2 + sqrt(max(0.0, tr * tr / 4 - det))
    }

    /** 360 minus the widest empty gap between bin bearings, seen from [from]. */
    private fun angularSpan(obs: List<BinObservation>, from: PointM): Double {
        val b = obs.map { Geo.bearingDeg(it.p.x - from.x, it.p.y - from.y) }.sorted()
        if (b.size < 2) return 0.0
        var gap = 360.0 - (b.last() - b.first())
        for (i in 1 until b.size) gap = max(gap, b[i] - b[i - 1])
        return 360.0 - gap
    }

    companion object {
        private const val AZ_STEPS = 36

        /** Whether a site's estimate is due: it has gained about 10% more bins. */
        fun shouldRecompute(binsAtLastEstimate: Int, binsNow: Int): Boolean =
            binsAtLastEstimate == 0 || binsNow * 10L >= binsAtLastEstimate * 11L
    }
}
