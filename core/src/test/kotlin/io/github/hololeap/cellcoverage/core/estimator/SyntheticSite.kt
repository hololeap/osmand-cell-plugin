// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.estimator

import io.github.hololeap.cellcoverage.core.bins.CellBins
import io.github.hololeap.cellcoverage.core.geo.Geo
import io.github.hololeap.cellcoverage.core.geo.LatLon
import io.github.hololeap.cellcoverage.core.geo.LocalProjection
import io.github.hololeap.cellcoverage.core.geo.PointM
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.RadioType
import io.github.hololeap.cellcoverage.core.model.Sample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A three-sector LTE site with a known position, and a drive past it.
 *
 * Shadowing is spatially correlated (a first-order autoregressive process along
 * the route with a 50 m decorrelation distance), because real shadowing is: a
 * building blocks the signal for a stretch, not for one sample. That makes the
 * test harder than independent noise would.
 */
class SyntheticSite(
    val site: PointM = PointM(0.0, 0.0),
    val exponent: Double = 3.5,
    val shadowDb: Double = 8.0,
    val azimuths: List<Double> = listOf(30.0, 150.0, 270.0),
    val powerDbm: Double = 0.0,
    val seed: Int = 1,
    /** Below this a neighbor sector is not reported. */
    val floorDbm: Double = -125.0,
    /** Report neighbor sectors at all (real phones often give neighbors no cell id). */
    val neighbors: Boolean = true,
) {
    val origin = LatLon(47.0, 11.0)
    val proj = LocalProjection(origin)
    val enb = 22183L
    val truth: LatLon get() = proj.toLatLon(site.x, site.y)

    fun cellOf(sector: Int) = CellKey(RadioType.LTE, 232, 1, 4711, (enb shl 8) + sector + 1)

    private fun pattern(bearing: Double, az: Double): Double {
        val r = Geo.angleDiffDeg(bearing, az) / 65.0
        return min(12 * r * r, 20.0)
    }

    /** Noise-free signal of [sector] at [p]. */
    fun meanDbm(p: PointM, sector: Int): Double {
        val dx = p.x - site.x
        val dy = p.y - site.y
        val d = sqrt(dx * dx + dy * dy + 30.0 * 30.0)
        return powerDbm - 10 * exponent * log10(d) - pattern(Geo.bearingDeg(dx, dy), azimuths[sector])
    }

    fun distanceTo(p: LatLon): Double = Geo.haversineM(p.lat, p.lon, truth.lat, truth.lon)

    /**
     * Drives [route] (local metres) at one sample every [stepM] and returns the
     * samples: the serving sector, plus every other sector above [floorDbm].
     */
    fun drive(route: List<PointM>, stepM: Double = 30.0, withTa: Boolean = false): List<Sample> {
        val rnd = Random(seed)
        val rho = exp(-stepM / 50.0)
        val shadow = DoubleArray(azimuths.size) { gauss(rnd) * shadowDb }
        val out = ArrayList<Sample>()
        var t = 1_700_000_000_000L
        for (p in densify(route, stepM)) {
            for (k in shadow.indices) shadow[k] = rho * shadow[k] + sqrt(1 - rho * rho) * gauss(rnd) * shadowDb
            val levels = azimuths.indices.map { meanDbm(p, it) + shadow[it] }
            val serving = levels.indices.maxBy { levels[it] }
            val ll = proj.toLatLon(p.x, p.y)
            val dist = hypot(p.x - site.x, p.y - site.y)
            for (k in levels.indices) {
                if (k != serving && (!neighbors || levels[k] < floorDbm)) continue
                // Timing advance exists only on the serving cell, quantised to 78 m.
                val ta = if (withTa && k == serving) {
                    ((dist + gauss(rnd) * 60) / 78.12).toInt().coerceAtLeast(0)
                } else null
                out += Sample(
                    timeMs = t,
                    lat = ll.lat,
                    lon = ll.lon,
                    accuracyM = 5f,
                    cell = cellOf(k),
                    registered = k == serving,
                    dbm = round(levels[k]).toInt(),
                    timingAdvance = ta,
                )
            }
            t += 3000
        }
        return out
    }

    fun bins(samples: List<Sample>): CellBins = CellBins().also { b -> samples.forEach { b.add(it) } }

    companion object {
        fun gauss(r: Random): Double {
            // Box-Muller.
            val u = 1.0 - r.nextDouble()
            val v = r.nextDouble()
            return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * v)
        }

        fun densify(route: List<PointM>, stepM: Double): List<PointM> {
            val out = ArrayList<PointM>()
            for (i in 1 until route.size) {
                val a = route[i - 1]
                val b = route[i]
                val len = hypot(b.x - a.x, b.y - a.y)
                val steps = (len / stepM).toInt().coerceAtLeast(1)
                for (s in 0 until steps) {
                    val f = s.toDouble() / steps
                    out += PointM(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y))
                }
            }
            out += route.last()
            return out
        }

        /** A straight east-west road passing [offset] m north of the site. */
        fun straightRoad(offset: Double = 800.0, halfLength: Double = 3000.0) =
            listOf(PointM(-halfLength, offset), PointM(halfLength, offset))

        /** Along that road, then turning south past the site's east side. */
        fun lRoute() = listOf(PointM(-3000.0, 800.0), PointM(1200.0, 800.0), PointM(1200.0, -3000.0))

        /** A ring road of [radius] m around a point [offsetM] east of the site. */
        fun loop(radius: Double = 1500.0, offsetM: Double = 400.0) =
            (0..72).map { i ->
                val a = 2 * PI * i / 72
                PointM(offsetM + radius * cos(a), radius * sin(a))
            }
    }
}
