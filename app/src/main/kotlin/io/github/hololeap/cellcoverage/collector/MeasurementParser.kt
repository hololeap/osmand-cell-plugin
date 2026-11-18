/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Ported from Tower Collector (zamojski/TowerCollector at e31296b,
// collector/parsers/MeasurementParser.java and Api17PlusMeasurementParser.java,
// collector/validators/LocationValidator.java, ConditionsValidator.java and
// SystemTimeValidator.java). Changes: Kotlin; no EventBus and no database
// access, the caller stores what parse() returns; neighbours without a cell id
// are kept (as physical ids) instead of being dropped as invalid; the location
// age is measured from the fix's own elapsed-realtime clock.

package io.github.hololeap.cellcoverage.collector

import android.location.Location
import android.os.SystemClock
import android.telephony.CellInfo

enum class ParseResult {
    SAVED,
    ACCURACY_NOT_ACHIEVED,
    DISTANCE_NOT_ACHIEVED,
    LOCATION_TOO_OLD,
    NO_NETWORK_SIGNAL,
}

data class Parsed(
    val result: ParseResult,
    /** The time to store: system time, or GPS time when the system clock is off. */
    val timeMs: Long = 0,
    val cells: List<ConvertedCell> = emptyList(),
)

/**
 * Decides whether a location fix plus a cell list makes a new measurement, and
 * converts the cells. One parser per SIM, because the distance rule compares
 * against the last measurement of the same SIM.
 */
class MeasurementParser(
    private val converter: CellInfoConverter = CellInfoConverter(),
    /** Fixes less accurate than this are ignored, in metres. */
    private val minAccuracyM: Float = 40f,
    /** A new measurement only after moving this far, unless the serving cell changed. */
    private val minDistanceM: Float = 50f,
    private val collectNeighbours: Boolean = true,
) {
    private var lastLocation: Location? = null
    private var lastRegisteredKeys: Set<String> = emptySet()

    fun parse(location: Location, cells: List<CellInfo>, plmn: Pair<Int, Int>?): Parsed {
        if (!isValid(location)) return Parsed(ParseResult.ACCURACY_NOT_ACHIEVED)
        if (!isUpToDate(location)) return Parsed(ParseResult.LOCATION_TOO_OLD)

        val time = fixTimestamp(System.currentTimeMillis(), location.time)

        // Convert, dropping invalid cells and duplicates (some phones list a cell twice).
        val seen = HashSet<String>()
        val converted = cells.mapNotNull { converter.convert(it, plmn) }
            .filter { c -> c.dedupKey?.let(seen::add) == true }
            .filter { collectNeighbours || it.registered }

        val registeredKeys = converted.filter { it.registered }.mapNotNull { it.dedupKey }.toSet()
        val last = lastLocation
        if (last != null && !isMinDistanceSatisfied(last, location)) {
            val changed = registeredKeys.any { it !in lastRegisteredKeys }
            if (!changed) return Parsed(ParseResult.DISTANCE_NOT_ACHIEVED)
        }
        if (converted.isEmpty()) return Parsed(ParseResult.NO_NETWORK_SIGNAL)

        lastLocation = location
        lastRegisteredKeys = registeredKeys
        return Parsed(ParseResult.SAVED, time, converted)
    }

    private fun isValid(l: Location): Boolean =
        l.latitude in -90.0..90.0 && l.latitude != 0.0 &&
            l.longitude in -180.0..180.0 && l.longitude != 0.0 &&
            l.hasAccuracy() && l.accuracy <= minAccuracyM

    /** The fix must be at most five minutes old. */
    private fun isUpToDate(l: Location): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return ageMs <= NO_LOCATION_TIME_DIFF_MS
    }

    /** Approximate match with 10% tolerance, as in Tower Collector. */
    private fun isMinDistanceSatisfied(previous: Location, current: Location): Boolean =
        1.1f * previous.distanceTo(current) >= minDistanceM

    /**
     * Uses GPS time when the system clock is more than two days off. Tower
     * Collector also refuses GPS times older than the app's build date, for
     * phones hit by the 2019 GPS week rollover; here a GPS time before 2020 is
     * treated the same way.
     */
    private fun fixTimestamp(system: Long, gps: Long): Long =
        if (kotlin.math.abs(system - gps) <= TWO_DAYS_MS || gps < JAN_2020_MS) system else gps

    companion object {
        const val NO_LOCATION_TIME_DIFF_MS = 5 * 60 * 1000L
        const val TWO_DAYS_MS = 2 * 24 * 60 * 60 * 1000L
        const val JAN_2020_MS = 1_577_836_800_000L

        /** Speeds above this are GPS glitches; Tower Collector stores 0 instead. */
        const val MAX_REASONABLE_SPEED = 500f
    }
}
