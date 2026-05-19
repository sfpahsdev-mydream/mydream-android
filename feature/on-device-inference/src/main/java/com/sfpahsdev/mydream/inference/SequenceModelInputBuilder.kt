package com.sfpahsdev.mydream.inference

import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStage
import com.sfpahsdev.mydream.sleep.SleepStageType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SequenceModelInputBuilder(
    private val scaler: SequenceContextScaler,
    private val localZone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    fun build(
        session: SleepSession,
        candidateTime: Instant,
        deadlineTime: Instant,
    ): SequenceModelInput {
        val sortedStages = session.stages.sortedBy { it.startTime }
        val stageSequence = buildStageSequence(sortedStages, candidateTime)
        val rawContext = buildRawContext(session, sortedStages, candidateTime, deadlineTime, stageSequence)
        return SequenceModelInput(
            sessionId = session.id,
            candidateTime = candidateTime,
            deadlineTime = deadlineTime,
            stageSequence60m = stageSequence,
            contextRaw22 = rawContext,
            contextScaled22 = scaler.scale(rawContext),
        )
    }

    private fun buildStageSequence(stages: List<SleepStage>, candidateTime: Instant): IntArray =
        IntArray(SequenceModelContract.WINDOW_MINUTES) { index ->
            val minute = candidateTime.minus(Duration.ofMinutes((SequenceModelContract.WINDOW_MINUTES - 1 - index).toLong()))
            SequenceModelContract.stageId(stageAt(stages, minute))
        }

    private fun buildRawContext(
        session: SleepSession,
        stages: List<SleepStage>,
        candidateTime: Instant,
        deadlineTime: Instant,
        stageSequence: IntArray,
    ): FloatArray {
        val currentStage = currentStage(stages, candidateTime)
        val minutesSinceStageStart = currentStage
            ?.let { minutesBetween(it.startTime, candidateTime) }
            ?: 0f
        val recent30m = recentStageMinutes(stages, candidateTime, SequenceModelContract.RECENT_CONTEXT_MINUTES)
        val sequenceRatios = sequenceStageRatios(stageSequence)
        val transitionCounts = transitionCounts(stageSequence)
        val timeOfDay = cyclicTimeFeatures(candidateTime)
        val targetWakeTime = cyclicTimeFeatures(deadlineTime)

        return floatArrayOf(
            minutesBetween(session.startTime, candidateTime),
            minutesBetween(candidateTime, deadlineTime),
            timeOfDay.first,
            timeOfDay.second,
            targetWakeTime.first,
            targetWakeTime.second,
            minutesSinceStageStart,
            minutesSinceLastDeep(stages, candidateTime),
            deepCyclePosition(stages, candidateTime).toFloat(),
            recent30m.getValue(SleepStageType.Awake),
            recent30m.getValue(SleepStageType.Light),
            recent30m.getValue(SleepStageType.Deep),
            recent30m.getValue(SleepStageType.Rem),
            recent30m.getValue(SleepStageType.Unknown),
            sequenceRatios.getValue(SleepStageType.Awake),
            sequenceRatios.getValue(SleepStageType.Light),
            sequenceRatios.getValue(SleepStageType.Deep),
            sequenceRatios.getValue(SleepStageType.Rem),
            sequenceRatios.getValue(SleepStageType.Unknown),
            transitionCounts.total.toFloat(),
            transitionCounts.knownOnly.toFloat(),
            sequenceKnownRatio(stageSequence),
        )
    }

    private fun currentStage(stages: List<SleepStage>, instant: Instant): SleepStage? =
        stages.firstOrNull { it.startTime <= instant && instant < it.endTime }

    private fun stageAt(stages: List<SleepStage>, instant: Instant): SleepStageType =
        currentStage(stages, instant)?.type ?: SleepStageType.Unknown

    private fun recentStageMinutes(
        stages: List<SleepStage>,
        candidateTime: Instant,
        windowMinutes: Int,
    ): Map<SleepStageType, Float> {
        val windowStart = candidateTime.minus(Duration.ofMinutes(windowMinutes.toLong()))
        val totals = mutableStageMap()
        stages.forEach { stage ->
            val overlapStart = maxOf(stage.startTime, windowStart)
            val overlapEnd = minOf(stage.endTime, candidateTime)
            if (overlapEnd > overlapStart) {
                totals[stage.type] = totals.getValue(stage.type) + minutesBetween(overlapStart, overlapEnd)
            }
        }
        return totals
    }

    private fun sequenceStageRatios(stageSequence: IntArray): Map<SleepStageType, Float> {
        val totals = mutableStageMap()
        stageSequence.forEach { stageId ->
            val type = stageType(stageId)
            totals[type] = totals.getValue(type) + 1f
        }
        return totals.mapValues { (_, count) -> count / stageSequence.size }
    }

    private fun transitionCounts(stageSequence: IntArray): TransitionCounts {
        var total = 0
        var knownOnly = 0
        for (index in 1 until stageSequence.size) {
            val previous = stageSequence[index - 1]
            val current = stageSequence[index]
            if (previous != current) {
                total += 1
                if (previous != UNKNOWN_STAGE_ID && current != UNKNOWN_STAGE_ID) {
                    knownOnly += 1
                }
            }
        }
        return TransitionCounts(total = total, knownOnly = knownOnly)
    }

    private fun sequenceKnownRatio(stageSequence: IntArray): Float {
        val knownCount = stageSequence.count { it != UNKNOWN_STAGE_ID }
        return knownCount.toFloat() / stageSequence.size
    }

    private fun minutesSinceLastDeep(stages: List<SleepStage>, candidateTime: Instant): Float {
        val previousDeepEnd = stages
            .filter { it.type == SleepStageType.Deep && it.endTime <= candidateTime }
            .maxOfOrNull { it.endTime }
            ?: return -1f
        return minutesBetween(previousDeepEnd, candidateTime)
    }

    private fun deepCyclePosition(stages: List<SleepStage>, candidateTime: Instant): Int =
        stages.count { it.type == SleepStageType.Deep && it.startTime < candidateTime }

    private fun cyclicTimeFeatures(instant: Instant): Pair<Float, Float> {
        val local = instant.atZone(localZone)
        val minute = local.hour * MINUTES_PER_HOUR + local.minute + local.second / SECONDS_PER_MINUTE
        val radians = 2.0 * PI * minute / MINUTES_PER_DAY
        return sin(radians).toFloat() to cos(radians).toFloat()
    }

    private fun stageType(stageId: Int): SleepStageType = when (stageId) {
        1 -> SleepStageType.Awake
        2 -> SleepStageType.Light
        3 -> SleepStageType.Deep
        4 -> SleepStageType.Rem
        else -> SleepStageType.Unknown
    }

    private fun mutableStageMap(): MutableMap<SleepStageType, Float> = mutableMapOf(
        SleepStageType.Awake to 0f,
        SleepStageType.Light to 0f,
        SleepStageType.Deep to 0f,
        SleepStageType.Rem to 0f,
        SleepStageType.Unknown to 0f,
    )

    private fun minutesBetween(start: Instant, end: Instant): Float =
        Duration.between(start, end).toMillis() / MILLIS_PER_MINUTE

    private data class TransitionCounts(
        val total: Int,
        val knownOnly: Int,
    )

    private companion object {
        const val UNKNOWN_STAGE_ID = 0
        const val MINUTES_PER_HOUR = 60
        const val SECONDS_PER_MINUTE = 60.0
        const val MINUTES_PER_DAY = 1440.0
        const val MILLIS_PER_MINUTE = 60000f
    }
}
