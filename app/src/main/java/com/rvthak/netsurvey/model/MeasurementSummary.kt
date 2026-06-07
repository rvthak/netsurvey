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
