package com.rvthak.netsurvey.model

/**
 * The metrics that can drive pin colour / be the "primary metric" (SPEC §8).
 * RSRQ and SINR are detail-only and intentionally excluded here.
 */
enum class Metric(
    val key: String,
    val label: String,
    val unit: String,
    /** true → bigger is better (RSRP, speed); false → smaller is better (latency). */
    val higherIsBetter: Boolean,
) {
    RSRP("rsrp", "Signal (RSRP)", "dBm", true),
    LATENCY("latency", "Latency", "ms", false),
    JITTER("jitter", "Jitter", "ms", false),
    SUCCESS("success", "Reliability", "%", true),
    DOWNLOAD("download", "Download", "Mbps", true),
    UPLOAD("upload", "Upload", "Mbps", true);

    companion object {
        fun fromKey(key: String): Metric = entries.firstOrNull { it.key == key } ?: RSRP
    }
}
