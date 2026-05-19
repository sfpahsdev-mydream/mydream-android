package com.sfpahsdev.mydream.inference

import java.time.Instant

data class SequenceModelInput(
    val sessionId: String,
    val candidateTime: Instant,
    val deadlineTime: Instant,
    val stageSequence60m: IntArray,
    val contextRaw22: FloatArray,
    val contextScaled22: FloatArray,
) {
    init {
        require(stageSequence60m.size == SequenceModelContract.WINDOW_MINUTES) {
            "Stage sequence must contain ${SequenceModelContract.WINDOW_MINUTES} values."
        }
        require(contextRaw22.size == SequenceModelContract.contextColumns.size) {
            "Raw context must contain ${SequenceModelContract.contextColumns.size} values."
        }
        require(contextScaled22.size == SequenceModelContract.contextColumns.size) {
            "Scaled context must contain ${SequenceModelContract.contextColumns.size} values."
        }
    }
}
