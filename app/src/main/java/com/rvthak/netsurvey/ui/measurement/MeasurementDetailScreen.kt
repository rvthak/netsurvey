package com.rvthak.netsurvey.ui.measurement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.db.MeasurementWithDetails
import com.rvthak.netsurvey.data.db.ServingCellEntity
import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.SampleKind
import com.rvthak.netsurvey.ui.common.Format

/**
 * Phase 5 — measurement detail (SPEC §8): summary cards, the signal-over-time
 * chart drawn from the raw samples, and the Cells section (serving-cell timeline,
 * neighbor count, data-quality badge).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    measurementId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { NetSurveyRepository(context) }
    val details by remember(measurementId) { repo.observeMeasurementDetails(measurementId) }
        .collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Measurement") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val d = details
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderCard(d)
            SummaryCard("Signal", listOf(
                "RSRP (median)" to Format.metric(Metric.RSRP, d.measurement.summary.rsrpMedian),
                "RSRP p10 / p90" to "${Format.number(d.measurement.summary.rsrpP10, "")} / ${Format.number(d.measurement.summary.rsrpP90, "dBm")}",
                "RSRQ (median)" to Format.number(d.measurement.summary.rsrqMedian, "dB"),
                "SINR (median)" to Format.number(d.measurement.summary.sinrMedian, "dB"),
            ))
            SummaryCard("Latency & reliability", listOf(
                "Latency (median)" to Format.metric(Metric.LATENCY, d.measurement.summary.latencyMedian),
                "Latency p10 / p90" to "${Format.number(d.measurement.summary.latencyP10, "")} / ${Format.number(d.measurement.summary.latencyP90, "ms")}",
                "Jitter" to Format.metric(Metric.JITTER, d.measurement.summary.jitter),
                "Reliability" to Format.metric(Metric.SUCCESS, d.measurement.summary.successPct),
            ))
            SummaryCard("Speed", listOf(
                "Download" to Format.metric(Metric.DOWNLOAD, d.measurement.summary.downloadMbps),
                "Upload" to Format.metric(Metric.UPLOAD, d.measurement.summary.uploadMbps),
            ))

            Text("Signal over time", style = MaterialTheme.typography.titleSmall)
            SignalChart(d)

            CellsSection(d)
        }
    }
}

@Composable
private fun HeaderCard(d: MeasurementWithDetails) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.timestamp(d.measurement.startedAt), style = MaterialTheme.typography.titleMedium)
            Text(
                "Duration ${Format.duration(d.measurement.durationSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.measurement.summary.mixedTech) {
                Text(
                    "⚠ Radio type changed mid-test — RSRP is mixed-tech.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (d.measurement.notes.isNotBlank()) {
                Text(d.measurement.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, rows: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalChart(d: MeasurementWithDetails) {
    val points = remember(d.measurement.id) {
        d.samples
            .filter { it.kind == SampleKind.SIGNAL && it.rsrp != null }
            .sortedBy { it.tOffsetMs }
            .map { (it.tOffsetMs / 1000.0) to it.rsrp!! }
    }

    if (points.size < 2) {
        Text(
            "Not enough signal samples to plot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    androidx.compose.runtime.LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries { series(points.map { it.first }, points.map { it.second }) }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(),
            ),
            modelProducer = modelProducer,
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(12.dp),
        )
    }
}

@Composable
private fun CellsSection(d: MeasurementWithDetails) {
    val s = d.measurement.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cells", style = MaterialTheme.typography.titleSmall)
            Text(
                "Data quality: ${s.cellDataQuality.name.lowercase().replace('_', ' ')}" +
                    "   •   neighbours observed: ${s.distinctNeighborCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.nrIdentityUnavailable) {
                Text(
                    "5G NSA: NR present but its cell identity was hidden by the OS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (d.servingCells.isEmpty()) {
                Text("No serving cell identity captured.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Serving cells (${d.servingCells.size}, in order)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                d.servingCells.forEach { ServingCellRow(it) }
            }
        }
    }
}

@Composable
private fun ServingCellRow(c: ServingCellEntity) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            "${c.tech} • ${c.globalId?.let { "id $it" } ?: "id hidden"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            buildString {
                append("tower ${c.derivedTowerId ?: "—"}")
                append("  •  pci ${c.pci ?: "—"}")
                append("  •  band ${c.band ?: "—"}")
                append("  •  dwell ${c.dwellMs / 1000}s")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
