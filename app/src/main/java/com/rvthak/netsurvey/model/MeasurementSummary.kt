package com.rvthak.netsurvey.model

import com.rvthak.netsurvey.telephony.CellDataQuality
import kotlinx.serialization.Serializable

/**
 * The per-measurement summary (SPEC §6). Pure data class so it can be unit-tested
 * and `@Embedded` directly in the Room `Measurement` entity. Every series headline
 * is nullable: a metric with no usable samples stays `null`, never a fake 0.
 * `@Serializable` so it rides along in the export bundle (SPEC §9).
 */
@Serializable
data class MeasurementSummary(
    val rsrpMedian: Double? = null,
    val rsrpP10: Double? = null,
    val rsrpP90: Double? = null,
    val rsrqMedian: Double? = null,
    val sinrMedian: Double? = null,
    val latencyMedian: Double? = null,
    val latencyP10: Double? = null,
    val latencyP90: Double? = null,
    val jitter: Double? = null,
    val successPct: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val cellDataQuality: CellDataQuality = CellDataQuality.UNAVAILABLE,
    val nrIdentityUnavailable: Boolean = false,
    val distinctNeighborCount: Int = 0,
    /** Radio type changed mid-test (SPEC §10.3) — RSRP is mixed-tech, flagged not hidden. */
    val mixedTech: Boolean = false,
    /** 5G NSA dual connectivity (EN-DC: LTE anchor + active NR carrier) seen at any point. */
    val endcSeen: Boolean = false,
    /** The PRIMARY serving cell changed during the run — aggregate spans a handover. */
    val handoverOccurred: Boolean = false,
    /** Distinct SECONDARY (Carrier-Aggregation / NSA) serving cells used during the run. */
    val secondaryCellCount: Int = 0,
    /** Largest single-cell time-share — low values mean the run was split across cells. */
    val dominantCellSharePct: Double? = null,
) {
    /** Headline value for a colour-driving metric, or null if not measured. */
    fun value(metric: Metric): Double? = when (metric) {
        Metric.RSRP -> rsrpMedian
        Metric.LATENCY -> latencyMedian
        Metric.JITTER -> jitter
        Metric.SUCCESS -> successPct
        Metric.DOWNLOAD -> downloadMbps
        Metric.UPLOAD -> uploadMbps
    }
}
