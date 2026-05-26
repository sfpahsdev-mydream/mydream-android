package com.sfpahsdev.mydream.inference

import com.sfpahsdev.mydream.sleep.SleepStageType
import java.time.Instant

data class AndroidInferenceValidationLog(
    val timestamp: Instant,
    val sessionId: String,
    val candidateTime: Instant,
    val deadlineTime: Instant,
    val stageSequence60m: IntArray,
    val contextRaw22: FloatArray,
    val contextScaled22: FloatArray,
    val gruScoreAndroid: Float,
    val gruScoreServerExpected: Float?,
    val tabularScoreServerExpected: Float?,
    val absDiff: Float?,
    val modelFile: String,
    val modelVersion: String,
    val scalerVersion: String,
    val threshold: Float,
)

data class AlarmDecisionLog(
    val timestamp: Instant,
    val candidateTime: Instant,
    val targetWakeTime: Instant,
    val minutesBeforeDeadline: Float,
    val currentStage: SleepStageType,
    val stageSequence60m: IntArray,
    val sequenceKnownRatio: Float,
    val sequenceUnknownRatio: Float,
    val gruScore: Float,
    val tabularScore: Float?,
    val combinedScore: Float,
    val threshold: Float,
    val decision: AlarmDecision,
    val reason: AlarmDecisionReason,
)

data class PostAlarmFeedbackLog(
    val actualWakeTime: Instant,
    val stageAtWake: SleepStageType,
    val stageAfterWake10m: SleepStageType?,
    val wasDeepWithin10m: Boolean?,
    val userDismissed: Boolean,
    val userSnoozed: Boolean,
    val userFeedback: String?,
)

data class MultiSampleParityValidationLog(
    val timestamp: Instant,
    val modelFile: String,
    val modelVersion: String,
    val sampleCount: Int,
    val meanAbsDiff: Float,
    val maxAbsDiff: Float,
    val threshold: Float,
    val thresholdFlipCount: Int,
)

data class TabularInferenceValidationLog(
    val timestamp: Instant,
    val sessionId: String,
    val candidateTime: Instant,
    val deadlineTime: Instant,
    val rawFeatures28: FloatArray,
    val scaledFeatures28: FloatArray,
    val tabularScoreAndroid: Float,
    val tabularScoreServerExpected: Float?,
    val absDiff: Float?,
    val modelFile: String,
    val modelVersion: String,
    val scalerVersion: String,
    val threshold: Float,
)

data class MultiSampleTabularValidationLog(
    val timestamp: Instant,
    val modelFile: String,
    val modelVersion: String,
    val sampleCount: Int,
    val meanAbsDiff: Float,
    val maxAbsDiff: Float,
    val threshold: Float,
    val thresholdFlipCount: Int,
)

data class InputBuilderParityValidationLog(
    val timestamp: Instant,
    val sampleId: String,
    val sessionId: String,
    val sessionMatched: Boolean,
    val availableSessionCount: Int,
    val sequenceMismatchCount: Int?,
    val contextRawMaxAbsDiff: Float?,
    val contextScaledMaxAbsDiff: Float?,
    val tabularRawMaxAbsDiff: Float?,
    val tabularScaledMaxAbsDiff: Float?,
    val passed: Boolean,
    val message: String,
)

data class AlarmWindowEvaluationLog(
    val timestamp: Instant,
    val sessionId: String,
    val sourceLabel: String,
    val deadlinePolicy: String,
    val deadlineTime: Instant,
    val candidateCount: Int,
    val smartWakeCount: Int,
    val waitCount: Int,
    val fallbackUsed: Boolean,
    val selectedAlarmTime: Instant,
    val selectedMinutesBeforeDeadline: Float,
    val selectedGruScore: Float?,
    val selectedTabularScore: Float?,
    val selectedCombinedScore: Float?,
    val selectedDecision: AlarmDecision,
    val selectedReason: AlarmDecisionReason,
    val threshold: Float,
    val scoringRecipe: String,
    val topCandidates: List<AlarmWindowCandidateSummary>,
    val lateWindowBestCandidate: AlarmWindowCandidateSummary?,
)

data class AlarmWindowCandidateSummary(
    val candidateTime: Instant,
    val minutesBeforeDeadline: Float,
    val gruScore: Float,
    val tabularScore: Float,
    val combinedScore: Float?,
    val decision: AlarmDecision,
    val reason: AlarmDecisionReason,
)

data class MultiSampleDecisionPolicyComparisonLog(
    val timestamp: Instant,
    val modelFile: String,
    val modelVersion: String,
    val sampleCount: Int,
    val threshold: Float,
    val summaries: List<MultiSampleDecisionPolicySummary>,
    val sourceLabel: String = "fixed_parity_samples",
    val sessionCount: Int? = null,
    val candidateCount: Int? = null,
)

data class MultiSampleDecisionPolicySummary(
    val option: DecisionPolicyOption,
    val availableScoreCount: Int,
    val meanScore: Float?,
    val smartWakeCount: Int,
    val waitCount: Int,
    val skipTooEarlyCount: Int,
    val skipUnknownTooHighCount: Int,
    val notAvailableCount: Int,
)

enum class AlarmDecision {
    SMART_WAKE,
    WAIT,
    FALLBACK_WAKE,
    SKIP_UNKNOWN_TOO_HIGH,
    SKIP_TOO_EARLY,
    SKIP_ALREADY_DEEP,
    NOT_AVAILABLE,
}

enum class AlarmDecisionReason {
    SCORE_ABOVE_THRESHOLD,
    SCORE_BELOW_THRESHOLD,
    DEADLINE_REACHED,
    UNKNOWN_COVERAGE_TOO_HIGH,
    OUTSIDE_SEARCH_WINDOW,
    CURRENT_STAGE_DEEP,
    TABULAR_MODEL_NOT_READY,
}
