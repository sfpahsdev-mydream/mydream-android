package com.sfpahsdev.mydream.inference

data class SequenceContextScaler(
    val columns: List<String>,
    val mean: FloatArray,
    val std: FloatArray,
) {
    init {
        require(columns.size == mean.size) { "Scaler mean size must match columns." }
        require(columns.size == std.size) { "Scaler std size must match columns." }
    }

    fun scale(rawContext: FloatArray): FloatArray {
        require(rawContext.size == columns.size) { "Raw context size must match scaler columns." }
        return FloatArray(rawContext.size) { index ->
            val divisor = if (std[index] == 0f) 1f else std[index]
            (rawContext[index] - mean[index]) / divisor
        }
    }
}
