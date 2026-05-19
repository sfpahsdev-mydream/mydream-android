package com.sfpahsdev.mydream.inference

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONObject

object AndroidSequenceModelAssets {
    fun loadScaler(
        context: Context,
        assetPath: String = SequenceModelContract.SCALER_ASSET,
    ): SequenceContextScaler {
        val json = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
        val columnsJson = json.getJSONArray("columns")
        val meanJson = json.getJSONArray("mean")
        val stdJson = json.getJSONArray("std")

        val columns = List(columnsJson.length()) { index -> columnsJson.getString(index) }
        require(columns == SequenceModelContract.contextColumns) {
            "Scaler columns do not match the Android sequence model contract."
        }

        return SequenceContextScaler(
            columns = columns,
            mean = FloatArray(meanJson.length()) { index -> meanJson.getDouble(index).toFloat() },
            std = FloatArray(stdJson.length()) { index -> stdJson.getDouble(index).toFloat() },
        )
    }

    fun loadModelBuffer(
        context: Context,
        assetPath: String = SequenceModelContract.VALIDATION_MODEL_ASSET,
    ): ByteBuffer {
        val bytes = context.assets.open(assetPath).use { input -> input.readBytes() }
        return ByteBuffer
            .allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    fun loadTabularScaler(
        context: Context,
        assetPath: String = TabularModelContract.SCALER_ASSET,
    ): TabularFeatureScaler {
        val json = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
        val columnsJson = json.getJSONArray("columns")
        val meanJson = json.getJSONArray("mean")
        val stdJson = json.getJSONArray("std")

        val columns = List(columnsJson.length()) { index -> columnsJson.getString(index) }
        require(columns == TabularModelContract.featureColumns) {
            "Tabular scaler columns do not match the Android tabular model contract."
        }

        return TabularFeatureScaler(
            columns = columns,
            mean = FloatArray(meanJson.length()) { index -> meanJson.getDouble(index).toFloat() },
            std = FloatArray(stdJson.length()) { index -> stdJson.getDouble(index).toFloat() },
        )
    }

    fun loadParitySample(
        context: Context,
        assetPath: String = SequenceModelContract.PARITY_SAMPLE_ASSET,
    ): SequenceModelParitySample {
        val json = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
        return parseParitySample(json)
    }

    fun loadParitySamples(
        context: Context,
        assetPath: String = SequenceModelContract.PARITY_SAMPLES_ASSET,
    ): List<SequenceModelParitySample> {
        val json = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
        val samples = json.getJSONArray("samples")
        return List(samples.length()) { index ->
            parseParitySample(samples.getJSONObject(index))
        }
    }

    private fun parseParitySample(json: JSONObject): SequenceModelParitySample {
        val columns = json.getJSONArray("context_columns")
        val sampleColumns = List(columns.length()) { index -> columns.getString(index) }
        require(sampleColumns == SequenceModelContract.contextColumns) {
            "Parity sample columns do not match the Android sequence model contract."
        }

        return SequenceModelParitySample(
            sampleId = json.getString("sample_id"),
            sessionId = json.getString("session_id"),
            candidateTime = parseInstant(json.getString("candidate_time")),
            deadlineTime = parseInstant(json.getString("deadline_time")),
            stageSequence60m = json.getJSONArray("stage_sequence_60m").toIntArray(),
            contextRaw22 = json.getJSONArray("context_raw_22").toFiniteFloatArray("context_raw_22"),
            contextScaled22 = json.getJSONArray("context_scaled_22").toFiniteFloatArray("context_scaled_22"),
            expectedGruScore = json.getFiniteFloat("expected_gru_score"),
            expectedTabularScore = json.getOptionalFiniteFloat("expected_tabular_score"),
            tabularRaw28 = json.getOptionalFiniteFloatArray("tabular_raw_28"),
            tabularScaled28 = json.getOptionalFiniteFloatArray("tabular_scaled_28"),
            modelVersion = json.getString("model_version"),
            modelFile = json.getString("model_file"),
        )
    }

    private fun org.json.JSONArray.toIntArray(): IntArray =
        IntArray(length()) { index -> getInt(index) }

    private fun org.json.JSONArray.toFiniteFloatArray(name: String): FloatArray =
        FloatArray(length()) { index ->
            val value = getDouble(index).toFloat()
            require(value.isFinite()) { "$name contains a non-finite value at index $index." }
            value
        }

    private fun JSONObject.getFiniteFloat(name: String): Float {
        val value = getDouble(name).toFloat()
        require(value.isFinite()) { "$name contains a non-finite value." }
        return value
    }

    private fun JSONObject.getOptionalFiniteFloat(name: String): Float? {
        if (!has(name) || isNull(name)) {
            return null
        }
        val value = getDouble(name).toFloat()
        require(value.isFinite()) { "$name contains a non-finite value." }
        return value
    }

    private fun JSONObject.getOptionalFiniteFloatArray(name: String): FloatArray? {
        if (!has(name) || isNull(name)) {
            return null
        }
        return getJSONArray(name).toFiniteFloatArray(name)
    }

    private fun parseInstant(value: String): Instant =
        OffsetDateTime.parse(value).toInstant()
}
