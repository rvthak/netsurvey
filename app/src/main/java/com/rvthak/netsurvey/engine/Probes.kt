package com.rvthak.netsurvey.engine

import com.rvthak.netsurvey.model.DataCapConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Network probes against Cloudflare's open speed endpoints (SPEC §4.2). Latency
 * uses small HTTP round-trips (not ICMP — Android can't ping without root); a
 * timed-out probe returns null and counts against reliability.
 */
class Probes {

    private val probeClient = OkHttpClient.Builder()
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val speedClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** One latency round-trip in ms, or null on failure/timeout. */
    suspend fun latencyProbe(): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATENCY_URL).header("Cache-Control", "no-cache").build()
        val start = System.nanoTime()
        try {
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes() // drain the (tiny) body
            }
            (System.nanoTime() - start) / 1_000_000L
        } catch (e: Exception) {
            null
        }
    }

    /** Capped download burst → Mbps, or null on failure. Honours time & byte caps. */
    suspend fun downloadBurst(cap: DataCapConfig): Double? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$DOWN_URL?bytes=${cap.downloadMaxBytes}")
            .header("Cache-Control", "no-cache")
            .build()
        val maxNs = cap.downloadMaxSec.toLong() * 1_000_000_000L
        try {
            val call = speedClient.newCall(req)
            val resp = call.execute()
            resp.use {
                if (!resp.isSuccessful) return@withContext null
                val stream = resp.body?.byteStream() ?: return@withContext null
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val startNs = System.nanoTime()
                try {
                    while (coroutineContext.isActive) {
                        val elapsed = System.nanoTime() - startNs
                        if (elapsed >= maxNs || total >= cap.downloadMaxBytes) break
                        val n = stream.read(buf)
                        if (n < 0) break
                        total += n
                    }
                } finally {
                    call.cancel()
                }
                val secs = (System.nanoTime() - startNs) / 1e9
                if (total > 0 && secs > 0) total * 8.0 / secs / 1e6 else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Capped upload burst → Mbps, or null on failure. */
    suspend fun uploadBurst(cap: DataCapConfig): Double? = withContext(Dispatchers.IO) {
        val body = CappedUploadBody(
            maxBytes = cap.uploadMaxBytes,
            maxNs = cap.uploadMaxSec.toLong() * 1_000_000_000L,
        )
        val req = Request.Builder().url(UP_URL).post(body).build()
        try {
            speedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
            }
            val secs = body.elapsedNs / 1e9
            if (body.bytesWritten > 0 && secs > 0) body.bytesWritten * 8.0 / secs / 1e6 else null
        } catch (e: Exception) {
            // Even a reset connection may have pushed measurable bytes.
            val secs = body.elapsedNs / 1e9
            if (body.bytesWritten > 0 && secs > 0) body.bytesWritten * 8.0 / secs / 1e6 else null
        }
    }

    /** Streams random-ish bytes until the byte or time cap is hit, timing itself. */
    private class CappedUploadBody(
        private val maxBytes: Long,
        private val maxNs: Long,
    ) : RequestBody() {
        @Volatile var bytesWritten: Long = 0
        @Volatile var elapsedNs: Long = 0

        override fun contentType() = "application/octet-stream".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            val chunk = ByteArray(64 * 1024)
            val startNs = System.nanoTime()
            var sent = 0L
            while (sent < maxBytes && (System.nanoTime() - startNs) < maxNs) {
                val remaining = maxBytes - sent
                val len = if (remaining < chunk.size) remaining.toInt() else chunk.size
                sink.write(chunk, 0, len)
                sink.flush()
                sent += len
            }
            bytesWritten = sent
            elapsedNs = System.nanoTime() - startNs
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 2_000L
        // bytes=0 → empty body, so the round-trip measures latency, not transfer.
        private const val LATENCY_URL = "https://speed.cloudflare.com/__down?bytes=0"
        private const val DOWN_URL = "https://speed.cloudflare.com/__down"
        private const val UP_URL = "https://speed.cloudflare.com/__up"
    }
}
