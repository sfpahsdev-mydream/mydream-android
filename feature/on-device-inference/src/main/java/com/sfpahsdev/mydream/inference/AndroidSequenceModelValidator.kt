package com.sfpahsdev.mydream.inference

import android.content.Context
import com.sfpahsdev.mydream.sleep.SleepSession
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class AndroidSequenceModelValidator(
    private val context: Context,
) {
    fun runInputBuilderParityValidation(
        sessions: List<SleepSession>,
    ): InputBuilderParityValidationLog {
        val sample = AndroidSequenceModelAssets.loadParitySample(context)
        val session = sessions.firstOrNull { it.id == sample.sessionId }
            ?: return InputBuilderParityValidationLog(
                timestamp = Instant.now(),
                sampleId = sample.sampleId,
                sessionId = sample.sessionId,
                sessionMatched = false,
                availableSessionCount = sessions.size,
                sequenceMismatchCount = null,
                contextRawMaxAbsDiff = null,
                contextScaledMaxAbsDiff = null,
                tabularRawMaxAbsDiff = null,
                tabularScaledMaxAbsDiff = null,
                passed = false,
                message = "No fetched sleep session matches the fixed parity sample.",
            )

        val sequenceInput = SequenceModelInputBuilder(AndroidSequenceModelAssets.loadScaler(context)).build(
            session = session,
            candidateTime = sample.candidateTime,
            deadlineTime = sample.deadlineTime,
        )
        val tabularInput = TabularModelInputBuilder(AndroidSequenceModelAssets.loadTabularScaler(context)).build(
            session = session,
            candidateTime = sample.candidateTime,
            deadlineTime = sample.deadlineTime,
        )
        val expectedTabularRaw = requireNotNull(sample.tabularRaw28) {
            "Parity sample is missing tabular_raw_28."
        }
        val expectedTabularScaled = requireNotNull(sample.tabularScaled28) {
            "Parity sample is missing tabular_scaled_28."
        }

        val sequenceMismatchCount = sequenceInput.stageSequence60m.countMismatches(sample.stageSequence60m)
        val contextRawMaxAbsDiff = sequenceInput.contextRaw22.maxAbsDiff(sample.contextRaw22)
        val contextScaledMaxAbsDiff = sequenceInput.contextScaled22.maxAbsDiff(sample.contextScaled22)
        val tabularRawMaxAbsDiff = tabularInput.rawFeatures28.maxAbsDiff(expectedTabularRaw)
        val tabularScaledMaxAbsDiff = tabularInput.scaledFeatures28.maxAbsDiff(expectedTabularScaled)
        val passed = sequenceMismatchCount == 0 &&
            contextRawMaxAbsDiff <= BUILDER_PARITY_TOLERANCE &&
            contextScaledMaxAbsDiff <= BUILDER_PARITY_TOLERANCE &&
            tabularRawMaxAbsDiff <= BUILDER_PARITY_TOLERANCE &&
            tabularScaledMaxAbsDiff <= BUILDER_PARITY_TOLERANCE

        return InputBuilderParityValidationLog(
            timestamp = Instant.now(),
            sampleId = sample.sampleId,
            sessionId = sample.sessionId,
            sessionMatched = true,
            availableSessionCount = sessions.size,
            sequenceMismatchCount = sequenceMismatchCount,
            contextRawMaxAbsDiff = contextRawMaxAbsDiff,
            contextScaledMaxAbsDiff = contextScaledMaxAbsDiff,
            tabularRawMaxAbsDiff = tabularRawMaxAbsDiff,
            tabularScaledMaxAbsDiff = tabularScaledMaxAbsDiff,
            passed = passed,
            message = if (passed) {
                "Input builders match the fixed parity sample."
            } else {
                "Input builders differ from the fixed parity sample."
            },
        )
    }

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
        return runFixedTabularParityValidation(
            modelAssetPath = TabularModelContract.VALIDATION_MODEL_ASSET,
        )
    }

    fun runMultiSampleTabularParityValidation(): MultiSampleTabularValidationLog {
        return runMultiSampleTabularParityValidation(
            modelAssetPath = TabularModelContract.VALIDATION_MODEL_ASSET,
        )
    }

    fun runFixedFloat16TabularParityValidation(): TabularInferenceValidationLog {
        return runFixedTabularParityValidation(
            modelAssetPath = TabularModelContract.OPTIMIZED_MODEL_ASSET,
        )
    }

    fun runMultiSampleFloat16TabularParityValidation(): MultiSampleTabularValidationLog {
        return runMultiSampleTabularParityValidation(
            modelAssetPath = TabularModelContract.OPTIMIZED_MODEL_ASSET,
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

    fun runRecent30DayDecisionPolicyComparison(
        sessions: List<SleepSession>,
        options: Set<DecisionPolicyOption>,
    ): MultiSampleDecisionPolicyComparisonLog {
        val zone = ZoneId.of("Asia/Seoul")
        val recentSessions = sessions
            .filter { it.stages.isNotEmpty() }
            .let { nonEmptySessions ->
                val latestDate = nonEmptySessions
                    .maxOfOrNull { it.endTime.atZone(zone).toLocalDate() }
                    ?: error("No sleep sessions with stages found.")
                val startDate = latestDate.minusDays(RECENT_POLICY_WINDOW_DAYS - 1L)
                nonEmptySessions.filter { session ->
                    val date = session.endTime.atZone(zone).toLocalDate()
                    date in startDate..latestDate
                }
            }
            .sortedBy { it.endTime }
        require(recentSessions.isNotEmpty()) { "No recent sleep sessions found." }

        val resultsByOption = DecisionPolicyOption.entries.associateWith { mutableListOf<DecisionPolicyResult>() }
        var candidateCount = 0
        val sequenceScaler = AndroidSequenceModelAssets.loadScaler(context)
        val tabularScaler = AndroidSequenceModelAssets.loadTabularScaler(context)
        val sequenceBuilder = SequenceModelInputBuilder(sequenceScaler)
        val tabularBuilder = TabularModelInputBuilder(tabularScaler)

        TfliteSequenceModelRunner(context, SequenceModelContract.VALIDATION_MODEL_ASSET).use { sequenceRunner ->
            TfliteTabularModelRunner(context, TabularModelContract.VALIDATION_MODEL_ASSET).use { tabularRunner ->
                recentSessions.forEach { session ->
                    val deadlineTime = targetDeadlineForSession(session, zone)
                    for (minuteBeforeDeadline in SEARCH_WINDOW_MINUTES downTo 0) {
                        val candidateTime = deadlineTime.minus(Duration.ofMinutes(minuteBeforeDeadline.toLong()))
                        val sequenceInput = sequenceBuilder.build(
                            session = session,
                            candidateTime = candidateTime,
                            deadlineTime = deadlineTime,
                        )
                        val tabularInput = tabularBuilder.build(
                            session = session,
                            candidateTime = candidateTime,
                            deadlineTime = deadlineTime,
                        )
                        val gruScore = sequenceRunner.predict(sequenceInput)
                        val tabularScore = tabularRunner.predict(tabularInput)
                        val input = DecisionPolicyInput(
                            gruScore = gruScore,
                            tabularScore = tabularScore,
                            minutesBeforeDeadline = sequenceInput.contextRaw22.getOrElse(1) {
                                minuteBeforeDeadline.toFloat()
                            },
                            sequenceUnknownRatio = sequenceInput.contextRaw22.getOrElse(18) { 1f },
                        )
                        DecisionPolicyEvaluator.evaluateAll(options, input).forEach { result ->
                            resultsByOption.getValue(result.option) += result
                        }
                        candidateCount += 1
                    }
                }
            }
        }

        return MultiSampleDecisionPolicyComparisonLog(
            timestamp = Instant.now(),
            modelFile = SequenceModelContract.VALIDATION_MODEL_ASSET,
            modelVersion = SequenceModelContract.SELECTED_MODEL,
            sampleCount = candidateCount,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
            summaries = DecisionPolicyOption.entries
                .filter { option -> option in options }
                .map { option -> resultsByOption.getValue(option).toSummary(option) },
            sourceLabel = "recent_${RECENT_POLICY_WINDOW_DAYS}_days_target_wake_policy",
            sessionCount = recentSessions.size,
            candidateCount = candidateCount,
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

    fun runFixedAlarmWindowEvaluation(
        sessions: List<SleepSession>,
    ): AlarmWindowEvaluationLog {
        val sample = AndroidSequenceModelAssets.loadParitySample(context)
        val session = sessions.firstOrNull { it.id == sample.sessionId }
            ?: error("No fetched sleep session matches the fixed parity sample.")
        return runAlarmWindowEvaluation(
            session = session,
            deadlineTime = sample.deadlineTime,
            sourceLabel = "fixed_parity_session",
            deadlinePolicy = "parity_sample_deadline",
        )
    }

    fun runLatestSessionAlarmWindowEvaluation(
        sessions: List<SleepSession>,
    ): AlarmWindowEvaluationLog {
        val session = sessions
            .filter { it.stages.isNotEmpty() }
            .maxByOrNull { it.endTime }
            ?: error("No loaded sleep session with stages is available.")
        val zone = ZoneId.of("Asia/Seoul")
        return runAlarmWindowEvaluation(
            session = session,
            deadlineTime = targetDeadlineForSession(session, zone),
            sourceLabel = "latest_loaded_sleep_session",
            deadlinePolicy = "fixed_weekday_weekend_deadline",
        )
    }

    private fun runAlarmWindowEvaluation(
        session: SleepSession,
        deadlineTime: Instant,
        sourceLabel: String,
        deadlinePolicy: String,
    ): AlarmWindowEvaluationLog {
        val sequenceScaler = AndroidSequenceModelAssets.loadScaler(context)
        val tabularScaler = AndroidSequenceModelAssets.loadTabularScaler(context)
        val sequenceBuilder = SequenceModelInputBuilder(sequenceScaler)
        val tabularBuilder = TabularModelInputBuilder(tabularScaler)
        val candidates = mutableListOf<AlarmWindowCandidateResult>()

        TfliteSequenceModelRunner(context, SequenceModelContract.VALIDATION_MODEL_ASSET).use { sequenceRunner ->
            TfliteTabularModelRunner(context, TabularModelContract.VALIDATION_MODEL_ASSET).use { tabularRunner ->
                for (minuteBeforeDeadline in SEARCH_WINDOW_MINUTES downTo 0) {
                    val candidateTime = deadlineTime.minus(Duration.ofMinutes(minuteBeforeDeadline.toLong()))
                    val sequenceInput = sequenceBuilder.build(
                        session = session,
                        candidateTime = candidateTime,
                        deadlineTime = deadlineTime,
                    )
                    val tabularInput = tabularBuilder.build(
                        session = session,
                        candidateTime = candidateTime,
                        deadlineTime = deadlineTime,
                    )
                    val gruScore = sequenceRunner.predict(sequenceInput)
                    val tabularScore = tabularRunner.predict(tabularInput)
                    val policyInput = DecisionPolicyInput(
                        gruScore = gruScore,
                        tabularScore = tabularScore,
                        minutesBeforeDeadline = sequenceInput.contextRaw22.getOrElse(1) { minuteBeforeDeadline.toFloat() },
                        sequenceUnknownRatio = sequenceInput.contextRaw22.getOrElse(18) { 1f },
                    )
                    val decision = DecisionPolicyEvaluator.evaluate(
                        option = DecisionPolicyOption.GRU_TABULAR,
                        input = policyInput,
                    )
                    candidates += AlarmWindowCandidateResult(
                        candidateTime = candidateTime,
                        minutesBeforeDeadline = policyInput.minutesBeforeDeadline,
                        gruScore = gruScore,
                        tabularScore = tabularScore,
                        result = decision,
                    )
                }
            }
        }

        val selected = candidates
            .filter { it.candidateTime < deadlineTime && it.result.decision == AlarmDecision.SMART_WAKE }
            .maxByOrNull { it.candidateTime }
        val fallback = selected == null
        val selectedCandidate = selected ?: candidates.last()
        val topCandidates = candidates
            .sortedWith(
                compareByDescending<AlarmWindowCandidateResult> { it.result.score ?: Float.NEGATIVE_INFINITY }
                    .thenByDescending { it.candidateTime },
            )
            .take(TOP_ALARM_WINDOW_CANDIDATE_COUNT)
            .map { it.toSummary() }
        val lateWindowBestCandidate = candidates
            .filter { it.minutesBeforeDeadline <= LATE_WINDOW_MINUTES }
            .maxWithOrNull(
                compareBy<AlarmWindowCandidateResult> { it.result.score ?: Float.NEGATIVE_INFINITY }
                    .thenBy { it.candidateTime },
            )
            ?.toSummary()

        return AlarmWindowEvaluationLog(
            timestamp = Instant.now(),
            sessionId = session.id,
            sourceLabel = sourceLabel,
            deadlinePolicy = deadlinePolicy,
            deadlineTime = deadlineTime,
            candidateCount = candidates.size,
            smartWakeCount = candidates.count { it.result.decision == AlarmDecision.SMART_WAKE },
            waitCount = candidates.count { it.result.decision == AlarmDecision.WAIT },
            fallbackUsed = fallback,
            selectedAlarmTime = if (fallback) deadlineTime else selectedCandidate.candidateTime,
            selectedMinutesBeforeDeadline = if (fallback) 0f else selectedCandidate.minutesBeforeDeadline,
            selectedGruScore = if (fallback) null else selectedCandidate.gruScore,
            selectedTabularScore = if (fallback) null else selectedCandidate.tabularScore,
            selectedCombinedScore = if (fallback) null else selectedCandidate.result.score,
            selectedDecision = if (fallback) AlarmDecision.FALLBACK_WAKE else selectedCandidate.result.decision,
            selectedReason = if (fallback) AlarmDecisionReason.DEADLINE_REACHED else selectedCandidate.result.reason,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
            scoringRecipe = SequenceModelContract.SCORING_RECIPE,
            topCandidates = topCandidates,
            lateWindowBestCandidate = lateWindowBestCandidate,
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

    private fun runFixedTabularParityValidation(
        modelAssetPath: String = TabularModelContract.VALIDATION_MODEL_ASSET,
    ): TabularInferenceValidationLog {
        val sample = AndroidSequenceModelAssets.loadParitySample(context)
        val input = sample.toTabularModelInput()
        val score = TfliteTabularModelRunner(context, modelAssetPath).use { runner ->
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
            modelFile = modelAssetPath,
            modelVersion = TabularModelContract.SELECTED_MODEL,
            scalerVersion = TabularModelContract.SCALER_ASSET,
            threshold = SequenceModelContract.SCORING_THRESHOLD,
        )
    }

    private fun runMultiSampleTabularParityValidation(
        modelAssetPath: String,
    ): MultiSampleTabularValidationLog {
        val samples = AndroidSequenceModelAssets.loadParitySamples(context)
        require(samples.isNotEmpty()) { "No parity samples found." }

        val diffs = mutableListOf<Float>()
        var thresholdFlipCount = 0
        TfliteTabularModelRunner(context, modelAssetPath).use { runner ->
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
            modelFile = modelAssetPath,
            modelVersion = TabularModelContract.SELECTED_MODEL,
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

    private fun IntArray.countMismatches(other: IntArray): Int {
        require(size == other.size) { "Stage sequence sizes do not match." }
        return indices.count { index -> this[index] != other[index] }
    }

    private fun FloatArray.maxAbsDiff(other: FloatArray): Float {
        require(size == other.size) { "Feature vector sizes do not match." }
        return indices.maxOfOrNull { index -> abs(this[index] - other[index]) } ?: 0f
    }

    private data class AlarmWindowCandidateResult(
        val candidateTime: Instant,
        val minutesBeforeDeadline: Float,
        val gruScore: Float,
        val tabularScore: Float,
        val result: DecisionPolicyResult,
    ) {
        fun toSummary(): AlarmWindowCandidateSummary =
            AlarmWindowCandidateSummary(
                candidateTime = candidateTime,
                minutesBeforeDeadline = minutesBeforeDeadline,
                gruScore = gruScore,
                tabularScore = tabularScore,
                combinedScore = result.score,
                decision = result.decision,
                reason = result.reason,
            )
    }

    private fun targetDeadlineForSession(
        session: SleepSession,
        zone: ZoneId,
    ): Instant {
        val wakeDate = session.endTime.atZone(zone).toLocalDate()
        val wakeHour = when (wakeDate.dayOfWeek.value) {
            6, 7 -> WEEKEND_WAKE_HOUR
            else -> WEEKDAY_WAKE_HOUR
        }
        return wakeDate
            .atTime(LocalTime.of(wakeHour, 0))
            .atZone(zone)
            .toInstant()
    }

    private companion object {
        const val BUILDER_PARITY_TOLERANCE = 0.0001f
        const val SEARCH_WINDOW_MINUTES = 30
        const val LATE_WINDOW_MINUTES = 10f
        const val TOP_ALARM_WINDOW_CANDIDATE_COUNT = 3
        const val RECENT_POLICY_WINDOW_DAYS = 30
        const val WEEKDAY_WAKE_HOUR = 7
        const val WEEKEND_WAKE_HOUR = 9
    }
}
