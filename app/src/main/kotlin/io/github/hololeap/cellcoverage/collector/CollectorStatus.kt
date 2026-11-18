// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.collector

import io.github.hololeap.cellcoverage.storage.CoverageDb

/** What the status screen shows. Written by the service, read by the activity. */
data class CollectorState(
    val running: Boolean = false,
    val lastResult: String = "Not started",
    val lastFixAccuracy: Float? = null,
    val lastServing: String? = null,
    val lastCalls: String? = null,
    val lastSavedAt: Long? = null,
    val counts: CoverageDb.Counts? = null,
    val dbPath: String? = null,
) {
    fun summary(): String = buildString {
        append(counts?.samples ?: 0).append(" samples")
        lastServing?.let { append(" · ").append(it) }
    }
}

object CollectorStatus {
    @Volatile var current = CollectorState()
        private set

    @Synchronized
    fun update(f: (CollectorState) -> CollectorState) {
        current = f(current)
    }
}
