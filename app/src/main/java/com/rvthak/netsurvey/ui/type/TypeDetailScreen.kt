package com.rvthak.netsurvey.ui.type

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.db.MeasurementEntity
import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.stats.TypeAggregate
import com.rvthak.netsurvey.ui.common.Format

/**
 * Phase 5 — type detail (SPEC §8): the equal-weight aggregate header over this
 * spot's measurements, plus the list of individual measurements. "New measurement"
 * re-tests the same spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeDetailScreen(
    typeId: Long,
    onBack: () -> Unit,
    onNewMeasurement: (Long) -> Unit,
    onOpenMeasurement: (Long) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { NetSurveyRepository(context) }
    val type by remember(typeId) { repo.observeType(typeId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val measurements by remember(typeId) { repo.observeMeasurementsForType(typeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val aggregate = remember(measurements) { TypeAggregate.from(measurements.map { it.summary }) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(type?.name ?: "Spot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNewMeasurement(typeId) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New measurement") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AggregateHeader(aggregate) }

            if (measurements.isEmpty()) {
                item {
                    Text(
                        "No measurements here yet. Tap \"New measurement\" to run one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        "Measurements (${measurements.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(measurements, key = { it.id }) { m ->
                    MeasurementRow(m, onClick = { onOpenMeasurement(m.id) })
                }
            }
        }
    }
}

@Composable
private fun AggregateHeader(aggregate: TypeAggregate) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Average across measurements", style = MaterialTheme.typography.titleMedium)
            Text(
                if (aggregate.measurementCount == 0) {
                    "no data yet"
                } else {
                    "equal-weight over ${aggregate.measurementCount} measurement" +
                        if (aggregate.measurementCount == 1) "" else "s"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Metric.entries.forEach { metric ->
                    MetricStat(metric.label, Format.metric(metric, aggregate.value(metric)))
                }
            }
        }
    }
}

@Composable
private fun MetricStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MeasurementRow(m: MeasurementEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(Format.timestamp(m.startedAt), style = MaterialTheme.typography.bodyMedium)
                Text(
                    Format.duration(m.durationSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MetricStat("RSRP", Format.metric(Metric.RSRP, m.summary.value(Metric.RSRP)))
                MetricStat("Latency", Format.metric(Metric.LATENCY, m.summary.value(Metric.LATENCY)))
                MetricStat("Down", Format.metric(Metric.DOWNLOAD, m.summary.value(Metric.DOWNLOAD)))
            }
            if (m.notes.isNotBlank()) {
                Text(
                    m.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
