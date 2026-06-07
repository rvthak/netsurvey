package com.rvthak.netsurvey.stats

import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.MeasurementSummary
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure statistics (SPEC §6). No Android dependencies → unit-tested on the JVM.
 * The headline of every series is the **median** (immune to spikes and to the
 * logarithmic nature of dBm), with p10/p90 as spread.
 */
object Stats {

    /** Linear-interpolation percentile (type-7), p in 0..100. null on empty. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = (p / 100.0) * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo]
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    fun median(values: List<Double>): Double? = percentile(values, 50.0)

    /**
     * Jitter = mean absolute difference between consecutive latency samples
     * (inter-arrival variation). null if fewer than two samples.
     */
    fun jitter(latencies: List<Double>): Double? {
        if (latencies.size < 2) return null
        var sum = 0.0
        for (i in 1 until latencies.size) sum += abs(latencies[i] - latencies[i - 1])
        return sum / (latencies.size - 1)
    }

    /** Equal-weight mean of the non-null values, or null if all null/empty. */
    fun equalWeightAverage(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        return present.sum() / present.size
    }
}

/**
 * Per-type rollup (SPEC §6): a measurement type's overall stats are the
 * **equal-weight average** of its measurements' summaries — one vote each,
 * regardless of duration.
 */
data class TypeAggregate(
    val measurementCount: Int,
    private val values: Map<Metric, Double?>,
) {
    fun value(metric: Metric): Double? = values[metric]

    companion object {
        fun from(summaries: List<MeasurementSummary>): TypeAggregate {
            val values = Metric.entries.associateWith { metric ->
                Stats.equalWeightAverage(summaries.map { it.value(metric) })
            }
            return TypeAggregate(measurementCount = summaries.size, values = values)
        }
    }
}
