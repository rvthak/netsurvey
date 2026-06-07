package com.rvthak.netsurvey.model

import kotlinx.serialization.Serializable

/**
 * Per-measurement ceiling for the speed burst (SPEC §4.1). Each direction stops
 * at whichever limit (time or bytes) is hit first.
 */
@Serializable
data class DataCapConfig(
    val downloadMaxSec: Int = 10,
    val downloadMaxMb: Int = 100,
    val uploadMaxSec: Int = 5,
    val uploadMaxMb: Int = 25,
) {
    val downloadMaxBytes: Long get() = downloadMaxMb.toLong() * 1_000_000L
    val uploadMaxBytes: Long get() = uploadMaxMb.toLong() * 1_000_000L

    companion object {
        val DEFAULT = DataCapConfig()
    }
}
