/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

// Ported from Tower Collector (zamojski/TowerCollector at e31296b,
// collector/validators/specific/*CellValidator.java). Changes: Kotlin; the
// ranges are split into "full identity" and "physical id only", so that a
// neighbour reported without a cell id is kept for the neighbour resolver
// instead of being dropped; the API 17 WCDMA-as-GSM workaround and CDMA are
// left out (minSdk is 29, and CDMA networks are gone).

package io.github.hololeap.cellcoverage.collector

/** Value ranges from 3GPP, as Tower Collector checks them. */
object CellValidators {
    fun mcc(v: Int?) = v != null && v in 100..999
    fun mnc(v: Int?) = v != null && v in 0..999

    fun lteIdentity(mcc: Int?, mnc: Int?, tac: Int?, ci: Int?, pci: Int?) =
        mcc(mcc) && mnc(mnc) && tac != null && tac in 1..65535 && ci != null && ci in 1..268_435_455 && ltePci(pci)
    fun ltePci(pci: Int?) = pci != null && pci in 0..503
    fun earfcn(v: Int?) = v != null && v in 0..262_143

    fun nrIdentity(mcc: Int?, mnc: Int?, tac: Int?, nci: Long?, pci: Int?) =
        mcc(mcc) && mnc(mnc) && tac != null && tac in 1..16_777_215 && nci != null && nci in 1L..68_719_476_735L && nrPci(pci)
    fun nrPci(pci: Int?) = pci != null && pci in 0..1007
    fun nrarfcn(v: Int?) = v != null && v in 0..3_279_165

    fun wcdmaIdentity(mcc: Int?, mnc: Int?, lac: Int?, cid: Int?, psc: Int?) =
        mcc(mcc) && mnc(mnc) && lac != null && lac in 1..65535 && cid != null && cid in 1..268_435_455 && psc(psc)
    fun psc(v: Int?) = v != null && v in 0..511
    fun uarfcn(v: Int?) = v != null && v in 0..16383

    fun gsmIdentity(mcc: Int?, mnc: Int?, lac: Int?, cid: Int?) =
        mcc(mcc) && mnc(mnc) && lac != null && lac in 1..65535 && cid != null && cid in 1..65535
    fun bsic(v: Int?) = v != null && v in 0..63
    fun arfcn(v: Int?) = v != null && v in 0..1023

    fun tdscdmaIdentity(mcc: Int?, mnc: Int?, lac: Int?, cid: Int?, cpid: Int?) =
        mcc(mcc) && mnc(mnc) && lac != null && lac in 1..65535 && cid != null && cid in 1..268_435_455 && cpid(cpid)
    fun cpid(v: Int?) = v != null && v in 0..127
}
