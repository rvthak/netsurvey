package com.rvthak.netsurvey.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads a [RadioSnapshot] from the OS for the default *data* SIM (SPEC §10.2).
 *
 * All radio reads require [Manifest.permission.ACCESS_FINE_LOCATION]; without it
 * the OS throws or silently blanks identity fields. We guard and report rather
 * than crash.
 *
 * SINR units note: NR `getSsSinr()` is plain dB. LTE `getRssnr()` is documented
 * inconsistently across OEMs (some return tenths of dB). We store the raw int and
 * surface it in the Phase-1 probe so the value can be calibrated against the
 * phone's field-test screen before it is trusted.
 */
class TelephonyReader(private val context: Context) {

    private val baseTm: TelephonyManager =
        context.getSystemService(TelephonyManager::class.java)

    private fun dataTelephonyManager(): TelephonyManager {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            baseTm.createForSubscriptionId(subId)
        } else {
            baseTm
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Force a fresh cell-info update (API 29+), falling back to the cached list. */
    private suspend fun freshCellInfo(tm: TelephonyManager): List<CellInfo> {
        if (!hasLocationPermission()) return emptyList()
        val updated = withTimeoutOrNull(2_000L) {
            suspendCancellableCoroutine<List<CellInfo>?> { cont ->
                try {
                    tm.requestCellInfoUpdate(
                        context.mainExecutor,
                        object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                                if (cont.isActive) cont.resume(cellInfo)
                            }

                            override fun onError(errorCode: Int, detail: Throwable?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (!updated.isNullOrEmpty()) return updated
        // Fall back to the cached snapshot.
        return try {
            @Suppress("DEPRECATION")
            tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    suspend fun snapshot(): RadioSnapshot {
        val now = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        if (!hasLocationPermission()) {
            return RadioSnapshot(
                timestampMs = now,
                carrier = null,
                dataNetworkTypeLabel = "permission denied",
                nsaActive = false,
                serving = null,
                servingSignal = null,
                nrSignal = null,
                servingCells = emptyList(),
                neighbors = emptyList(),
                cellDataQuality = CellDataQuality.UNAVAILABLE,
                nrIdentityUnavailable = false,
                errors = listOf("ACCESS_FINE_LOCATION not granted"),
            )
        }

        val tm = dataTelephonyManager()
        val carrier = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
        val dataType = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val cellInfos = freshCellInfo(tm)
        if (cellInfos.isEmpty()) errors += "no cell info returned"

        // Classify every reported cell by how it's being used right now. CA and 5G
        // NSA mean several cells can be SECONDARY-serving simultaneously, not just
        // one registered cell — getCellConnectionStatus() is what distinguishes a
        // concurrently-used carrier from a merely-visible neighbour.
        val roled = cellInfos.map { it to roleOf(it) }
        val servingCells = roled
            .filter { it.second != CellRole.NEIGHBOR }
            .mapNotNull { (info, role) -> servingCellOf(info)?.copy(role = role) }
        val serving = servingCells.firstOrNull { it.role == CellRole.PRIMARY }
            ?: servingCells.firstOrNull()
        // The CellInfo backing the primary, for its signal reading.
        val primaryInfo = roled.firstOrNull { it.second == CellRole.PRIMARY }?.first
            ?: cellInfos.firstOrNull { it.isRegistered }
        // Headline signal: prefer the primary cell's reading, but fall back to
        // the system SignalStrength object. getAllCellInfo() blanks out when the
        // device's Location master toggle is OFF (even with the permission granted),
        // yet TelephonyManager.signalStrength keeps reporting RSRP — so without this
        // fallback the headline metric silently disappears. (Cell *identity* still
        // needs location on; that's an OS privacy gate, not something we can route
        // around — so the Cells section legitimately stays empty in that case.)
        val servingSignal = primaryInfo?.let { signalOf(it) }?.takeIf { it.rsrp != null }
            ?: primarySignalFromSystem(tm)

        // NR signal from the system SignalStrength object — present in NSA even
        // when the registered (anchor) cell is LTE.
        val nrSignal = nrSignalFromSystem(tm)

        val neighbors = roled
            .filter { it.second == CellRole.NEIGHBOR }
            .mapNotNull { neighborCellOf(it.first) }
            .dedupeNeighbors()

        val nsaActive = (serving?.tech == CellTech.LTE) && nrSignal?.rsrp != null
        // In NSA the phone is registered on the LTE anchor, so the NR cell identity
        // is not exposed by the OS — a missing NR tower id means "hidden", not "no 5G".
        val nrIdentityUnavailable = nsaActive

        return RadioSnapshot(
            timestampMs = now,
            carrier = carrier,
            dataNetworkTypeLabel = networkTypeLabel(dataType, nsaActive),
            nsaActive = nsaActive,
            serving = serving,
            servingCells = servingCells,
            servingSignal = servingSignal,
            nrSignal = nrSignal,
            neighbors = neighbors,
            cellDataQuality = qualityOf(serving, neighbors),
            nrIdentityUnavailable = nrIdentityUnavailable,
            errors = errors,
        )
    }

    // --- parsing helpers -----------------------------------------------------

    /**
     * Map the OS connection status to our [CellRole]. When the modem reports
     * CONNECTION_UNKNOWN (common on older/quirky OEM stacks that don't populate
     * the field) we fall back to the legacy `isRegistered` signal: registered →
     * PRIMARY, otherwise NEIGHBOR. So behaviour degrades to the old single-serving
     * model rather than dropping the cell.
     */
    private fun roleOf(info: CellInfo): CellRole = when (info.cellConnectionStatus) {
        CellInfo.CONNECTION_PRIMARY_SERVING -> CellRole.PRIMARY
        CellInfo.CONNECTION_SECONDARY_SERVING -> CellRole.SECONDARY
        CellInfo.CONNECTION_NONE -> CellRole.NEIGHBOR
        else -> if (info.isRegistered) CellRole.PRIMARY else CellRole.NEIGHBOR
    }

    private fun servingCellOf(info: CellInfo): ServingCell? = when (info) {
        is CellInfoLte -> {
            val id: CellIdentityLte = info.cellIdentity
            val ci = id.ci.unavailableToNull()
            ServingCell(
                tech = CellTech.LTE,
                globalId = ci?.toLong(),
                derivedTowerId = ci?.let { (it shr 8).toLong() }, // eNB = ECI >> 8
                pci = id.pci.unavailableToNull(),
                tac = id.tac.unavailableToNull(),
                earfcn = id.earfcn.unavailableToNull(),
                bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands.toList() else emptyList(),
                mccMnc = id.mccmncOrNull(),
            )
        }

        is CellInfoNr -> {
            val id = info.cellIdentity as? CellIdentityNr
            val nci = id?.nci?.unavailableToNull()
            ServingCell(
                tech = CellTech.NR,
                globalId = nci,
                derivedTowerId = null, // gNB-id split is operator-configured; not derivable honestly
                pci = id?.pci?.unavailableToNull(),
                tac = id?.tac?.unavailableToNull(),
                earfcn = id?.nrarfcn?.unavailableToNull(),
                bands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && id != null) id.bands.toList() else emptyList(),
                mccMnc = id?.let { if (it.mccString != null && it.mncString != null) "${it.mccString}${it.mncString}" else null },
            )
        }

        is CellInfoWcdma -> ServingCell(
            tech = CellTech.WCDMA,
            globalId = info.cellIdentity.cid.unavailableToNull()?.toLong(),
            derivedTowerId = null,
            pci = info.cellIdentity.psc.unavailableToNull(),
            tac = info.cellIdentity.lac.unavailableToNull(),
            earfcn = info.cellIdentity.uarfcn.unavailableToNull(),
            bands = emptyList(),
            mccMnc = info.cellIdentity.mccmncOrNull(),
        )

        is CellInfoGsm -> ServingCell(
            tech = CellTech.GSM,
            globalId = info.cellIdentity.cid.unavailableToNull()?.toLong(),
            derivedTowerId = null,
            pci = null,
            tac = info.cellIdentity.lac.unavailableToNull(),
            earfcn = info.cellIdentity.arfcn.unavailableToNull(),
            bands = emptyList(),
            mccMnc = info.cellIdentity.mccmncOrNull(),
        )

        else -> null
    }

    private fun signalOf(info: CellInfo): SignalReading? = when (info) {
        is CellInfoLte -> info.cellSignalStrength.let { s ->
            SignalReading(CellTech.LTE, s.rsrp.unavailableToNull(), s.rsrq.unavailableToNull(), s.rssnr.unavailableToNull(), s.level)
        }

        is CellInfoNr -> (info.cellSignalStrength as? CellSignalStrengthNr)?.let { s ->
            SignalReading(CellTech.NR, s.ssRsrp.unavailableToNull(), s.ssRsrq.unavailableToNull(), s.ssSinr.unavailableToNull(), s.level)
        }

        is CellInfoWcdma -> info.cellSignalStrength.let { s ->
            SignalReading(CellTech.WCDMA, s.dbm.unavailableToNull(), null, null, s.level)
        }

        is CellInfoGsm -> info.cellSignalStrength.let { s ->
            SignalReading(CellTech.GSM, s.dbm.unavailableToNull(), null, null, s.level)
        }

        else -> null
    }

    /**
     * Headline signal straight from [TelephonyManager.signalStrength] — the most
     * robust source, independent of `getAllCellInfo()` and the Location toggle.
     * Prefers LTE (the serving/anchor radio, and the headline RSRP per SPEC §6),
     * then NR (5G SA), then any other radio with a usable dBm.
     */
    private fun primarySignalFromSystem(tm: TelephonyManager): SignalReading? {
        val ss: SignalStrength = runCatching { tm.signalStrength }.getOrNull() ?: return null
        val strengths = ss.cellSignalStrengths

        strengths.filterIsInstance<CellSignalStrengthLte>()
            .firstOrNull { it.rsrp.unavailableToNull() != null }
            ?.let { return SignalReading(CellTech.LTE, it.rsrp.unavailableToNull(), it.rsrq.unavailableToNull(), it.rssnr.unavailableToNull(), it.level) }

        strengths.filterIsInstance<CellSignalStrengthNr>()
            .firstOrNull { it.ssRsrp.unavailableToNull() != null }
            ?.let { return SignalReading(CellTech.NR, it.ssRsrp.unavailableToNull(), it.ssRsrq.unavailableToNull(), it.ssSinr.unavailableToNull(), it.level) }

        return strengths.firstOrNull { it.dbm.unavailableToNull() != null }?.let {
            val tech = when (it) {
                is android.telephony.CellSignalStrengthWcdma -> CellTech.WCDMA
                is android.telephony.CellSignalStrengthGsm -> CellTech.GSM
                else -> CellTech.UNKNOWN
            }
            SignalReading(tech, it.dbm.unavailableToNull(), null, null, it.level)
        }
    }

    private fun nrSignalFromSystem(tm: TelephonyManager): SignalReading? {
        val ss: SignalStrength = runCatching { tm.signalStrength }.getOrNull() ?: return null
        val nr = ss.cellSignalStrengths.filterIsInstance<CellSignalStrengthNr>().firstOrNull() ?: return null
        val rsrp = nr.ssRsrp.unavailableToNull()
        // Skip phantom NR entries that report no usable RSRP.
        if (rsrp == null) return null
        return SignalReading(CellTech.NR, rsrp, nr.ssRsrq.unavailableToNull(), nr.ssSinr.unavailableToNull(), nr.level)
    }

    private fun neighborCellOf(info: CellInfo): NeighborCell? = when (info) {
        is CellInfoLte -> NeighborCell(
            CellTech.LTE, info.cellIdentity.pci.unavailableToNull(), info.cellIdentity.earfcn.unavailableToNull(),
            info.cellIdentity.ci.unavailableToNull()?.toLong(), info.cellSignalStrength.dbm.unavailableToNull(),
        )

        is CellInfoNr -> {
            val id = info.cellIdentity as? CellIdentityNr
            val sig = (info.cellSignalStrength as? CellSignalStrengthNr)?.ssRsrp?.unavailableToNull()
            NeighborCell(CellTech.NR, id?.pci?.unavailableToNull(), id?.nrarfcn?.unavailableToNull(), id?.nci?.unavailableToNull(), sig)
        }

        is CellInfoWcdma -> NeighborCell(
            CellTech.WCDMA, info.cellIdentity.psc.unavailableToNull(), info.cellIdentity.uarfcn.unavailableToNull(),
            info.cellIdentity.cid.unavailableToNull()?.toLong(), info.cellSignalStrength.dbm.unavailableToNull(),
        )

        is CellInfoGsm -> NeighborCell(
            CellTech.GSM, null, info.cellIdentity.arfcn.unavailableToNull(),
            info.cellIdentity.cid.unavailableToNull()?.toLong(), info.cellSignalStrength.dbm.unavailableToNull(),
        )

        else -> null
    }

    private fun qualityOf(serving: ServingCell?, neighbors: List<NeighborCell>): CellDataQuality = when {
        serving == null -> CellDataQuality.UNAVAILABLE
        serving.globalId != null && neighbors.isNotEmpty() -> CellDataQuality.FULL
        serving.globalId != null -> CellDataQuality.SERVING_ONLY
        serving.pci != null -> CellDataQuality.PCI_ONLY
        else -> CellDataQuality.UNAVAILABLE
    }
}

// --- small extensions --------------------------------------------------------

/** [CellInfo.UNAVAILABLE] (Int.MAX_VALUE) and Long form sentinel → null. */
private fun Int.unavailableToNull(): Int? = if (this == CellInfo.UNAVAILABLE) null else this
private fun Long.unavailableToNull(): Long? = if (this == CellInfo.UNAVAILABLE_LONG) null else this

private fun android.telephony.CellIdentityLte.mccmncOrNull(): String? = mccmncCombine(mccString, mncString)
private fun android.telephony.CellIdentityWcdma.mccmncOrNull(): String? = mccmncCombine(mccString, mncString)
private fun android.telephony.CellIdentityGsm.mccmncOrNull(): String? = mccmncCombine(mccString, mncString)
private fun mccmncCombine(mcc: String?, mnc: String?): String? =
    if (mcc != null && mnc != null) "$mcc$mnc" else null

/** Neighbours are a set keyed by tech+pci+earfcn (SPEC §5), keeping best signal. */
private fun List<NeighborCell>.dedupeNeighbors(): List<NeighborCell> {
    val byKey = LinkedHashMap<String, NeighborCell>()
    for (n in this) {
        val key = "${n.tech}/${n.pci}/${n.earfcn}"
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = n
        } else {
            val best = listOfNotNull(existing.bestSignalDbm, n.bestSignalDbm).maxOrNull()
            byKey[key] = existing.copy(
                bestSignalDbm = best,
                globalId = existing.globalId ?: n.globalId,
            )
        }
    }
    return byKey.values.toList()
}

fun networkTypeLabel(type: Int, nsa: Boolean): String {
    val base = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G (NR)"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA -> "WCDMA/HSPA"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM/EDGE"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "unknown"
        else -> "type $type"
    }
    return if (nsa && type == TelephonyManager.NETWORK_TYPE_LTE) "$base + 5G NSA" else base
}
