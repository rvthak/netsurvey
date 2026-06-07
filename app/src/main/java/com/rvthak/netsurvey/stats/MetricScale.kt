package com.rvthak.netsurvey.stats

import com.rvthak.netsurvey.model.ThresholdBand

/**
 * Maps a metric value onto a 0..1 **severity** fraction using its [ThresholdBand]
 * (SPEC §8): 0 = at/past the "great" end, 1 = at/past the "poor" end. The band's
 * own orientation (`greatAt` vs `poorAt`) already encodes whether higher or lower
 * is better, so a single formula serves every metric — RSRP's great end is the
 * larger number, latency's the smaller, and both land at 0. Values past either end
 * clamp. Returns `null` when there's no value to place (keeps "not measured"
 * distinct from "measured and bad").
 */
object MetricScale {

    fun severity(value: Double?, band: ThresholdBand): Double? {
        if (value == null) return null
        val span = band.poorAt - band.greatAt
        if (span == 0.0) return 0.0 // degenerate band → treat everything as "great"
        return ((value - band.greatAt) / span).coerceIn(0.0, 1.0)
    }
}
