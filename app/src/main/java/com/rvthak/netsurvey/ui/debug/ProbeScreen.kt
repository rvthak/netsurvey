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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rvthak.netsurvey.telephony.RadioSnapshot
import com.rvthak.netsurvey.telephony.TelephonyReader
import com.rvthak.netsurvey.ui.rememberPermissionController
import kotlinx.coroutines.launch

/**
 * Phase-1 spike screen: dumps a raw [RadioSnapshot] so we can see exactly what
 * the *actual* phone exposes (which fields are unavailable, NSA behaviour, etc.).
 */
@Composable
fun ProbeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = rememberPermissionController()
    val reader = remember { TelephonyReader(context) }
    var snapshot by remember { mutableStateOf<RadioSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Telephony probe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Permissions: " + if (permissions.granted) "granted" else "not granted",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        if (!permissions.granted) {
            Button(onClick = permissions.request, modifier = Modifier.fillMaxWidth()) {
                Text("Grant location + phone")
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    snapshot = reader.snapshot()
                    busy = false
                }
            },
            enabled = permissions.granted && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Reading…" else "Read radio snapshot")
        }

        snapshot?.let { SnapshotDump(it) }
    }
}

@Composable
private fun SnapshotDump(s: RadioSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildString {
                appendLine("carrier:        ${s.carrier ?: "—"}")
                appendLine("network type:   ${s.dataNetworkTypeLabel}")
                appendLine("5G NSA active:  ${s.nsaActive}")
                appendLine("data quality:   ${s.cellDataQuality}")
                appendLine("NR id hidden:   ${s.nrIdentityUnavailable}")
                appendLine("serving cells:  ${s.servingCells.size} [${s.servingCells.joinToString { it.role.name.lowercase() }}]")
                appendLine()
                appendLine("— serving cell (primary) —")
                s.serving?.let { c ->
                    appendLine("tech:           ${c.tech}")
                    appendLine("globalId:       ${c.globalId ?: "unavailable"}")
                    appendLine("derivedTower:   ${c.derivedTowerId ?: "unavailable"}")
                    appendLine("pci:            ${c.pci ?: "unavailable"}")
                    appendLine("tac:            ${c.tac ?: "unavailable"}")
                    appendLine("earfcn:         ${c.earfcn ?: "unavailable"}")
                    appendLine("bands:          ${c.bands.ifEmpty { "unavailable" }}")
                    appendLine("mcc/mnc:        ${c.mccMnc ?: "unavailable"}")
                } ?: appendLine("(none registered)")
                appendLine()
                appendLine("— serving signal —")
                appendSignal(s.servingSignal)
                appendLine()
                appendLine("— NR signal (NSA) —")
                appendSignal(s.nrSignal)
                appendLine()
                appendLine("— neighbours (${s.neighbors.size}) —")
                if (s.neighbors.isEmpty()) appendLine("(none reported)")
                s.neighbors.forEach { n ->
                    appendLine("${n.tech} pci=${n.pci} earfcn=${n.earfcn} id=${n.globalId ?: "—"} dBm=${n.bestSignalDbm ?: "—"}")
                }
                if (s.errors.isNotEmpty()) {
                    appendLine()
                    appendLine("— errors —")
                    s.errors.forEach { appendLine(it) }
                }
            },
            modifier = Modifier.padding(12.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun StringBuilder.appendSignal(sig: com.rvthak.netsurvey.telephony.SignalReading?) {
    if (sig == null) {
        appendLine("(none)")
        return
    }
    appendLine("tech:           ${sig.tech}")
    appendLine("rsrp:           ${sig.rsrp?.let { "$it dBm" } ?: "unavailable"}")
    appendLine("rsrq:           ${sig.rsrq?.let { "$it dB" } ?: "unavailable"}")
    appendLine("sinr/snr:       ${sig.sinr ?: "unavailable"}")
    appendLine("level (0-4):    ${sig.level}")
}
