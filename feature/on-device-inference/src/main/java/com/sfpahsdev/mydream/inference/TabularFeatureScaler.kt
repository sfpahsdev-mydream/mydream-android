package com.sfpahsdev.mydream.inference

data class TabularFeatureScaler(
    val columns: List<String>,
    val mean: FloatArray,
    val std: FloatArray,
) {
    init {
        require(columns == TabularModelContract.featureColumns) {
            "Tabular scaler columns do not match the Android tabular model contract."
        }
        require(mean.size == columns.size) {
            "Tabular scaler mean size does not match column count."
        }
        require(std.size == columns.size) {
            "Tabular scaler std size does not match column count."
        }
    }

    fun scale(raw: FloatArray): FloatArray {
        require(raw.size == columns.size) {
            "Raw tabular feature count does not match column count."
        }
        return FloatArray(raw.size) { index ->
            val denominator = std[index].takeIf { it != 0f } ?: 1f
            (raw[index] - mean[index]) / denominator
        }
    }
}
