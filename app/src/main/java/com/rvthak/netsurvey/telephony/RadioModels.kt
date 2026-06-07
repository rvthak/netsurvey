package com.rvthak.netsurvey.telephony

import kotlinx.serialization.Serializable

/**
 * Telephony data model. Deliberately conservative: every numeric field is
 * nullable so an "unavailable" reading from the OS is represented as `null`
 * rather than a fabricated value (SPEC §1 "keep the data honest").
 */

@Serializable
enum class CellTech { LTE, NR, WCDMA, GSM, CDMA, UNKNOWN }

/** Quality of the cell-identity data we managed to read (SPEC §5). */
@Serializable
enum class CellDataQuality { FULL, SERVING_ONLY, PCI_ONLY, UNAVAILABLE }

/** One radio's signal numbers. dBm values are negative for RSRP/RSSI. */
data class SignalReading(
    val tech: CellTech,
    val rsrp: Int?,   // dBm — LTE RSRP / NR ssRsrp
    val rsrq: Int?,   // dB  — LTE/NR RSRQ
    val sinr: Int?,   // dB  — LTE RSSNR / NR ssSinr (see TelephonyReader notes)
    val level: Int,   // 0..4 coarse bars
)

/** The registered serving cell's identity. */
data class ServingCell(
    val tech: CellTech,
    val globalId: Long?,        // ECI (LTE) / NCI (NR)
    val derivedTowerId: Long?,  // eNodeB id (LTE). null for NR — see note.
    val pci: Int?,
    val tac: Int?,
    val earfcn: Int?,           // EARFCN (LTE) / NRARFCN (NR)
    val bands: List<Int>,
    val mccMnc: String?,
)

/** An observed neighbour (measured candidate, not a guaranteed alternative). */
data class NeighborCell(
    val tech: CellTech,
    val pci: Int?,
    val earfcn: Int?,
    val globalId: Long?,
    val bestSignalDbm: Int?,
)

/** A single ~1 Hz poll of the radio state. */
data class RadioSnapshot(
    val timestampMs: Long,
    val carrier: String?,
    val dataNetworkTypeLabel: String,
    val nsaActive: Boolean,            // 5G NSA: LTE anchor + active NR carrier
    val serving: ServingCell?,
    val servingSignal: SignalReading?, // signal of the serving radio
    val nrSignal: SignalReading?,      // NR signal when present (esp. NSA)
    val neighbors: List<NeighborCell>,
    val cellDataQuality: CellDataQuality,
    val nrIdentityUnavailable: Boolean,
    val errors: List<String> = emptyList(),
) {
    /** The headline signal: prefer the serving radio, fall back to NR (NSA). */
    val headlineSignal: SignalReading?
        get() = servingSignal ?: nrSignal
}
