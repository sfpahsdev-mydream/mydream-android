package com.sfpahsdev.mydream.inference

import java.time.Instant

data class TabularModelInput(
    val sessionId: String,
    val candidateTime: Instant,
    val deadlineTime: Instant,
    val rawFeatures28: FloatArray,
    val scaledFeatures28: FloatArray,
) {
    init {
        require(rawFeatures28.size == TabularModelContract.featureColumns.size) {
            "Raw tabular input must contain ${TabularModelContract.featureColumns.size} values."
        }
        require(scaledFeatures28.size == TabularModelContract.featureColumns.size) {
            "Scaled tabular input must contain ${TabularModelContract.featureColumns.size} values."
        }
    }
}
