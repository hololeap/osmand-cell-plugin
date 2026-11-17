// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/** Whether a voice call could be placed, as far as public Android APIs can tell. */
enum class CallCapability {
    /** Voice service is registered. */
    YES,

    /** Only emergency calls. */
    EMERGENCY_ONLY,

    /** Out of service or radio off. */
    NO,

    /** Not recorded (imported data, or the permission is missing). */
    UNKNOWN,
}

/**
 * What the phone said about its registration when a sample was taken. The raw
 * fields are kept next to the derived [calls] value, because on LTE-only and 5G
 * networks voice depends on IMS registration, which Android exposes only partly.
 */
data class ServiceInfo(
    /** `ServiceState.getState()`: 0 in service, 1 out of service, 2 emergency only, 3 power off. */
    val voiceState: Int? = null,
    /** `TelephonyManager.getDataNetworkType()`. */
    val dataNetworkType: Int? = null,
    /** `TelephonyDisplayInfo.getOverrideNetworkType()`, the "5G" icon logic. */
    val displayOverride: Int? = null,
    val roaming: Boolean? = null,
    /** `NetworkRegistrationInfo.getAvailableServices()` over all registrations, e.g. "1,2,3". */
    val availableServices: String? = null,
    /** `NetworkCapabilities.getLinkDownstreamBandwidthKbps()`: the modem's own estimate. */
    val linkDownKbps: Int? = null,
    val calls: CallCapability = CallCapability.UNKNOWN,
)

/**
 * One observation of one cell at one position. A saved GPS fix produces one
 * sample for the registered cell and optionally one per neighbor.
 */
data class Sample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val altitudeM: Double? = null,
    val cell: CellKey,
    val registered: Boolean = true,
    /** PCI for LTE and NR, PSC for WCDMA. */
    val pci: Int? = null,
    /** EARFCN, NR-ARFCN, UARFCN or ARFCN. */
    val channel: Int? = null,
    /** Band numbers as reported, comma separated. */
    val bands: String? = null,
    /**
     * The signal level the estimator and the heatmap use, in dBm: RSRP for LTE,
     * SS-RSRP for NR, RSCP for WCDMA, RSSI for GSM.
     */
    val dbm: Int? = null,
    /** RSRQ (LTE), SS-RSRQ (NR) or Ec/No (WCDMA), in dB. */
    val rsrq: Int? = null,
    /** RS-SNR (LTE) or SS-SINR (NR), in dB. */
    val sinr: Int? = null,
    /** Timing advance in the radio's own units; see `TimingAdvance`. */
    val timingAdvance: Int? = null,
    val service: ServiceInfo? = null,
    /** The SIM slot's subscription id, when known. */
    val subscriptionId: Int? = null,
)
