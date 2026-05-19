package com.sfpahsdev.mydream.inference

enum class DecisionPolicyOption(
    val label: String,
) {
    GRU_ONLY("GRU-only"),
    GRU_DEADLINE("GRU + deadline"),
    GRU_STRICT_DEADLINE_GATE("GRU + strict deadline gate"),
    GRU_UNKNOWN_GATE("GRU + unknown coverage gate"),
    GRU_DEADLINE_UNKNOWN_GATE("GRU + deadline + unknown gate"),
    GRU_TABULAR("GRU + tabular"),
}

data class DecisionPolicyInput(
    val gruScore: Float,
    val tabularScore: Float?,
    val minutesBeforeDeadline: Float,
    val sequenceUnknownRatio: Float,
)

data class DecisionPolicyResult(
    val option: DecisionPolicyOption,
    val score: Float?,
    val decision: AlarmDecision,
    val reason: AlarmDecisionReason,
)

object DecisionPolicyEvaluator {
    private const val SEARCH_WINDOW_MINUTES = 30f
    private const val STRICT_DEADLINE_GATE_MINUTES = 20f
    private const val UNKNOWN_RATIO_LIMIT = 0.3f
    private const val GRU_WEIGHT = 0.8f
    private const val DEADLINE_WEIGHT = 0.2f

    fun evaluate(
        option: DecisionPolicyOption,
        input: DecisionPolicyInput,
    ): DecisionPolicyResult = when (option) {
        DecisionPolicyOption.GRU_ONLY -> scoreOnly(option, input.gruScore)
        DecisionPolicyOption.GRU_DEADLINE -> scoreOnly(option, deadlineScore(input))
        DecisionPolicyOption.GRU_STRICT_DEADLINE_GATE -> strictDeadlineGate(input)
        DecisionPolicyOption.GRU_UNKNOWN_GATE -> unknownGate(input)
        DecisionPolicyOption.GRU_DEADLINE_UNKNOWN_GATE -> deadlineUnknownGate(input)
        DecisionPolicyOption.GRU_TABULAR -> tabularCombined(input)
    }

    fun evaluateAll(
        options: Set<DecisionPolicyOption>,
        input: DecisionPolicyInput,
    ): List<DecisionPolicyResult> = DecisionPolicyOption.entries
        .filter { option -> option in options }
        .map { option -> evaluate(option, input) }

    private fun scoreOnly(
        option: DecisionPolicyOption,
        score: Float,
    ): DecisionPolicyResult {
        val isSmartCandidate = score >= SequenceModelContract.SCORING_THRESHOLD
        return DecisionPolicyResult(
            option = option,
            score = score,
            decision = if (isSmartCandidate) AlarmDecision.SMART_WAKE else AlarmDecision.WAIT,
            reason = if (isSmartCandidate) {
                AlarmDecisionReason.SCORE_ABOVE_THRESHOLD
            } else {
                AlarmDecisionReason.SCORE_BELOW_THRESHOLD
            },
        )
    }

    private fun strictDeadlineGate(input: DecisionPolicyInput): DecisionPolicyResult {
        if (input.minutesBeforeDeadline > STRICT_DEADLINE_GATE_MINUTES) {
            return DecisionPolicyResult(
                option = DecisionPolicyOption.GRU_STRICT_DEADLINE_GATE,
                score = input.gruScore,
                decision = AlarmDecision.SKIP_TOO_EARLY,
                reason = AlarmDecisionReason.OUTSIDE_SEARCH_WINDOW,
            )
        }
        return scoreOnly(DecisionPolicyOption.GRU_STRICT_DEADLINE_GATE, input.gruScore)
    }

    private fun unknownGate(input: DecisionPolicyInput): DecisionPolicyResult {
        if (input.sequenceUnknownRatio > UNKNOWN_RATIO_LIMIT) {
            return DecisionPolicyResult(
                option = DecisionPolicyOption.GRU_UNKNOWN_GATE,
                score = input.gruScore,
                decision = AlarmDecision.SKIP_UNKNOWN_TOO_HIGH,
                reason = AlarmDecisionReason.UNKNOWN_COVERAGE_TOO_HIGH,
            )
        }
        return scoreOnly(DecisionPolicyOption.GRU_UNKNOWN_GATE, input.gruScore)
    }

    private fun deadlineUnknownGate(input: DecisionPolicyInput): DecisionPolicyResult {
        if (input.sequenceUnknownRatio > UNKNOWN_RATIO_LIMIT) {
            return DecisionPolicyResult(
                option = DecisionPolicyOption.GRU_DEADLINE_UNKNOWN_GATE,
                score = deadlineScore(input),
                decision = AlarmDecision.SKIP_UNKNOWN_TOO_HIGH,
                reason = AlarmDecisionReason.UNKNOWN_COVERAGE_TOO_HIGH,
            )
        }
        return scoreOnly(DecisionPolicyOption.GRU_DEADLINE_UNKNOWN_GATE, deadlineScore(input))
    }

    private fun tabularCombined(input: DecisionPolicyInput): DecisionPolicyResult {
        val tabularScore = input.tabularScore
            ?: return DecisionPolicyResult(
                option = DecisionPolicyOption.GRU_TABULAR,
                score = null,
                decision = AlarmDecision.NOT_AVAILABLE,
                reason = AlarmDecisionReason.TABULAR_MODEL_NOT_READY,
            )
        return scoreOnly(
            option = DecisionPolicyOption.GRU_TABULAR,
            score = 0.5f * input.gruScore + 0.5f * tabularScore,
        )
    }

    private fun deadlineScore(input: DecisionPolicyInput): Float {
        val urgency = (1f - input.minutesBeforeDeadline / SEARCH_WINDOW_MINUTES).coerceIn(0f, 1f)
        return GRU_WEIGHT * input.gruScore + DEADLINE_WEIGHT * urgency
    }
}
