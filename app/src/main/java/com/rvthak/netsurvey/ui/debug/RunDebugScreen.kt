package com.rvthak.netsurvey.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.engine.MeasurementEngine
import com.rvthak.netsurvey.engine.RunPhase
import com.rvthak.netsurvey.engine.RunProgress
import com.rvthak.netsurvey.engine.RunResult
import com.rvthak.netsurvey.model.DataCapConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase-3 spike screen: drives the real [MeasurementEngine], shows live progress,
 * and persists the finished run under a throwaway debug type so it can be checked
 * in the DB. Aborts cleanly if the app is backgrounded (SPEC §4).
 */
@Composable
fun RunDebugScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { NetSurveyRepository(context) }
    val engine = remember { MeasurementEngine(context) }

    var durationText by remember { mutableStateOf("30") }
    var progress by remember { mutableStateOf<RunProgress?>(null) }
    var result by remember { mutableStateOf<RunResult?>(null) }
    var status by remember { mutableStateOf("idle") }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true

    // Abort the run if the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, job) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Run a measurement", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = durationText,
            onValueChange = { durationText = it.filter(Char::isDigit) },
            label = { Text("Duration (s)") },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val duration = durationText.toIntOrNull()?.coerceIn(5, 300) ?: 30
                result = null
                progress = null
                status = "running"
                job = scope.launch {
                    try {
                        val run = engine.run(
                            durationSec = duration,
                            dataCap = DataCapConfig.DEFAULT,
                            onProgress = { progress = it },
                        )
                        result = run
                        val typeId = repo.ensureDebugType()
                        val id = repo.saveRun(typeId, DataCapConfig.DEFAULT, "debug run", run)
                        status = "saved measurement #$id"
                    } catch (e: CancellationException) {
                        status = "aborted (backgrounded?) — not saved"
                    } catch (e: Exception) {
                        status = "error: ${e.message}"
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Running…" else "Start") }

        if (running) {
            Button(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Abort") }
        }

        Text("Status: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        progress?.let { p -> LiveProgress(p) }
        result?.let { r -> ResultDump(r) }
    }
}

@Composable
private fun LiveProgress(p: RunProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val frac = if (p.totalMs > 0) (p.elapsedMs.toFloat() / p.totalMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            Text(
                buildString {
                    appendLine("phase:    ${p.phase}")
                    appendLine("elapsed:  ${p.elapsedMs / 1000}s / ${p.totalMs / 1000}s")
                    appendLine("network:  ${p.currentNetwork ?: "—"}")
                    appendLine("RSRP:     ${p.currentRsrp?.let { "$it dBm" } ?: "—"}")
                    appendLine("probes:   ${p.probesOk}/${p.probesSent} ok")
                    appendLine("last RTT: ${p.lastLatencyMs?.let { "$it ms" } ?: "—"}")
                    if (p.phase == RunPhase.DOWNLOAD || p.phase == RunPhase.UPLOAD || p.phase == RunPhase.DONE) {
                        appendLine("down:     ${p.downloadMbps?.let { "%.1f Mbps".format(it) } ?: "…"}")
                        appendLine("up:       ${p.uploadMbps?.let { "%.1f Mbps".format(it) } ?: "…"}")
                    }
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ResultDump(r: RunResult) {
    val s = r.summary
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            buildString {
                appendLine("— summary —")
                appendLine("RSRP median:  ${s.rsrpMedian?.fmt("dBm") ?: "—"}  (p10 ${s.rsrpP10?.fmt() ?: "—"} / p90 ${s.rsrpP90?.fmt() ?: "—"})")
                appendLine("RSRQ median:  ${s.rsrqMedian?.fmt("dB") ?: "—"}")
                appendLine("SINR median:  ${s.sinrMedian?.fmt() ?: "—"}")
                appendLine("Latency med:  ${s.latencyMedian?.fmt("ms") ?: "—"}  (p10 ${s.latencyP10?.fmt() ?: "—"} / p90 ${s.latencyP90?.fmt() ?: "—"})")
                appendLine("Jitter:       ${s.jitter?.fmt("ms") ?: "—"}")
                appendLine("Success:      ${s.successPct?.fmt("%") ?: "—"}")
                appendLine("Download:     ${s.downloadMbps?.fmt("Mbps") ?: "—"}")
                appendLine("Upload:       ${s.uploadMbps?.fmt("Mbps") ?: "—"}")
                appendLine("Cell quality: ${s.cellDataQuality}")
                appendLine("NR id hidden: ${s.nrIdentityUnavailable}")
                appendLine("Neighbours:   ${s.distinctNeighborCount}")
                appendLine("Mixed tech:   ${s.mixedTech}")
                appendLine("Samples:      ${r.samples.size}  serving=${r.servingCells.size}")
            },
            modifier = Modifier.padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun Double.fmt(unit: String = ""): String =
    if (unit.isEmpty()) "%.1f".format(this) else "%.1f %s".format(this, unit)
