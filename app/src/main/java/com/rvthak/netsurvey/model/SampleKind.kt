package com.rvthak.netsurvey.model

import kotlinx.serialization.Serializable

/** Raw time-series sample kind (SPEC §7). */
@Serializable
enum class SampleKind { SIGNAL, PROBE }
