package com.rvthak.netsurvey.ui.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.SettingsRepository
import com.rvthak.netsurvey.engine.MeasurementEngine
import com.rvthak.netsurvey.engine.RunPhase
import com.rvthak.netsurvey.engine.RunProgress
import com.rvthak.netsurvey.model.AppSettings
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.ui.rememberPermissionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 5 — the run flow (SPEC §8). Setup (duration, data cap prefilled from
 * settings, notes), then the real [MeasurementEngine] with live progress, saved
 * under the given measurement type. Aborts cleanly if the app is backgrounded
 * (SPEC §4); nothing is persisted on abort.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementRunScreen(
    typeId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { NetSurveyRepository(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    val engine = remember { MeasurementEngine(context) }
    val permissions = rememberPermissionController()

    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT)

    var durationText by remember { mutableStateOf("30") }
    var notes by remember { mutableStateOf("") }
    var dlSec by remember { mutableStateOf(DataCapConfig.DEFAULT.downloadMaxSec.toString()) }
    var dlMb by remember { mutableStateOf(DataCapConfig.DEFAULT.downloadMaxMb.toString()) }
    var ulSec by remember { mutableStateOf(DataCapConfig.DEFAULT.uploadMaxSec.toString()) }
    var ulMb by remember { mutableStateOf(DataCapConfig.DEFAULT.uploadMaxMb.toString()) }

    // Seed the data-cap fields from the saved default once settings have loaded.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        if (!seeded) {
            val c = settings.defaultDataCap
            dlSec = c.downloadMaxSec.toString(); dlMb = c.downloadMaxMb.toString()
            ulSec = c.uploadMaxSec.toString(); ulMb = c.uploadMaxMb.toString()
            seeded = true
        }
    }

    var progress by remember { mutableStateOf<RunProgress?>(null) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true

    // Abort the run if the app leaves the foreground (SPEC §4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, job) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New measurement") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !running) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissions.granted) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Location + phone permission is required to read signal and cell info.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = permissions.request) { Text("Grant permission") }
                    }
                }
            }

            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it.filter(Char::isDigit).take(3) },
                label = { Text("Duration (seconds)") },
                enabled = !running,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Data cap (stops at time or size, whichever first)", style = MaterialTheme.typography.titleSmall)
            CapRow("Download", dlSec, { dlSec = it }, dlMb, { dlMb = it }, enabled = !running)
            CapRow("Upload", ulSec, { ulSec = it }, ulMb, { ulMb = it }, enabled = !running)

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val duration = durationText.toIntOrNull()?.coerceIn(5, 300) ?: 30
                    val cap = DataCapConfig(
                        downloadMaxSec = dlSec.toIntOrNull()?.coerceIn(1, 120) ?: 10,
                        downloadMaxMb = dlMb.toIntOrNull()?.coerceIn(1, 2000) ?: 100,
                        uploadMaxSec = ulSec.toIntOrNull()?.coerceIn(1, 120) ?: 5,
                        uploadMaxMb = ulMb.toIntOrNull()?.coerceIn(1, 2000) ?: 25,
                    )
                    progress = null
                    status = "running"
                    job = scope.launch {
                        try {
                            val run = engine.run(
                                durationSec = duration,
                                dataCap = cap,
                                onProgress = { progress = it },
                            )
                            repo.saveRun(typeId, cap, notes.trim(), run)
                            status = "saved"
                            onSaved()
                        } catch (_: CancellationException) {
                            status = "aborted (app backgrounded) — nothing saved"
                        } catch (e: Exception) {
                            status = "error: ${e.message}"
                        }
                    }
                },
                enabled = permissions.granted && !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) "Running…" else "Start measurement") }

            if (running) {
                OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Abort")
                }
            }

            if (status.isNotEmpty() && status != "running" && status != "saved") {
                Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            progress?.let { LiveProgress(it) }
        }
    }
}

@Composable
private fun CapRow(
    label: String,
    sec: String,
    onSec: (String) -> Unit,
    mb: String,
    onMb: (String) -> Unit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 18.dp).weight(0.8f),
        )
        OutlinedTextField(
            value = sec,
            onValueChange = { onSec(it.filter(Char::isDigit).take(3)) },
            label = { Text("sec") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = mb,
            onValueChange = { onMb(it.filter(Char::isDigit).take(4)) },
            label = { Text("MB") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LiveProgress(p: RunProgress) {
    val sampling = p.phase == RunPhase.SAMPLING
    val speedTest = p.phase == RunPhase.DOWNLOAD || p.phase == RunPhase.UPLOAD
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Determinate bar only for the timed sampling window; the speed burst
            // afterwards runs to its own data/time cap, so show it as indeterminate.
            if (speedTest) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                val frac = if (p.totalMs > 0) (p.elapsedMs.toFloat() / p.totalMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }

            val phaseLabel = when (p.phase) {
                RunPhase.SAMPLING -> "Sampling signal & latency"
                RunPhase.DOWNLOAD -> "Speed test — download (runs after the timed window)"
                RunPhase.UPLOAD -> "Speed test — upload (runs after the timed window)"
                RunPhase.DONE -> "Done"
            }
            Text(phaseLabel, style = MaterialTheme.typography.titleSmall)

            Text(
                buildString {
                    // Cap the sampling clock at the set duration so it never reads
                    // "41s / 30s"; the speed test's own time isn't part of that window.
                    val shown = (p.elapsedMs / 1000).coerceAtMost(p.totalMs / 1000)
                    appendLine("sampled:  ${shown}s / ${p.totalMs / 1000}s")
                    appendLine("network:  ${p.currentNetwork ?: "—"}")
                    appendLine("RSRP:     ${p.currentRsrp?.let { "$it dBm" } ?: "—"}")
                    appendLine("probes:   ${p.probesOk}/${p.probesSent} ok")
                    appendLine("last RTT: ${p.lastLatencyMs?.let { "$it ms" } ?: "—"}")
                    if (!sampling) {
                        appendLine("down:     ${p.downloadMbps?.let { "%.1f Mbps".format(it) } ?: "…"}")
                        appendLine("up:       ${p.uploadMbps?.let { "%.1f Mbps".format(it) } ?: "…"}")
                    }
                }.trimEnd(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
