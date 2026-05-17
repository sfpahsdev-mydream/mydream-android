package com.sfpahsdev.mydream.export

import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStage
import com.sfpahsdev.mydream.sleep.SleepStageType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SleepSessionJsonlExporterTest {
    private val exporter = SleepSessionJsonlExporter()

    @Test
    fun exportsOneSessionPerJsonLine() {
        val sessions = listOf(
            SleepSession(
                id = "2026-05-01_sleep",
                startTime = Instant.parse("2026-05-01T14:40:00Z"),
                endTime = Instant.parse("2026-05-01T22:20:00Z"),
                stages = listOf(
                    SleepStage(
                        type = SleepStageType.Light,
                        startTime = Instant.parse("2026-05-01T14:40:00Z"),
                        endTime = Instant.parse("2026-05-01T15:15:00Z"),
                    ),
                    SleepStage(
                        type = SleepStageType.Deep,
                        startTime = Instant.parse("2026-05-01T15:15:00Z"),
                        endTime = Instant.parse("2026-05-01T15:45:00Z"),
                    ),
                ),
            ),
        )

        val jsonl = exporter.toJsonl(sessions)

        assertEquals(
            "{\"session_id\":\"2026-05-01_sleep\",\"start\":\"2026-05-01T14:40:00Z\",\"end\":\"2026-05-01T22:20:00Z\",\"stages\":[{\"type\":\"Light\",\"start\":\"2026-05-01T14:40:00Z\",\"end\":\"2026-05-01T15:15:00Z\"},{\"type\":\"Deep\",\"start\":\"2026-05-01T15:15:00Z\",\"end\":\"2026-05-01T15:45:00Z\"}]}\n",
            jsonl,
        )
    }

    @Test
    fun escapesJsonStrings() {
        val sessions = listOf(
            SleepSession(
                id = "night \"alpha\"\n",
                startTime = Instant.parse("2026-05-01T14:40:00Z"),
                endTime = Instant.parse("2026-05-01T22:20:00Z"),
                stages = emptyList(),
            ),
        )

        val jsonl = exporter.toJsonl(sessions)

        assertEquals(
            "{\"session_id\":\"night \\\"alpha\\\"\\n\",\"start\":\"2026-05-01T14:40:00Z\",\"end\":\"2026-05-01T22:20:00Z\",\"stages\":[]}\n",
            jsonl,
        )
    }
}
