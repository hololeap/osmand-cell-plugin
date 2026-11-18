/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// The shape of this service follows Tower Collector's CollectorService.java
// (zamojski/TowerCollector at e31296b): a foreground service of type location,
// GPS updates from LocationManager, a TelephonyManager per active SIM, and a
// wake lock. Changes: Kotlin, much reduced; each GPS fix triggers
// requestCellInfoUpdate() on every SIM (minSdk 29 has it), which replaces
// Tower Collector's periodically re-registered phone-state listener; a partial
// wake lock only; storage goes to this app's GeoPackage.

package io.github.hololeap.cellcoverage.collector

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.telephony.CellInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.hololeap.cellcoverage.MainActivity
import io.github.hololeap.cellcoverage.R
import io.github.hololeap.cellcoverage.core.model.IdSource
import io.github.hololeap.cellcoverage.core.model.NeighbourReading
import io.github.hololeap.cellcoverage.core.model.Sample
import io.github.hololeap.cellcoverage.core.model.ServiceInfo as CellServiceInfo
import io.github.hololeap.cellcoverage.storage.CoverageDb
import java.util.concurrent.Executor

class CollectorService : Service() {

    /** One SIM: its TelephonyManager and its own parser (the distance rule is per SIM). */
    private class Sim(val subId: Int?, val tm: TelephonyManager, val parser: MeasurementParser)

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var executor: Executor
    private var db: CoverageDb? = null
    private var sims: List<Sim> = emptyList()
    private var serviceState: ServiceStateReader? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastCellRequestMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderDisabled(provider: String) {
            CollectorStatus.update { it.copy(lastResult = "GPS is switched off") }
        }
        override fun onProviderEnabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("collector").apply { start() }
        handler = Handler(worker.looper)
        executor = Executor { handler.post(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (CollectorStatus.current.running) return START_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CollectorStatus.update { it.copy(lastResult = "Location permission missing") }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CellCoverage:collector")
            .apply { setReferenceCounted(false); acquire() }
        CollectorStatus.update { it.copy(running = true, lastResult = "Waiting for a GPS fix") }

        handler.post {
            try {
                db = CoverageDb.open(this)
            } catch (e: Exception) {
                CollectorStatus.update { it.copy(lastResult = "Cannot open the database: ${e.message}") }
                stopSelf()
                return@post
            }
            sims = createSims()
            serviceState = ServiceStateReader(this, executor).also { it.start(sims.map { s -> s.tm }) }
            publishCounts()
        }
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, worker.looper)
        } catch (e: SecurityException) {
            CollectorStatus.update { it.copy(lastResult = "Location permission missing") }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        handler.post {
            serviceState?.stop()
            db?.close()
            db = null
        }
        worker.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        CollectorStatus.update { it.copy(running = false, lastResult = "Stopped") }
        super.onDestroy()
    }

    private fun createSims(): List<Sim> {
        val default = getSystemService(TelephonyManager::class.java)
        val ids = try {
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?.map { it.subscriptionId }
        } catch (e: SecurityException) {
            null // No READ_PHONE_STATE: fall back to the default SIM only.
        }
        if (ids.isNullOrEmpty()) return listOf(Sim(null, default, MeasurementParser()))
        return ids.map { Sim(it, default.createForSubscriptionId(it), MeasurementParser()) }
    }

    /** Runs on the worker thread. */
    private fun onFix(location: Location) {
        CollectorStatus.update { it.copy(lastFixAccuracy = if (location.hasAccuracy()) location.accuracy else null) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCellRequestMs < MIN_CELL_REQUEST_INTERVAL_MS) return
        lastCellRequestMs = now
        for (sim in sims) {
            try {
                sim.tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cells: MutableList<CellInfo>) = process(sim, location, cells)
                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // Some modems refuse fresh scans; the cached list is better than nothing.
                        process(sim, location, cachedCells(sim))
                    }
                })
            } catch (e: SecurityException) {
                CollectorStatus.update { it.copy(lastResult = "Permission missing for cell info") }
            }
        }
    }

    private fun cachedCells(sim: Sim): List<CellInfo> =
        try {
            sim.tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    /** Runs on the worker thread. */
    private fun process(sim: Sim, location: Location, cells: List<CellInfo>) {
        val db = db ?: return
        val parsed = sim.parser.parse(location, cells, plmnOf(sim.tm))
        if (parsed.result != ParseResult.SAVED) {
            CollectorStatus.update { it.copy(lastResult = parsed.result.describe()) }
            return
        }
        val service = serviceState?.read(sim.tm)
        val speed = if (location.hasSpeed()) location.speed.takeIf { it <= MeasurementParser.MAX_REASONABLE_SPEED } ?: 0f else null
        var resolvedNow = 0
        db.transaction {
            for (c in parsed.cells) {
                val cell = c.cell
                if (cell != null) {
                    db.insertSample(
                        Sample(
                            timeMs = parsed.timeMs,
                            lat = location.latitude, lon = location.longitude,
                            accuracyM = location.accuracy,
                            speedMps = speed,
                            bearingDeg = if (location.hasBearing()) location.bearing else null,
                            altitudeM = if (location.hasAltitude()) location.altitude else null,
                            cell = cell, registered = c.registered,
                            pci = c.pci, channel = c.channel, bands = c.bands,
                            dbm = c.dbm, rsrq = c.rsrq, sinr = c.sinr, timingAdvance = c.timingAdvance,
                            service = if (c.registered) service else null,
                            subscriptionId = sim.subId,
                        ),
                        SOURCE,
                    )
                } else {
                    val phys = c.physical ?: continue
                    val r = db.insertNeighbour(
                        NeighbourReading(
                            parsed.timeMs, location.latitude, location.longitude, location.accuracy,
                            phys, c.dbm, c.rsrq, c.sinr, sim.subId,
                        ),
                        SOURCE,
                    )
                    if (r == IdSource.RESOLVED) resolvedNow++
                }
            }
        }
        val serving = parsed.cells.firstOrNull { it.registered }
        CollectorStatus.update {
            it.copy(
                lastResult = "Saved ${parsed.cells.size} cells",
                lastServing = serving?.let { s -> "${s.radio.name} ${s.cell?.toId() ?: s.physical?.toId()} ${s.dbm ?: "?"} dBm" },
                lastCalls = service?.calls?.name,
                lastSavedAt = System.currentTimeMillis(),
            )
        }
        publishCounts()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(CollectorStatus.current.summary()))
    }

    private fun publishCounts() {
        val c = db?.counts() ?: return
        CollectorStatus.update { it.copy(counts = c, dbPath = db?.file?.path) }
    }

    /** The SIM's registered network as (MCC, MNC), for neighbours that report none. */
    private fun plmnOf(tm: TelephonyManager): Pair<Int, Int>? {
        val op = tm.networkOperator ?: return null
        if (op.length < 5) return null
        val mcc = op.substring(0, 3).toIntOrNull() ?: return null
        val mnc = op.substring(3).toIntOrNull() ?: return null
        return mcc to mnc
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Collecting", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CollectorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.github.hololeap.cellcoverage.STOP"
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1
        private const val GPS_INTERVAL_MS = 1000L
        private const val MIN_CELL_REQUEST_INTERVAL_MS = 2000L
        private const val SOURCE = "collector"

        fun start(context: Context) = context.startForegroundService(Intent(context, CollectorService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, CollectorService::class.java).setAction(ACTION_STOP))
    }
}

private fun ParseResult.describe(): String = when (this) {
    ParseResult.SAVED -> "Saved"
    ParseResult.ACCURACY_NOT_ACHIEVED -> "Waiting for a fix of 40 m or better"
    ParseResult.DISTANCE_NOT_ACHIEVED -> "Waiting to move 50 m"
    ParseResult.LOCATION_TOO_OLD -> "GPS fix too old"
    ParseResult.NO_NETWORK_SIGNAL -> "No usable cells reported"
}
