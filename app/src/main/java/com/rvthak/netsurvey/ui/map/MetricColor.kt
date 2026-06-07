package com.rvthak.netsurvey.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.rvthak.netsurvey.model.ThresholdBand
import com.rvthak.netsurvey.stats.MetricScale
import com.rvthak.netsurvey.ui.theme.MetricBad
import com.rvthak.netsurvey.ui.theme.MetricFair
import com.rvthak.netsurvey.ui.theme.MetricGood
import com.rvthak.netsurvey.ui.theme.MetricGreat
import com.rvthak.netsurvey.ui.theme.MetricPoor

/** The five green→red stops, ordered great → bad (matches [MetricScale] severity). */
private val scaleStops = listOf(MetricGreat, MetricGood, MetricFair, MetricPoor, MetricBad)

/**
 * Pin colour for [value] on its [band] (SPEC §8): interpolates the green→red theme
 * scale by the [MetricScale] severity fraction. Returns `null` when there's no
 * value to place, so the caller can fall back to a neutral pin colour rather than
 * implying "great". The severity logic is pure and unit-tested in
 * [com.rvthak.netsurvey.stats.MetricScaleTest]; only the lerp lives here.
 */
fun metricColor(value: Double?, band: ThresholdBand): Color? {
    val severity = MetricScale.severity(value, band)?.toFloat() ?: return null
    val scaled = severity * (scaleStops.size - 1)
    val lo = scaled.toInt().coerceIn(0, scaleStops.size - 2)
    return lerp(scaleStops[lo], scaleStops[lo + 1], scaled - lo)
}
