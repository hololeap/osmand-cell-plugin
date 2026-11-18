// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.hololeap.cellcoverage.core.model

/**
 * Derives "could a call be placed here" from what Android exposes. The raw
 * inputs are stored next to the result, because the answer is a heuristic:
 *
 *  - Voice registration in service: YES. On 2G/3G that is a circuit-switched
 *    registration, which is a solid yes. On LTE and 5G, Android reports the voice
 *    registration as in service once the phone can fall back to 2G/3G or has
 *    VoLTE; whether IMS (VoLTE) is actually registered is not public API, so a
 *    YES on an LTE-only network is "probably".
 *  - Not in service but emergency calls available: EMERGENCY_ONLY.
 *  - Otherwise NO. With no voice state at all (no permission): UNKNOWN.
 *
 * [voiceAdvertised] is true when any network registration lists the voice
 * service (API 30+); it upgrades an out-of-service voice state to YES, which
 * covers VoLTE-only networks with no 2G/3G fallback.
 */
object CallHeuristic {
    fun derive(voiceInService: Boolean?, emergencyOnly: Boolean, voiceAdvertised: Boolean?): CallCapability =
        when {
            voiceInService == true -> CallCapability.YES
            voiceAdvertised == true -> CallCapability.YES
            emergencyOnly -> CallCapability.EMERGENCY_ONLY
            voiceInService == false -> CallCapability.NO
            else -> CallCapability.UNKNOWN
        }
}
