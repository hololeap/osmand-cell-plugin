/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Ported from Tower Collector (zamojski/TowerCollector at e31296b,
// collector/converters/CellIdentityConverter.java and CellSignalConverter.java).
// Changes: Kotlin; one pass produces this app's Sample (or, for a neighbour
// without a cell id, a NeighbourReading) instead of Tower Collector's Cell;
// the signal kept is the radio-specific one (RSRP, SS-RSRP, RSCP, RSSI);
// bands are read where Android has them; CDMA is not supported.

package io.github.hololeap.cellcoverage.collector

import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import io.github.hololeap.cellcoverage.core.model.CellKey
import io.github.hololeap.cellcoverage.core.model.PhysicalId
import io.github.hololeap.cellcoverage.core.model.RadioType

/**
 * One cell from `getAllCellInfo()`, before it is tied to a position. Either
 * [cell] is set (the phone named the cell) or only [physical] is.
 */
data class ConvertedCell(
    val radio: RadioType,
    val registered: Boolean,
    val cell: CellKey?,
    val physical: PhysicalId?,
    val pci: Int?,
    val channel: Int?,
    val bands: String?,
    val dbm: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val timingAdvance: Int?,
) {
    /** Duplicate detection: the cell id when known, otherwise the physical id. */
    val dedupKey: String? get() = cell?.toId() ?: physical?.toId()
}

class CellInfoConverter {

    /**
     * Converts [info]. [plmn] is the serving network (MCC, MNC) of the SIM the
     * list came from, used for neighbours that do not report their own. Returns
     * null for cells that are neither identifiable nor have a usable physical id.
     */
    fun convert(info: CellInfo, plmn: Pair<Int, Int>?): ConvertedCell? =
        when {
            info is CellInfoLte -> lte(info, plmn)
            info is CellInfoNr -> nr(info, plmn)
            info is CellInfoWcdma -> wcdma(info, plmn)
            info is CellInfoGsm -> gsm(info, plmn)
            info is CellInfoTdscdma -> tdscdma(info, plmn)
            else -> null // CDMA and anything newer
        }

    private fun lte(info: CellInfoLte, plmn: Pair<Int, Int>?): ConvertedCell? {
        val id: CellIdentityLte = info.cellIdentity
        val s = info.cellSignalStrength
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val pci = id.pci.av()
        val ch = id.earfcn.av()
        val ci = id.ci.av()
        val tac = id.tac.av()
        val full = CellValidators.lteIdentity(mcc, mnc, tac, ci, pci)
        val phys = if (CellValidators.ltePci(pci) && CellValidators.earfcn(ch)) physical(RadioType.LTE, mcc, mnc, plmn, ch!!, pci!!) else null
        if (!full && phys == null) return null
        return ConvertedCell(
            radio = RadioType.LTE,
            registered = info.isRegistered,
            cell = if (full) CellKey(RadioType.LTE, mcc!!, mnc!!, tac!!, ci!!.toLong()) else null,
            physical = phys,
            pci = pci,
            channel = ch,
            bands = bands { id.bands },
            dbm = fixLteRsrpOnSamsung(s.rsrp.av()).dbm(),
            rsrq = s.rsrq.av(),
            sinr = s.rssnr.av(),
            timingAdvance = s.timingAdvance.av(),
        )
    }

    private fun nr(info: CellInfoNr, plmn: Pair<Int, Int>?): ConvertedCell? {
        val id = info.cellIdentity as CellIdentityNr
        val s = info.cellSignalStrength as CellSignalStrengthNr
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val pci = id.pci.av()
        val ch = id.nrarfcn.av()
        val nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG }
        val tac = id.tac.av()
        val full = CellValidators.nrIdentity(mcc, mnc, tac, nci, pci)
        val phys = if (CellValidators.nrPci(pci) && CellValidators.nrarfcn(ch)) physical(RadioType.NR, mcc, mnc, plmn, ch!!, pci!!) else null
        if (!full && phys == null) return null
        return ConvertedCell(
            radio = RadioType.NR,
            registered = info.isRegistered,
            cell = if (full) CellKey(RadioType.NR, mcc!!, mnc!!, tac!!, nci!!) else null,
            physical = phys,
            pci = pci,
            channel = ch,
            bands = bands { id.bands },
            dbm = (s.ssRsrp.av() ?: s.csiRsrp.av()).dbm(),
            rsrq = s.ssRsrq.av() ?: s.csiRsrq.av(),
            sinr = s.ssSinr.av() ?: s.csiSinr.av(),
            timingAdvance = null,
        )
    }

    private fun wcdma(info: CellInfoWcdma, plmn: Pair<Int, Int>?): ConvertedCell? {
        val id: CellIdentityWcdma = info.cellIdentity
        val s = info.cellSignalStrength
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val psc = id.psc.av()
        val ch = id.uarfcn.av()
        val cid = id.cid.av()
        val lac = id.lac.av()
        val full = CellValidators.wcdmaIdentity(mcc, mnc, lac, cid, psc)
        val phys = if (CellValidators.psc(psc) && CellValidators.uarfcn(ch)) physical(RadioType.WCDMA, mcc, mnc, plmn, ch!!, psc!!) else null
        if (!full && phys == null) return null
        return ConvertedCell(
            radio = RadioType.WCDMA,
            registered = info.isRegistered,
            cell = if (full) CellKey(RadioType.WCDMA, mcc!!, mnc!!, lac!!, cid!!.toLong()) else null,
            physical = phys,
            pci = psc,
            channel = ch,
            bands = null,
            // getDbm() is the RSCP on WCDMA.
            dbm = s.dbm.av().dbm(),
            rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.ecNo.av() else null,
            sinr = null,
            timingAdvance = null,
        )
    }

    private fun gsm(info: CellInfoGsm, plmn: Pair<Int, Int>?): ConvertedCell? {
        val id: CellIdentityGsm = info.cellIdentity
        val s = info.cellSignalStrength
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val bsic = id.bsic.av()
        val ch = id.arfcn.av()
        val cid = id.cid.av()
        val lac = id.lac.av()
        val full = CellValidators.gsmIdentity(mcc, mnc, lac, cid)
        val phys = if (CellValidators.bsic(bsic) && CellValidators.arfcn(ch)) physical(RadioType.GSM, mcc, mnc, plmn, ch!!, bsic!!) else null
        if (!full && phys == null) return null
        return ConvertedCell(
            radio = RadioType.GSM,
            registered = info.isRegistered,
            cell = if (full) CellKey(RadioType.GSM, mcc!!, mnc!!, lac!!, cid!!.toLong()) else null,
            physical = phys,
            pci = bsic,
            channel = ch,
            bands = null,
            dbm = ((if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) s.rssi.av() else null) ?: s.dbm.av()).dbm(),
            rsrq = null,
            sinr = null,
            timingAdvance = s.timingAdvance.av(),
        )
    }

    private fun tdscdma(info: CellInfoTdscdma, plmn: Pair<Int, Int>?): ConvertedCell? {
        val id: CellIdentityTdscdma = info.cellIdentity
        val s = info.cellSignalStrength
        val mcc = id.mccString?.toIntOrNull()
        val mnc = id.mncString?.toIntOrNull()
        val cpid = id.cpid.av()
        val ch = id.uarfcn.av()
        val cid = id.cid.av()
        val lac = id.lac.av()
        val full = CellValidators.tdscdmaIdentity(mcc, mnc, lac, cid, cpid)
        val phys = if (CellValidators.cpid(cpid) && CellValidators.uarfcn(ch)) physical(RadioType.TDSCDMA, mcc, mnc, plmn, ch!!, cpid!!) else null
        if (!full && phys == null) return null
        return ConvertedCell(
            radio = RadioType.TDSCDMA,
            registered = info.isRegistered,
            cell = if (full) CellKey(RadioType.TDSCDMA, mcc!!, mnc!!, lac!!, cid!!.toLong()) else null,
            physical = phys,
            pci = cpid,
            channel = ch,
            bands = null,
            dbm = s.rscp.av().dbm(),
            rsrq = null,
            sinr = null,
            timingAdvance = null,
        )
    }

    private fun physical(radio: RadioType, mcc: Int?, mnc: Int?, plmn: Pair<Int, Int>?, ch: Int, pci: Int): PhysicalId? {
        val m = mcc?.takeIf { CellValidators.mcc(it) } ?: plmn?.first ?: return null
        val n = mnc?.takeIf { CellValidators.mnc(it) } ?: plmn?.second ?: return null
        return PhysicalId(radio, m, n, ch, pci)
    }

    private inline fun bands(get: () -> IntArray): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) get().takeIf { it.isNotEmpty() }?.joinToString(",") else null

    /** Samsung phones have reported RSRP as a positive number ten times too large. */
    private fun fixLteRsrpOnSamsung(rsrp: Int?): Int? {
        if (rsrp == null || rsrp < -1 || !"samsung".equals(Build.MANUFACTURER, ignoreCase = true)) return rsrp
        return (rsrp / -10).takeIf { it in -140..-40 }
    }

    private fun Int.av(): Int? = takeIf { it != CellInfo.UNAVAILABLE }
    private fun Int?.dbm(): Int? = this?.takeIf { it in -160..-20 }
}
