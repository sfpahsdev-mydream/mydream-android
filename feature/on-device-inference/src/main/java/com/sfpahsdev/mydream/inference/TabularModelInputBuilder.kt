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

class TabularModelInputBuilder(
    private val scaler: TabularFeatureScaler,
    private val localZone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    fun build(
        session: SleepSession,
        candidateTime: Instant,
        deadlineTime: Instant,
    ): TabularModelInput {
        val sortedStages = session.stages.sortedBy { it.startTime }
        val raw = buildRawFeatures(session, sortedStages, candidateTime, deadlineTime)
        return TabularModelInput(
            sessionId = session.id,
            candidateTime = candidateTime,
            deadlineTime = deadlineTime,
            rawFeatures28 = raw,
            scaledFeatures28 = scaler.scale(raw),
        )
    }

    private fun buildRawFeatures(
        session: SleepSession,
        stages: List<SleepStage>,
        candidateTime: Instant,
        deadlineTime: Instant,
    ): FloatArray {
        val currentStageIndex = stages.indexOfFirst { it.startTime <= candidateTime && candidateTime < it.endTime }
        val currentStage = stages.getOrNull(currentStageIndex)?.type ?: SleepStageType.Unknown
        val previousStage = stages.getOrNull(currentStageIndex - 1)?.type ?: SleepStageType.Unknown
        val timeOfDay = cyclicTimeFeatures(candidateTime)
        val targetWakeTime = cyclicTimeFeatures(deadlineTime)
        val recent30m = recentStageMinutes(stages, candidateTime, SequenceModelContract.RECENT_CONTEXT_MINUTES)
        val dayOfWeek = candidateTime.atZone(localZone).dayOfWeek.value - 1

        return floatArrayOf(
            minutesBetween(candidateTime, deadlineTime),
            minutesBetween(session.startTime, candidateTime),
            targetWakeTime.first,
            targetWakeTime.second,
            timeOfDay.first,
            timeOfDay.second,
            stages.getOrNull(currentStageIndex)?.let { minutesBetween(it.startTime, candidateTime) } ?: 0f,
            minutesSinceLastDeep(stages, candidateTime),
            deepCyclePosition(stages, candidateTime).toFloat(),
            recent30m.getValue(SleepStageType.Awake),
            recent30m.getValue(SleepStageType.Light),
            recent30m.getValue(SleepStageType.Deep),
            recent30m.getValue(SleepStageType.Rem),
            oneHot(currentStage, SleepStageType.Awake),
            oneHot(currentStage, SleepStageType.Light),
            oneHot(currentStage, SleepStageType.Rem),
            oneHot(previousStage, SleepStageType.Awake),
            oneHot(previousStage, SleepStageType.Deep),
            oneHot(previousStage, SleepStageType.Light),
            oneHot(previousStage, SleepStageType.Rem),
            oneHot(previousStage, SleepStageType.Unknown),
            oneHot(dayOfWeek, 0),
            oneHot(dayOfWeek, 1),
            oneHot(dayOfWeek, 2),
            oneHot(dayOfWeek, 3),
            oneHot(dayOfWeek, 4),
            oneHot(dayOfWeek, 5),
            oneHot(dayOfWeek, 6),
        )
    }

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

    private fun mutableStageMap(): MutableMap<SleepStageType, Float> = mutableMapOf(
        SleepStageType.Awake to 0f,
        SleepStageType.Light to 0f,
        SleepStageType.Deep to 0f,
        SleepStageType.Rem to 0f,
        SleepStageType.Unknown to 0f,
    )

    private fun minutesBetween(start: Instant, end: Instant): Float =
        Duration.between(start, end).toMillis() / MILLIS_PER_MINUTE

    private fun oneHot(actual: SleepStageType, expected: SleepStageType): Float =
        if (actual == expected) 1f else 0f

    private fun oneHot(actual: Int, expected: Int): Float =
        if (actual == expected) 1f else 0f

    private companion object {
        const val MINUTES_PER_HOUR = 60
        const val SECONDS_PER_MINUTE = 60.0
        const val MINUTES_PER_DAY = 1440.0
        const val MILLIS_PER_MINUTE = 60000f
    }
}
