package com.sfpahsdev.mydream.inference

import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStage
import com.sfpahsdev.mydream.sleep.SleepStageType
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SequenceModelInputBuilderTest {
    @Test
    fun buildsStageSequenceAndContextInTrainingOrder() {
        val session = SleepSession(
            id = "session-1",
            startTime = Instant.parse("2026-05-18T20:00:00Z"),
            endTime = Instant.parse("2026-05-18T22:00:00Z"),
            stages = listOf(
                SleepStage(
                    type = SleepStageType.Awake,
                    startTime = Instant.parse("2026-05-18T20:00:00Z"),
                    endTime = Instant.parse("2026-05-18T20:10:00Z"),
                ),
                SleepStage(
                    type = SleepStageType.Light,
                    startTime = Instant.parse("2026-05-18T20:10:00Z"),
                    endTime = Instant.parse("2026-05-18T20:40:00Z"),
                ),
                SleepStage(
                    type = SleepStageType.Deep,
                    startTime = Instant.parse("2026-05-18T20:40:00Z"),
                    endTime = Instant.parse("2026-05-18T20:50:00Z"),
                ),
                SleepStage(
                    type = SleepStageType.Rem,
                    startTime = Instant.parse("2026-05-18T20:50:00Z"),
                    endTime = Instant.parse("2026-05-18T21:00:00Z"),
                ),
            ),
        )
        val scaler = SequenceContextScaler(
            columns = SequenceModelContract.contextColumns,
            mean = FloatArray(SequenceModelContract.contextColumns.size) { 0f },
            std = FloatArray(SequenceModelContract.contextColumns.size) { 1f },
        )

        val input = SequenceModelInputBuilder(scaler).build(
            session = session,
            candidateTime = Instant.parse("2026-05-18T20:59:00Z"),
            deadlineTime = Instant.parse("2026-05-18T21:29:00Z"),
        )

        assertEquals(60, input.stageSequence60m.size)
        assertEquals(22, input.contextRaw22.size)
        assertArrayEquals(input.contextRaw22, input.contextScaled22, 0.000001f)
        assertEquals(1, input.stageSequence60m[0])
        assertEquals(2, input.stageSequence60m[10])
        assertEquals(3, input.stageSequence60m[40])
        assertEquals(4, input.stageSequence60m[50])
        assertEquals(59f, input.contextRaw22[0], 0.000001f)
        assertEquals(30f, input.contextRaw22[1], 0.000001f)
        assertEquals(9f, input.contextRaw22[6], 0.000001f)
        assertEquals(9f, input.contextRaw22[7], 0.000001f)
        assertEquals(1f, input.contextRaw22[8], 0.000001f)
        assertEquals(1f, input.contextRaw22[21], 0.000001f)
    }
}
