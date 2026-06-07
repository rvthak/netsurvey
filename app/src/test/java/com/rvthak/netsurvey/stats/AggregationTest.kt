package com.rvthak.netsurvey.stats

import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.MeasurementSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AggregationTest {

    private val eps = 1e-9

    @Test fun median_oddCount() {
        assertEquals(2.0, Stats.median(listOf(1.0, 3.0, 2.0))!!, eps)
    }

    @Test fun median_evenCount_interpolates() {
        // sorted 1,2,3,4 → midpoint between 2 and 3
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 3.0, 2.0))!!, eps)
    }

    @Test fun median_empty_isNull() {
        assertNull(Stats.median(emptyList()))
    }

    @Test fun percentile_p10_p90() {
        val v = (1..11).map { it.toDouble() } // 1..11
        // type-7: rank = p/100 * (n-1) = p/100 * 10
        assertEquals(2.0, Stats.percentile(v, 10.0)!!, eps)
        assertEquals(10.0, Stats.percentile(v, 90.0)!!, eps)
    }

    @Test fun percentile_singleValue() {
        assertEquals(42.0, Stats.percentile(listOf(42.0), 10.0)!!, eps)
    }

    @Test fun jitter_meanAbsConsecutiveDiff() {
        // diffs: |20-10|, |15-20|, |25-15| = 10,5,10 → mean 8.333..
        val j = Stats.jitter(listOf(10.0, 20.0, 15.0, 25.0))!!
        assertEquals(25.0 / 3.0, j, eps)
    }

    @Test fun jitter_tooFewSamples_isNull() {
        assertNull(Stats.jitter(listOf(10.0)))
    }

    @Test fun equalWeightAverage_ignoresNulls() {
        assertEquals(20.0, Stats.equalWeightAverage(listOf(10.0, null, 30.0))!!, eps)
    }

    @Test fun equalWeightAverage_allNull_isNull() {
        assertNull(Stats.equalWeightAverage(listOf(null, null)))
    }

    @Test fun typeRollup_isEqualWeightPerMeasurement() {
        // Two measurements, one vote each regardless of how they'd weight by duration.
        val m1 = MeasurementSummary(rsrpMedian = -90.0, latencyMedian = 20.0, downloadMbps = 100.0)
        val m2 = MeasurementSummary(rsrpMedian = -100.0, latencyMedian = 40.0, downloadMbps = 200.0)
        val agg = TypeAggregate.from(listOf(m1, m2))

        assertEquals(2, agg.measurementCount)
        assertEquals(-95.0, agg.value(Metric.RSRP)!!, eps)
        assertEquals(30.0, agg.value(Metric.LATENCY)!!, eps)
        assertEquals(150.0, agg.value(Metric.DOWNLOAD)!!, eps)
    }

    @Test fun typeRollup_partialData_skipsMissing() {
        val m1 = MeasurementSummary(rsrpMedian = -80.0, uploadMbps = null)
        val m2 = MeasurementSummary(rsrpMedian = null, uploadMbps = 50.0)
        val agg = TypeAggregate.from(listOf(m1, m2))

        assertEquals(-80.0, agg.value(Metric.RSRP)!!, eps)
        assertEquals(50.0, agg.value(Metric.UPLOAD)!!, eps)
    }

    @Test fun typeRollup_empty_allNull() {
        val agg = TypeAggregate.from(emptyList())
        assertEquals(0, agg.measurementCount)
        Metric.entries.forEach { assertNull(agg.value(it)) }
    }
}
