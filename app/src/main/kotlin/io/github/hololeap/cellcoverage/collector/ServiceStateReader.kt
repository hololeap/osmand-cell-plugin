// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import io.github.hololeap.cellcoverage.core.model.CallHeuristic
import io.github.hololeap.cellcoverage.core.model.ServiceInfo
import java.util.concurrent.Executor

/**
 * Reads the registration and service state that goes with each sample: voice
 * state, data network type, the "5G" display override, roaming, the services
 * each registration offers, and the modem's bandwidth estimate. Fields Android
 * refuses (missing READ_PHONE_STATE, or too old an API level) stay null.
 */
class ServiceStateReader(private val context: Context, private val executor: Executor) {

    @Volatile private var displayOverride: Int? = null
    @Volatile private var cellularDownKbps: Int? = null
    private val callbacks = ArrayList<Pair<TelephonyManager, TelephonyCallback>>()
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val hasPhoneState: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun start(managers: Collection<TelephonyManager>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasPhoneState) {
            for (tm in managers) {
                val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                        displayOverride = info.overrideNetworkType
                    }
                }
                try {
                    tm.registerTelephonyCallback(executor, cb)
                    callbacks += tm to cb
                } catch (e: SecurityException) {
                    // Leave the override unknown.
                }
            }
        }
        // Track the cellular network's capabilities for the bandwidth estimate.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                cellularDownKbps = caps.linkDownstreamBandwidthKbps
            }

            override fun onLost(network: Network) {
                cellularDownKbps = null
            }
        }
        cm.registerNetworkCallback(request, cb)
        netCallback = cb
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callbacks.forEach { (tm, cb) -> tm.unregisterTelephonyCallback(cb) }
        }
        callbacks.clear()
        netCallback?.let { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        netCallback = null
    }

    fun read(tm: TelephonyManager): ServiceInfo {
        var voiceState: Int? = null
        var dataType: Int? = null
        var roaming: Boolean? = null
        var services: String? = null
        var voiceAdvertised: Boolean? = null
        var emergencyOnly = false
        if (hasPhoneState) {
            try {
                val ss: ServiceState? = tm.serviceState
                if (ss != null) {
                    voiceState = ss.state
                    roaming = ss.roaming
                    emergencyOnly = ss.state == ServiceState.STATE_EMERGENCY_ONLY
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val all = ss.networkRegistrationInfoList.flatMap { it.availableServices }.toSortedSet()
                        services = all.joinToString(",")
                        voiceAdvertised = NetworkRegistrationInfo.SERVICE_TYPE_VOICE in all
                        if (NetworkRegistrationInfo.SERVICE_TYPE_EMERGENCY in all) emergencyOnly = emergencyOnly || voiceState != ServiceState.STATE_IN_SERVICE
                    }
                }
                dataType = tm.dataNetworkType
            } catch (e: SecurityException) {
                // Leave unknown.
            }
        }
        return ServiceInfo(
            voiceState = voiceState,
            dataNetworkType = dataType,
            displayOverride = displayOverride,
            roaming = roaming,
            availableServices = services,
            linkDownKbps = cellularDownKbps,
            calls = CallHeuristic.derive(
                voiceInService = voiceState?.let { it == ServiceState.STATE_IN_SERVICE },
                emergencyOnly = emergencyOnly,
                voiceAdvertised = voiceAdvertised,
            ),
        )
    }
}
