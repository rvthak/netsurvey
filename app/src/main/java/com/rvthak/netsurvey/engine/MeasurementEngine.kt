package com.rvthak.netsurvey.engine

import android.content.Context
import android.os.PowerManager
import com.rvthak.netsurvey.data.db.NeighborCellEntity
import com.rvthak.netsurvey.data.db.SampleEntity
import com.rvthak.netsurvey.data.db.ServingCellEntity
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.MeasurementSummary
import com.rvthak.netsurvey.model.SampleKind
import com.rvthak.netsurvey.stats.Stats
import com.rvthak.netsurvey.telephony.CellDataQuality
import com.rvthak.netsurvey.telephony.CellRole
import com.rvthak.netsurvey.telephony.CellTech
import com.rvthak.netsurvey.telephony.RadioSnapshot
import com.rvthak.netsurvey.telephony.ServingCell
import com.rvthak.netsurvey.telephony.TelephonyReader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

enum class RunPhase { SAMPLING, DOWNLOAD, UPLOAD, DONE }

/** Live progress emitted during a run (SPEC §3 Phase 3 "live progress state"). */
data class RunProgress(
    val phase: RunPhase,
    val elapsedMs: Long,
    val totalMs: Long,
    val currentRsrp: Int?,
    val currentNetwork: String?,
    val probesSent: Int,
    val probesOk: Int,
    val lastLatencyMs: Long?,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
)

/** Everything needed to persist a finished run; the caller attaches the typeId. */
data class RunResult(
    val startedAt: Long,
    val durationSec: Int,
    val summary: MeasurementSummary,
    val samples: List<SampleEntity>,
    val servingCells: List<ServingCellEntity>,
    val neighborCells: List<NeighborCellEntity>,
)

/**
 * Runs one timed measurement (SPEC §4). Signal is polled ~1 Hz for the whole run
 * (including the speed burst, to capture signal under load); latency is probed
 * ~2 Hz only during the sampling window, so saturation bufferbloat doesn't skew
 * jitter/reliability. Then a single capped download+upload burst.
 *
 * Cancellation (e.g. the app being backgrounded) propagates out of [run] as a
 * CancellationException; the wakelock is always released and nothing is persisted.
 */
class MeasurementEngine(
    context: Context,
    private val reader: TelephonyReader = TelephonyReader(context),
    private val probes: Probes = Probes(),
) {
    private val appContext = context.applicationContext

    suspend fun run(
        durationSec: Int,
        dataCap: DataCapConfig,
        includeSpeedTest: Boolean = true,
        onProgress: (RunProgress) -> Unit,
    ): RunResult = coroutineScope {
        val pm = appContext.getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netsurvey:measurement").apply {
            setReferenceCounted(false)
            acquire(durationSec * 1000L + 120_000L)
        }

        val acc = Accumulator()
        val startedAt = System.currentTimeMillis()
        val t0 = System.nanoTime()
        val totalMs = durationSec * 1000L
        fun elapsedMs() = (System.nanoTime() - t0) / 1_000_000L

        // The signal loop runs the whole time, including under the speed burst, so it
        // must report the active phase — otherwise its 1 Hz SAMPLING emissions clobber
        // the DOWNLOAD/UPLOAD status and the UI looks stuck in the sampling screen.
        val currentPhase = AtomicReference(RunPhase.SAMPLING)

        try {
            // Signal polling for the entire run.
            val signalJob = launch {
                while (coroutineContext.isActive) {
                    val snap = reader.snapshot()
                    acc.addSignal(snap, elapsedMs())
                    onProgress(acc.progress(currentPhase.get(), elapsedMs(), totalMs, snap))
                    delay(SIGNAL_INTERVAL_MS)
                }
            }

            // Latency probing only during the sampling window.
            val latencyJob = launch {
                while (coroutineContext.isActive) {
                    val latency = probes.latencyProbe()
                    acc.addProbe(latency, elapsedMs())
                    onProgress(acc.progress(RunPhase.SAMPLING, elapsedMs(), totalMs, null))
                    delay(PROBE_INTERVAL_MS)
                }
            }

            delay(totalMs)
            latencyJob.cancel()

            // Optional speed burst (once). Signal job keeps running underneath.
            // When skipped, download/upload stay null — i.e. "not measured", not 0.
            if (includeSpeedTest) {
                currentPhase.set(RunPhase.DOWNLOAD)
                onProgress(acc.progress(RunPhase.DOWNLOAD, elapsedMs(), totalMs, null))
                acc.downloadMbps = probes.downloadBurst(dataCap)
                onProgress(acc.progress(RunPhase.DOWNLOAD, elapsedMs(), totalMs, null))

                currentPhase.set(RunPhase.UPLOAD)
                onProgress(acc.progress(RunPhase.UPLOAD, elapsedMs(), totalMs, null))
                acc.uploadMbps = probes.uploadBurst(dataCap)
                onProgress(acc.progress(RunPhase.UPLOAD, elapsedMs(), totalMs, null))
            }

            currentPhase.set(RunPhase.DONE)
            signalJob.cancel()

            val result = acc.finish(startedAt, durationSec)
            onProgress(acc.progress(RunPhase.DONE, elapsedMs(), totalMs, null))
            result
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    companion object {
        private const val SIGNAL_INTERVAL_MS = 1_000L   // ~1 Hz (SPEC §10.4)
        private const val PROBE_INTERVAL_MS = 500L      // ~2 Hz
    }
}

/**
 * Thread-safe accumulator: the signal and latency loops append concurrently, so
 * all mutation is guarded by a lock.
 */
private class Accumulator {
    private val lock = Any()

    private val samples = ArrayList<SampleEntity>()
    private val rsrp = ArrayList<Double>()
    private val rsrq = ArrayList<Double>()
    private val sinr = ArrayList<Double>()
    private val latency = ArrayList<Double>()
    private var probesSent = 0
    private var probesOk = 0
    private var lastLatency: Long? = null
    private var lastRsrp: Int? = null
    private var lastNetwork: String? = null

    private val serving = LinkedHashMap<String, ServingAccum>()
    private val neighbors = LinkedHashMap<String, NeighborCellEntity>()
    private val servingTechs = HashSet<CellTech>()
    private val primaryKeys = HashSet<String>()
    private var signalSamples = 0
    private var nsaSeen = false
    private var nrIdSeen = false

    @Volatile var downloadMbps: Double? = null
    @Volatile var uploadMbps: Double? = null

    fun addSignal(snap: RadioSnapshot, tOffsetMs: Long) = synchronized(lock) {
        signalSamples++
        val sig = snap.headlineSignal
        lastRsrp = sig?.rsrp
        lastNetwork = snap.dataNetworkTypeLabel
        sig?.rsrp?.let { rsrp += it.toDouble() }
        sig?.rsrq?.let { rsrq += it.toDouble() }
        sig?.sinr?.let { sinr += it.toDouble() }

        samples += SampleEntity(
            measurementId = 0,
            tOffsetMs = tOffsetMs,
            kind = SampleKind.SIGNAL,
            rsrp = sig?.rsrp,
            rsrq = sig?.rsrq,
            sinr = sig?.sinr,
            radioType = snap.dataNetworkTypeLabel,
            band = snap.serving?.bands?.joinToString(",")?.ifEmpty { null },
            carrier = snap.carrier,
        )

        snap.serving?.let { primaryKeys += servingKey(it); servingTechs += it.tech }
        // Tally every serving cell this snapshot — primary plus any CA/NSA secondaries.
        snap.servingCells.forEach { sc ->
            val key = servingKey(sc)
            val a = serving.getOrPut(key) { ServingAccum(sc, tOffsetMs, tOffsetMs) }
            a.lastSeen = tOffsetMs
            a.sampleCount++
            if (sc.role == CellRole.PRIMARY) a.everPrimary = true
            if (a.cell.globalId == null && sc.globalId != null) a.cell = sc
        }
        if (snap.nsaActive) nsaSeen = true
        snap.neighbors.forEach { n ->
            if (n.tech == CellTech.NR && n.globalId != null) nrIdSeen = true
            val key = "${n.tech}/${n.pci}/${n.earfcn}"
            val existing = neighbors[key]
            if (existing == null) {
                neighbors[key] = NeighborCellEntity(
                    measurementId = 0, tech = n.tech, pci = n.pci, earfcn = n.earfcn,
                    globalId = n.globalId, bestSignal = n.bestSignalDbm,
                )
            } else {
                val best = listOfNotNull(existing.bestSignal, n.bestSignalDbm).maxOrNull()
                neighbors[key] = existing.copy(
                    bestSignal = best, globalId = existing.globalId ?: n.globalId,
                )
            }
        }
    }

    fun addProbe(latencyMs: Long?, tOffsetMs: Long) = synchronized(lock) {
        probesSent++
        lastLatency = latencyMs
        if (latencyMs != null) {
            probesOk++
            latency += latencyMs.toDouble()
        }
        samples += SampleEntity(
            measurementId = 0,
            tOffsetMs = tOffsetMs,
            kind = SampleKind.PROBE,
            latencyMs = latencyMs,
            ok = latencyMs != null,
        )
    }

    fun progress(phase: RunPhase, elapsedMs: Long, totalMs: Long, snap: RadioSnapshot?): RunProgress =
        synchronized(lock) {
            RunProgress(
                phase = phase,
                elapsedMs = elapsedMs,
                totalMs = totalMs,
                currentRsrp = snap?.headlineSignal?.rsrp ?: lastRsrp,
                currentNetwork = snap?.dataNetworkTypeLabel ?: lastNetwork,
                probesSent = probesSent,
                probesOk = probesOk,
                lastLatencyMs = lastLatency,
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
            )
        }

    fun finish(startedAt: Long, durationSec: Int): RunResult = synchronized(lock) {
        val servingCells = serving.values
            .sortedBy { it.firstSeen }
            .map { a ->
                ServingCellEntity(
                    measurementId = 0,
                    tech = a.cell.tech,
                    globalId = a.cell.globalId,
                    derivedTowerId = a.cell.derivedTowerId,
                    pci = a.cell.pci,
                    tac = a.cell.tac,
                    earfcn = a.cell.earfcn,
                    band = a.cell.bands.joinToString(",").ifEmpty { null },
                    firstSeen = a.firstSeen,
                    lastSeen = a.lastSeen,
                    dwellMs = a.lastSeen - a.firstSeen,
                    // A cell that was ever the anchor is reported as PRIMARY; one only
                    // ever seen as a concurrent carrier is SECONDARY (CA / NSA).
                    role = if (a.everPrimary) CellRole.PRIMARY else CellRole.SECONDARY,
                    sampleCount = a.sampleCount,
                    sharePct = if (signalSamples > 0) a.sampleCount * 100.0 / signalSamples else null,
                )
            }

        val hasServing = servingCells.isNotEmpty()
        val hasGlobalId = servingCells.any { it.globalId != null }
        val hasPci = servingCells.any { it.pci != null }
        val quality = when {
            !hasServing -> CellDataQuality.UNAVAILABLE
            hasGlobalId && neighbors.isNotEmpty() -> CellDataQuality.FULL
            hasGlobalId -> CellDataQuality.SERVING_ONLY
            hasPci -> CellDataQuality.PCI_ONLY
            else -> CellDataQuality.UNAVAILABLE
        }

        val summary = MeasurementSummary(
            rsrpMedian = Stats.median(rsrp),
            rsrpP10 = Stats.percentile(rsrp, 10.0),
            rsrpP90 = Stats.percentile(rsrp, 90.0),
            rsrqMedian = Stats.median(rsrq),
            sinrMedian = Stats.median(sinr),
            latencyMedian = Stats.median(latency),
            latencyP10 = Stats.percentile(latency, 10.0),
            latencyP90 = Stats.percentile(latency, 90.0),
            jitter = Stats.jitter(latency),
            successPct = if (probesSent > 0) probesOk * 100.0 / probesSent else null,
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            cellDataQuality = quality,
            nrIdentityUnavailable = nsaSeen && !nrIdSeen,
            distinctNeighborCount = neighbors.size,
            mixedTech = servingTechs.size > 1,
            endcSeen = nsaSeen,
            handoverOccurred = primaryKeys.size > 1,
            secondaryCellCount = servingCells.count { it.role == CellRole.SECONDARY },
            dominantCellSharePct = servingCells.mapNotNull { it.sharePct }.maxOrNull(),
        )

        RunResult(
            startedAt = startedAt,
            durationSec = durationSec,
            summary = summary,
            samples = samples.toList(),
            servingCells = servingCells,
            neighborCells = neighbors.values.toList(),
        )
    }

    private fun servingKey(sc: ServingCell): String =
        "${sc.tech}:${sc.globalId ?: "pci${sc.pci}"}"

    private class ServingAccum(var cell: ServingCell, val firstSeen: Long, var lastSeen: Long) {
        var sampleCount: Int = 0
        var everPrimary: Boolean = false
    }
}
