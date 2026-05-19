package com.sfpahsdev.mydream.inference

import com.sfpahsdev.mydream.sleep.SleepStageType

object SequenceModelContract {
    const val WINDOW_MINUTES = 60
    const val RECENT_CONTEXT_MINUTES = 30
    const val SELECTED_MODEL = "gru64_dense32_dropout00"
    const val SCORING_RECIPE = "combined_gru_50_tab_50"
    const val SCORING_THRESHOLD = 0.55f
    const val ASSET_DIR = "mydream_sequence_gru64_dense32_dropout00"
    const val VALIDATION_MODEL_ASSET = "$ASSET_DIR/sequence_model_float32.tflite"
    const val OPTIMIZED_MODEL_ASSET = "$ASSET_DIR/sequence_model_float16.tflite"
    const val SCALER_ASSET = "$ASSET_DIR/context_scaler.json"
    const val MANIFEST_ASSET = "$ASSET_DIR/tflite_manifest.json"
    const val METRICS_ASSET = "$ASSET_DIR/sequence_metrics.json"
    const val PARITY_SAMPLE_ASSET = "$ASSET_DIR/parity_sample.json"
    const val PARITY_SAMPLES_ASSET = "$ASSET_DIR/parity_samples.json"

    val contextColumns: List<String> = listOf(
        "elapsed_sleep_minutes",
        "minutes_before_deadline",
        "time_of_day_sin",
        "time_of_day_cos",
        "target_wake_hour_sin",
        "target_wake_hour_cos",
        "minutes_since_stage_start",
        "minutes_since_last_deep",
        "deep_cycle_position",
        "recent_30m_awake_minutes",
        "recent_30m_light_minutes",
        "recent_30m_deep_minutes",
        "recent_30m_rem_minutes",
        "recent_30m_unknown_minutes",
        "sequence_awake_ratio",
        "sequence_light_ratio",
        "sequence_deep_ratio",
        "sequence_rem_ratio",
        "sequence_unknown_ratio",
        "sequence_stage_transition_count",
        "sequence_known_stage_transition_count",
        "sequence_known_ratio",
    )

    fun stageId(type: SleepStageType): Int = when (type) {
        SleepStageType.Unknown -> 0
        SleepStageType.Awake -> 1
        SleepStageType.Light -> 2
        SleepStageType.Deep -> 3
        SleepStageType.Rem -> 4
    }
}
