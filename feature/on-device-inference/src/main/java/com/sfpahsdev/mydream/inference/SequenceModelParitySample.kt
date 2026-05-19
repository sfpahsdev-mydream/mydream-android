package com.sfpahsdev.mydream.inference

import java.time.Instant

data class SequenceModelParitySample(
    val sampleId: String,
    val sessionId: String,
    val candidateTime: Instant,
    val deadlineTime: Instant,
    val stageSequence60m: IntArray,
    val contextRaw22: FloatArray,
    val contextScaled22: FloatArray,
    val expectedGruScore: Float,
    val expectedTabularScore: Float?,
    val tabularRaw28: FloatArray?,
    val tabularScaled28: FloatArray?,
    val modelVersion: String,
    val modelFile: String,
) {
    fun toModelInput(): SequenceModelInput = SequenceModelInput(
        sessionId = sessionId,
        candidateTime = candidateTime,
        deadlineTime = deadlineTime,
        stageSequence60m = stageSequence60m,
        contextRaw22 = contextRaw22,
        contextScaled22 = contextScaled22,
    )

    fun toTabularModelInput(): TabularModelInput {
        val raw = requireNotNull(tabularRaw28) { "Parity sample is missing tabular_raw_28." }
        val scaled = requireNotNull(tabularScaled28) { "Parity sample is missing tabular_scaled_28." }
        return TabularModelInput(
            sessionId = sessionId,
            candidateTime = candidateTime,
            deadlineTime = deadlineTime,
            rawFeatures28 = raw,
            scaledFeatures28 = scaled,
        )
    }
}
