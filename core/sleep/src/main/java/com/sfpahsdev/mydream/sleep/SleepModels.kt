package com.sfpahsdev.mydream.sleep

import java.time.Instant

data class SleepSession(
    val id: String,
    val startTime: Instant,
    val endTime: Instant,
    val stages: List<SleepStage>,
)

data class SleepStage(
    val type: SleepStageType,
    val startTime: Instant,
    val endTime: Instant,
)

enum class SleepStageType {
    Awake,
    Light,
    Deep,
    Rem,
    Unknown,
}
