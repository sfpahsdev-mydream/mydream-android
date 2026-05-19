package com.sfpahsdev.mydream.inference

import android.content.Context
import com.sfpahsdev.mydream.sleep.SleepSession
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class AndroidSequenceModelValidator(
    private val context: Context,
) {
    fun runFixedParityValidation(): AndroidInferenceValidationLog {
        return runFixedParityValidation(
            modelAssetPath = SequenceModelContract.VALIDATION_MODEL_ASSET,
        )
    }

    fun runFixedFloat16ParityValidation(): AndroidInferenceValidationLog {
        return runFixedParityValidation(
            modelAssetPath = SequenceModelContract.OPTIMIZED_MODEL_ASSET,
        )
    }

    fun runMultiSampleFloat32ParityValidation(): MultiSampleParityValidationLog {
        return runMultiSampleParityValidation(
            modelAssetPath = SequenceModelContract.VALIDATION_MODEL_ASSET,
        )
    }

    fun runMultiSampleFloat16ParityValidation(): MultiSampleParityValidationLog {
        return runMultiSampleParityValidation(
            modelAssetPath = SequenceModelContract.OPTIMIZED_MODEL_ASSET,
        )
    }

    fun runFixedTabularParityValidation(): TabularInferenceValidationLog {
        val sample = AndroidSequenceModelAssets.loadParitySample(context)
        val input = sample.toTabularModelInput()
        val score = TfliteTabularModelRunner(context, TabularModelContract.VALIDATION_MODEL_ASSET).use { runner ->
            runner.predict(input)
        }
        val expected = sample.expectedTabularScore

        return TabularInferenceValidationLog(
            timestamp = Instant.now(),
            sessionId = input.sessionId,
            candidateTime = input.candidateTime,
            deadlineTime = input.deadlineTime,
            rawFeatures28 = input.rawFeatures28,
            scaledFeatures28 = input.scaledFeatures28,
            tabularScoreAndroid = score,
            tabularScoreServerExpected = expected,
            absDiff = expected?.let { abs(score - it) },
            modelFile = TabularModelContract.VALIDATION_MODEL_ASSET,
            modelVersion = TabularModelContract.SELECTED_MODEL,
            scalerVersion = TabularModelContract.SCALER_ASSET,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
        )
    }

    fun runMultiSampleTabularParityValidation(): MultiSampleTabularValidationLog {
        val samples = AndroidSequenceModelAssets.loadParitySamples(context)
        require(samples.isNotEmpty()) { "No parity samples found." }

        val diffs = mutableListOf<Float>()
        var thresholdFlipCount = 0
        TfliteTabularModelRunner(context, TabularModelContract.VALIDATION_MODEL_ASSET).use { runner ->
            samples.forEach { sample ->
                val score = runner.predict(sample.toTabularModelInput())
                val expected = requireNotNull(sample.expectedTabularScore) {
                    "Parity sample is missing expected_tabular_score."
                }
                diffs += abs(score - expected)
                val androidDecision = score >= SequenceModelContract.SCORING_THRESHOLD
                val expectedDecision = expected >= SequenceModelContract.SCORING_THRESHOLD
                if (androidDecision != expectedDecision) {
                    thresholdFlipCount += 1
                }
            }
        }

        return MultiSampleTabularValidationLog(
            timestamp = Instant.now(),
            modelFile = TabularModelContract.VALIDATION_MODEL_ASSET,
            modelVersion = TabularModelContract.SELECTED_MODEL,
            sampleCount = samples.size,
            meanAbsDiff = diffs.average().toFloat(),
            maxAbsDiff = diffs.maxOrNull() ?: 0f,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
            thresholdFlipCount = thresholdFlipCount,
        )
    }

    fun runMultiSampleDecisionPolicyComparison(
        options: Set<DecisionPolicyOption>,
    ): MultiSampleDecisionPolicyComparisonLog {
        val samples = AndroidSequenceModelAssets.loadParitySamples(context)
        require(samples.isNotEmpty()) { "No parity samples found." }

        val resultsByOption = DecisionPolicyOption.entries.associateWith { mutableListOf<DecisionPolicyResult>() }
        TfliteSequenceModelRunner(context, SequenceModelContract.VALIDATION_MODEL_ASSET).use { sequenceRunner ->
            TfliteTabularModelRunner(context, TabularModelContract.VALIDATION_MODEL_ASSET).use { tabularRunner ->
                samples.forEach { sample ->
                    val gruScore = sequenceRunner.predict(sample.toModelInput())
                    val tabularScore = tabularRunner.predict(sample.toTabularModelInput())
                    val input = DecisionPolicyInput(
                        gruScore = gruScore,
                        tabularScore = tabularScore,
                        minutesBeforeDeadline = sample.contextRaw22.getOrElse(1) { 0f },
                        sequenceUnknownRatio = sample.contextRaw22.getOrElse(18) { 1f },
                    )
                    DecisionPolicyEvaluator.evaluateAll(options, input).forEach { result ->
                        resultsByOption.getValue(result.option) += result
                    }
                }
            }
        }

        return MultiSampleDecisionPolicyComparisonLog(
            timestamp = Instant.now(),
            modelFile = SequenceModelContract.VALIDATION_MODEL_ASSET,
            modelVersion = SequenceModelContract.SELECTED_MODEL,
            sampleCount = samples.size,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
            summaries = DecisionPolicyOption.entries
                .filter { option -> option in options }
                .map { option -> resultsByOption.getValue(option).toSummary(option) },
        )
    }

    private fun runFixedParityValidation(
        modelAssetPath: String,
    ): AndroidInferenceValidationLog {
        val sample = AndroidSequenceModelAssets.loadParitySample(context)
        val input = sample.toModelInput()
        val score = TfliteSequenceModelRunner(context, modelAssetPath).use { runner ->
            runner.predict(input)
        }

        return AndroidInferenceValidationLog(
            timestamp = Instant.now(),
            sessionId = input.sessionId,
            candidateTime = input.candidateTime,
            deadlineTime = input.deadlineTime,
            stageSequence60m = input.stageSequence60m,
            contextRaw22 = input.contextRaw22,
            contextScaled22 = input.contextScaled22,
            gruScoreAndroid = score,
            gruScoreServerExpected = sample.expectedGruScore,
            tabularScoreServerExpected = sample.expectedTabularScore,
            absDiff = abs(score - sample.expectedGruScore),
            modelFile = modelAssetPath,
            modelVersion = sample.modelVersion,
            scalerVersion = SequenceModelContract.SCALER_ASSET,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
        )
    }

    fun runSmokeValidation(
        session: SleepSession,
        expectedServerScore: Float? = null,
    ): AndroidInferenceValidationLog {
        val deadlineTime = session.endTime
        val candidateTime = deadlineTime.minus(Duration.ofMinutes(1))
        val scaler = AndroidSequenceModelAssets.loadScaler(context)
        val input = SequenceModelInputBuilder(scaler).build(
            session = session,
            candidateTime = candidateTime,
            deadlineTime = deadlineTime,
        )
        val score = TfliteSequenceModelRunner(context).use { runner ->
            runner.predict(input)
        }

        return AndroidInferenceValidationLog(
            timestamp = Instant.now(),
            sessionId = input.sessionId,
            candidateTime = input.candidateTime,
            deadlineTime = input.deadlineTime,
            stageSequence60m = input.stageSequence60m,
            contextRaw22 = input.contextRaw22,
            contextScaled22 = input.contextScaled22,
            gruScoreAndroid = score,
            gruScoreServerExpected = expectedServerScore,
            tabularScoreServerExpected = null,
            absDiff = expectedServerScore?.let { abs(score - it) },
            modelFile = SequenceModelContract.VALIDATION_MODEL_ASSET,
            modelVersion = SequenceModelContract.SELECTED_MODEL,
            scalerVersion = SequenceModelContract.SCALER_ASSET,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
        )
    }

    private fun runMultiSampleParityValidation(
        modelAssetPath: String,
    ): MultiSampleParityValidationLog {
        val samples = AndroidSequenceModelAssets.loadParitySamples(context)
        require(samples.isNotEmpty()) { "No parity samples found." }

        val diffs = mutableListOf<Float>()
        var thresholdFlipCount = 0
        TfliteSequenceModelRunner(context, modelAssetPath).use { runner ->
            samples.forEach { sample ->
                val score = runner.predict(sample.toModelInput())
                val expected = sample.expectedGruScore
                diffs += abs(score - expected)
                val androidDecision = score >= SequenceModelContract.SCORING_THRESHOLD
                val expectedDecision = expected >= SequenceModelContract.SCORING_THRESHOLD
                if (androidDecision != expectedDecision) {
                    thresholdFlipCount += 1
                }
            }
        }

        return MultiSampleParityValidationLog(
            timestamp = Instant.now(),
            modelFile = modelAssetPath,
            modelVersion = SequenceModelContract.SELECTED_MODEL,
            sampleCount = samples.size,
            meanAbsDiff = diffs.average().toFloat(),
            maxAbsDiff = diffs.maxOrNull() ?: 0f,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
            thresholdFlipCount = thresholdFlipCount,
        )
    }

    private fun List<DecisionPolicyResult>.toSummary(
        option: DecisionPolicyOption,
    ): MultiSampleDecisionPolicySummary {
        val scores = mapNotNull { it.score }
        return MultiSampleDecisionPolicySummary(
            option = option,
            availableScoreCount = scores.size,
            meanScore = scores.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            smartWakeCount = count { it.decision == AlarmDecision.SMART_WAKE },
            waitCount = count { it.decision == AlarmDecision.WAIT },
            skipTooEarlyCount = count { it.decision == AlarmDecision.SKIP_TOO_EARLY },
            skipUnknownTooHighCount = count { it.decision == AlarmDecision.SKIP_UNKNOWN_TOO_HIGH },
            notAvailableCount = count { it.decision == AlarmDecision.NOT_AVAILABLE },
        )
    }
}
