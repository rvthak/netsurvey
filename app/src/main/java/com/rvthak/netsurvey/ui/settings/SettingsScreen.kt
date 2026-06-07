package com.rvthak.netsurvey.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvthak.netsurvey.data.db.PlanEntity
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.ThresholdBand
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Settings (SPEC §8): pick the primary metric that colours the map, edit each
 * metric's green→red threshold band, set the default data cap for new runs, and
 * manage plans (rename / delete). Every edit writes straight through to
 * DataStore/Room, so the map recolours/relabels live as soon as you change a
 * setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var planToRename by remember { mutableStateOf<PlanEntity?>(null) }
    var planToDelete by remember { mutableStateOf<PlanEntity?>(null) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) pendingImport = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { PrimaryMetricSection(selected = settings.primaryMetric, onSelect = vm::setPrimaryMetric) }
            item { ThresholdsSection(settings = settings, onChange = vm::setThreshold) }
            item { DataCapSection(cap = settings.defaultDataCap, onChange = vm::setDefaultDataCap) }
            item {
                Text("Plans", style = MaterialTheme.typography.titleMedium)
            }
            if (plans.isEmpty()) {
                item {
                    Text(
                        "No plans yet — add one from the map screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(plans, key = { it.id }) { plan ->
                    PlanRow(
                        plan = plan,
                        onRename = { planToRename = plan },
                        onDelete = { planToDelete = plan },
                    )
                }
            }

            item {
                DataSection(
                    busy = busy,
                    onExport = { exportLauncher.launch(vm.suggestedBackupName()) },
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-zip-compressed",
                            ),
                        )
                    },
                )
            }
            item { HelpSection() }
        }
    }

    planToRename?.let { plan ->
        TextPromptDialog(
            title = "Rename plan",
            initial = plan.name,
            confirmLabel = "Rename",
            onConfirm = { vm.renamePlan(plan, it); planToRename = null },
            onDismiss = { planToRename = null },
        )
    }
    planToDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { planToDelete = null },
            title = { Text("Delete \"${plan.name}\"?") },
            text = {
                Text(
                    "This permanently removes the plan, its spots, and all their " +
                        "measurements. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deletePlan(plan); planToDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { planToDelete = null }) { Text("Cancel") } },
        )
    }
    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import backup?") },
            text = {
                Text(
                    "This replaces everything currently in the app — all plans, spots, " +
                        "measurements, and settings — with the contents of the backup. " +
                        "This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.importFrom(uri); pendingImport = null }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DataSection(busy: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    SettingsCard(
        title = "Backup",
        subtitle = "Export everything to a .zip you can keep, or import one to " +
            "restore on a new install. Import replaces current data.",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onExport, enabled = !busy) { Text("Export") }
            OutlinedButton(onClick = onImport, enabled = !busy) { Text("Import") }
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun HelpSection() {
    SettingsCard(
        title = "What the metrics mean",
        subtitle = "Each spot shows the median of its samples; a type's headline is " +
            "the equal-weight average of its measurements.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HelpRow("Signal (RSRP)", "Serving-cell signal power in dBm. Higher (closer to 0) is stronger; −80 is great, −110 is poor.")
            HelpRow("Latency", "Round-trip time of small HTTP probes to Cloudflare, in ms. Lower is better.")
            HelpRow("Jitter", "How much latency varies between consecutive probes, in ms. Lower is steadier.")
            HelpRow("Reliability", "Percentage of probes that succeeded within the timeout. 100% is perfect.")
            HelpRow("Download / Upload", "A single capped speed burst each, in Mbps. Higher is faster.")
            HelpRow(
                "Cells",
                "Serving and neighbour cell identity, shown on a measurement's detail only — too " +
                    "device-dependent to rank spots by, and on 5G-NSA the NR identity is often hidden by the OS.",
            )
        }
    }
}

@Composable
private fun HelpRow(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrimaryMetricSection(selected: Metric, onSelect: (Metric) -> Unit) {
    SettingsCard(
        title = "Primary metric",
        subtitle = "Drives pin colour and the value shown on each spot.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric.entries.forEach { metric ->
                FilterChip(
                    selected = metric == selected,
                    onClick = { onSelect(metric) },
                    label = { Text(metric.label) },
                )
            }
        }
    }
}

@Composable
private fun ThresholdsSection(
    settings: com.rvthak.netsurvey.model.AppSettings,
    onChange: (Metric, ThresholdBand) -> Unit,
) {
    SettingsCard(
        title = "Colour thresholds",
        subtitle = "“Great” is the green end, “Poor” the red end. Values between " +
            "interpolate; past either end clamp.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Metric.entries.forEach { metric ->
                val band = settings.band(metric)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${metric.label} (${metric.unit})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DecimalField(
                            label = "Great",
                            value = band.greatAt,
                            onCommit = { onChange(metric, band.copy(greatAt = it)) },
                            modifier = Modifier.weight(1f),
                        )
                        DecimalField(
                            label = "Poor",
                            value = band.poorAt,
                            onCommit = { onChange(metric, band.copy(poorAt = it)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DataCapSection(cap: DataCapConfig, onChange: (DataCapConfig) -> Unit) {
    SettingsCard(
        title = "Default data cap",
        subtitle = "Prefilled into each new run; the speed burst stops at whichever " +
            "limit it hits first.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Download", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntField(
                    label = "Max seconds",
                    value = cap.downloadMaxSec,
                    onCommit = { onChange(cap.copy(downloadMaxSec = it)) },
                    modifier = Modifier.weight(1f),
                )
                IntField(
                    label = "Max MB",
                    value = cap.downloadMaxMb,
                    onCommit = { onChange(cap.copy(downloadMaxMb = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text("Upload", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntField(
                    label = "Max seconds",
                    value = cap.uploadMaxSec,
                    onCommit = { onChange(cap.copy(uploadMaxSec = it)) },
                    modifier = Modifier.weight(1f),
                )
                IntField(
                    label = "Max MB",
                    value = cap.uploadMaxMb,
                    onCommit = { onChange(cap.copy(uploadMaxMb = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlanRow(plan: PlanEntity, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(plan.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Rename plan")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete plan",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * A self-buffering numeric field: it commits parsed edits upward immediately, and
 * only resyncs its text when the upstream value changes to something that doesn't
 * already match what's typed — so committing your own edit never resets the cursor.
 * Uses the phone keypad so the minus sign (needed for RSRP/dBm) is reachable.
 */
@Composable
private fun DecimalField(
    label: String,
    value: Double,
    onCommit: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(formatDouble(value)) }
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value) text = formatDouble(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toDoubleOrNull()?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toIntOrNull()?.takeIf { n -> n > 0 }?.let(onCommit)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val submit = { if (text.isNotBlank()) onConfirm(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.widthIn(min = 240.dp),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Trim a trailing ".0" so whole-number thresholds read cleanly (e.g. "-80" not "-80.0"). */
private fun formatDouble(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
