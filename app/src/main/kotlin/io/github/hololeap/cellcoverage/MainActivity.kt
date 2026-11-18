// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.hololeap.cellcoverage.collector.CollectorService
import io.github.hololeap.cellcoverage.collector.CollectorStatus
import io.github.hololeap.cellcoverage.core.csv.TowerCollectorCsv
import io.github.hololeap.cellcoverage.storage.CoverageDb
import java.text.DateFormat
import java.util.Date

/**
 * The status screen: permissions, start and stop, what was last collected, how
 * many neighbour readings were matched to a cell, and the one-off tools
 * (Tower Collector import, re-matching neighbours). Plain framework views; the
 * real screens come with later milestones.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toolOutput: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { setTextIsSelectable(true); textSize = 15f }
        toolOutput = TextView(this).apply { setTextIsSelectable(true); textSize = 14f; setPadding(0, pad, 0, 0) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(button("Grant permissions") { requestPermissions(neededPermissions(), REQ_PERMISSIONS) })
            addView(button("Start collecting") { CollectorService.start(this@MainActivity) })
            addView(button("Stop collecting") { CollectorService.stop(this@MainActivity) })
            addView(status)
            addView(button("Import Tower Collector CSV…") { pickCsv() })
            addView(button("Re-match neighbour cells") { reResolve() })
            addView(toolOutput)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun neededPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val s = CollectorStatus.current
        status.text = buildString {
            appendLine()
            appendLine("Cell Coverage ${BuildConfig.VERSION_NAME} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Permissions")
            appendLine("  precise location: ${yn(granted(Manifest.permission.ACCESS_FINE_LOCATION))} (required)")
            appendLine("  phone state: ${yn(granted(Manifest.permission.READ_PHONE_STATE))} (for call availability and dual SIM)")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appendLine("  notifications: ${yn(granted(Manifest.permission.POST_NOTIFICATIONS))} (to see that it is running)")
            }
            appendLine()
            appendLine("Collector: ${if (s.running) "running" else "stopped"}")
            appendLine("  ${s.lastResult}")
            s.lastFixAccuracy?.let { appendLine("  last GPS accuracy: ${"%.0f".format(it)} m") }
            s.lastServing?.let { appendLine("  serving: $it") }
            s.lastCalls?.let { appendLine("  calls possible: $it") }
            s.lastSavedAt?.let { appendLine("  last saved: ${DateFormat.getTimeInstance().format(Date(it))}") }
            s.counts?.let { c ->
                appendLine()
                appendLine("Stored: ${c.samples} samples, ${c.cells} cells, ${c.bins} bins")
                val by = c.bySource
                appendLine("  cell id reported by the phone: ${by["REPORTED"] ?: 0}")
                appendLine("  neighbours matched by PCI: ${by["RESOLVED"] ?: 0} confidently, ${by["AMBIGUOUS"] ?: 0} ambiguous, ${by["UNRESOLVED"] ?: 0} unmatched")
            }
            s.dbPath?.let { appendLine(); appendLine("Database: $it") }
            appendLine()
            appendLine("OsmAnd builds found:")
            for ((pkg, label) in OSMAND_PACKAGES) appendLine("  $label: ${installedVersion(pkg) ?: "not installed"}")
        }
    }

    private fun pickCsv() {
        if (CollectorStatus.current.running) {
            toolOutput.text = "Stop collecting before importing."
            return
        }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            REQ_CSV,
        )
    }

    @Deprecated("Framework Activity API; fine for this screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_CSV && resultCode == RESULT_OK && uri != null) importCsv(uri)
    }

    private fun importCsv(uri: Uri) {
        toolOutput.text = "Importing…"
        background {
            val db = CoverageDb.open(this)
            try {
                var added = 0
                val stats = db.transaction {
                    contentResolver.openInputStream(uri)!!.bufferedReader().use { r ->
                        TowerCollectorCsv.parse(r) { s -> if (db.insertSample(s, "towercollector")) added++ }
                    }
                }
                val counts = db.counts()
                CollectorStatus.update { it.copy(counts = counts, dbPath = db.file.path) }
                buildString {
                    appendLine("Imported $added new samples from ${stats.rows} rows (${stats.imported - added} already present, ${stats.skipped} skipped).")
                    stats.problems.forEach { appendLine("  $it") }
                }
            } finally {
                db.close()
            }
        }
    }

    private fun reResolve() {
        if (CollectorStatus.current.running) {
            toolOutput.text = "Stop collecting before re-matching."
            return
        }
        toolOutput.text = "Re-matching neighbour cells…"
        background {
            val db = CoverageDb.open(this)
            try {
                val r = db.reResolveAll()
                CollectorStatus.update { it.copy(counts = db.counts(), dbPath = db.file.path) }
                "Re-matched: ${r.entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }.ifEmpty { "nothing to do" }}. Bins rebuilt."
            } finally {
                db.close()
            }
        }
    }

    private fun background(work: () -> String) {
        Thread {
            val msg = try {
                work()
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            ui.post { toolOutput.text = msg }
        }.start()
    }

    private fun installedVersion(pkg: String): String? =
        try {
            packageManager.getPackageInfo(pkg, 0).versionName ?: "installed"
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun yn(b: Boolean) = if (b) "granted" else "not granted"

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_CSV = 2

        /** In order of preference when more than one is installed. */
        val OSMAND_PACKAGES = listOf(
            "net.osmand.plus" to "OsmAnd~ (F-Droid) / OsmAnd+",
            "net.osmand" to "OsmAnd (free)",
            "net.osmand.dev" to "OsmAnd nightly",
        )
    }
}
