package com.rvthak.netsurvey.ui.common

import com.rvthak.netsurvey.model.Metric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared display formatting. A `null` value means "not measured" and renders as an
 * em dash — never a fabricated 0 (SPEC §1 "keep the data honest").
 */
object Format {

    private val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun timestamp(epochMs: Long): String = dateFmt.format(Date(epochMs))

    fun duration(sec: Int): String = if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"

    /** A primary-metric value with its unit, or "—" when not measured. */
    fun metric(metric: Metric, value: Double?): String {
        if (value == null) return "—"
        val decimals = if (metric == Metric.DOWNLOAD || metric == Metric.UPLOAD) 1 else 0
        return number(value, metric.unit, decimals)
    }

    fun number(value: Double?, unit: String, decimals: Int = 0): String {
        if (value == null) return "—"
        val n = "%.${decimals}f".format(value)
        return if (unit.isEmpty()) n else "$n $unit"
    }
}
