// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Milestone 0 placeholder: shows the build and which OsmAnd builds are installed,
 * which is enough to confirm that the APK installs and can see OsmAnd.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            textSize = 16f
            text = statusText()
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(text)
        })
    }

    private fun statusText(): String = buildString {
        appendLine("Cell Coverage ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine()
        appendLine("OsmAnd builds found:")
        for ((pkg, label) in OSMAND_PACKAGES) {
            appendLine("  $label ($pkg): ${installedVersion(pkg) ?: "not installed"}")
        }
        appendLine()
        appendLine("Nothing is collected yet. This build only checks that the app installs and can see OsmAnd.")
    }

    private fun installedVersion(pkg: String): String? =
        try {
            packageManager.getPackageInfo(pkg, 0).versionName ?: "installed"
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private companion object {
        /** In order of preference when more than one is installed. */
        val OSMAND_PACKAGES = listOf(
            "net.osmand.plus" to "OsmAnd~ (F-Droid) / OsmAnd+",
            "net.osmand" to "OsmAnd (free)",
            "net.osmand.dev" to "OsmAnd nightly",
        )
    }
}
