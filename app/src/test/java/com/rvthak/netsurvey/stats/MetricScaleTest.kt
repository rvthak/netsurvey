package com.rvthak.netsurvey.stats

import com.rvthak.netsurvey.model.ThresholdBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricScaleTest {

    private val eps = 1e-9

    // RSRP: higher is better, so greatAt (-80) > poorAt (-110).
    private val rsrp = ThresholdBand(greatAt = -80.0, poorAt = -110.0)

    // Latency: lower is better, so greatAt (30) < poorAt (100).
    private val latency = ThresholdBand(greatAt = 30.0, poorAt = 100.0)

    @Test fun nullValue_isNull() {
        assertNull(MetricScale.severity(null, rsrp))
    }

    @Test fun higherIsBetter_atGreat_isZero() {
        assertEquals(0.0, MetricScale.severity(-80.0, rsrp)!!, eps)
    }

    @Test fun higherIsBetter_atPoor_isOne() {
        assertEquals(1.0, MetricScale.severity(-110.0, rsrp)!!, eps)
    }

    @Test fun higherIsBetter_midpoint() {
        assertEquals(0.5, MetricScale.severity(-95.0, rsrp)!!, eps)
    }

    @Test fun higherIsBetter_pastGreat_clampsToZero() {
        assertEquals(0.0, MetricScale.severity(-50.0, rsrp)!!, eps)
    }

    @Test fun higherIsBetter_pastPoor_clampsToOne() {
        assertEquals(1.0, MetricScale.severity(-130.0, rsrp)!!, eps)
    }

    @Test fun lowerIsBetter_atGreat_isZero() {
        assertEquals(0.0, MetricScale.severity(30.0, latency)!!, eps)
    }

    @Test fun lowerIsBetter_atPoor_isOne() {
        assertEquals(1.0, MetricScale.severity(100.0, latency)!!, eps)
    }

    @Test fun lowerIsBetter_midpoint() {
        assertEquals(0.5, MetricScale.severity(65.0, latency)!!, eps)
    }

    @Test fun lowerIsBetter_pastGreat_clampsToZero() {
        assertEquals(0.0, MetricScale.severity(5.0, latency)!!, eps)
    }

    @Test fun degenerateBand_isZero() {
        assertEquals(0.0, MetricScale.severity(42.0, ThresholdBand(10.0, 10.0))!!, eps)
    }
}
