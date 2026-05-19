package com.sfpahsdev.mydream.inference

object TabularModelContract {
    const val SELECTED_MODEL = "tabular_mlp"
    const val ASSET_DIR = "mydream_tabular_tflite"
    const val VALIDATION_MODEL_ASSET = "$ASSET_DIR/tabular_model_float32.tflite"
    const val OPTIMIZED_MODEL_ASSET = "$ASSET_DIR/tabular_model_float16.tflite"
    const val SCALER_ASSET = "$ASSET_DIR/tabular_feature_scaler.json"
    const val MANIFEST_ASSET = "$ASSET_DIR/tabular_tflite_manifest.json"

    val featureColumns: List<String> = listOf(
        "minutes_before_deadline",
        "elapsed_sleep_minutes",
        "target_wake_hour_sin",
        "target_wake_hour_cos",
        "time_of_day_sin",
        "time_of_day_cos",
        "minutes_since_stage_start",
        "minutes_since_last_deep",
        "deep_cycle_position",
        "recent_30m_awake_minutes",
        "recent_30m_light_minutes",
        "recent_30m_deep_minutes",
        "recent_30m_rem_minutes",
        "stage_at_candidate_Awake",
        "stage_at_candidate_Light",
        "stage_at_candidate_Rem",
        "previous_stage_Awake",
        "previous_stage_Deep",
        "previous_stage_Light",
        "previous_stage_Rem",
        "previous_stage_Unknown",
        "day_of_week_0",
        "day_of_week_1",
        "day_of_week_2",
        "day_of_week_3",
        "day_of_week_4",
        "day_of_week_5",
        "day_of_week_6",
    )
}
