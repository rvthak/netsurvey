package com.rvthak.netsurvey.model

import kotlinx.serialization.Serializable

/**
 * A green→red colour band for one metric (SPEC §8): [greatAt] is the value
 * considered best, [poorAt] the worst. Linear interpolation between them gives a
 * pin's colour; values past either end clamp.
 */
@Serializable
data class ThresholdBand(val greatAt: Double, val poorAt: Double)

@Serializable
data class AppSettings(
    val primaryMetricKey: String = Metric.RSRP.key,
    val thresholds: Map<String, ThresholdBand> = defaultThresholds(),
    val defaultDataCap: DataCapConfig = DataCapConfig.DEFAULT,
) {
    val primaryMetric: Metric get() = Metric.fromKey(primaryMetricKey)

    fun band(metric: Metric): ThresholdBand =
        thresholds[metric.key] ?: defaultThresholds().getValue(metric.key)

    companion object {
        val DEFAULT = AppSettings()
    }
}

/** Industry-ish defaults (SPEC §8). Editable in Settings. */
fun defaultThresholds(): Map<String, ThresholdBand> = mapOf(
    Metric.RSRP.key to ThresholdBand(greatAt = -80.0, poorAt = -110.0),
    Metric.LATENCY.key to ThresholdBand(greatAt = 30.0, poorAt = 100.0),
    Metric.JITTER.key to ThresholdBand(greatAt = 5.0, poorAt = 40.0),
    Metric.SUCCESS.key to ThresholdBand(greatAt = 100.0, poorAt = 95.0),
    Metric.DOWNLOAD.key to ThresholdBand(greatAt = 100.0, poorAt = 10.0),
    Metric.UPLOAD.key to ThresholdBand(greatAt = 50.0, poorAt = 2.0),
)
