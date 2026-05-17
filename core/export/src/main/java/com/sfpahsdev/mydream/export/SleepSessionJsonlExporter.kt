package com.sfpahsdev.mydream.export

import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStage
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class SleepSessionJsonlExporter {
    fun export(sessions: List<SleepSession>, outputStream: OutputStream) {
        outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            sessions.forEach { session ->
                writer.append(session.toJson())
                writer.newLine()
            }
        }
    }

    fun toJsonl(sessions: List<SleepSession>): String =
        buildString {
            sessions.forEach { session ->
                append(session.toJson())
                append('\n')
            }
        }

    private fun SleepSession.toJson(): String =
        buildString {
            append('{')
            appendJsonField("session_id", id)
            append(',')
            appendJsonField("start", startTime.toString())
            append(',')
            appendJsonField("end", endTime.toString())
            append(',')
            append("\"stages\":[")
            stages.forEachIndexed { index, stage ->
                if (index > 0) append(',')
                append(stage.toJson())
            }
            append(']')
            append('}')
        }

    private fun SleepStage.toJson(): String =
        buildString {
            append('{')
            appendJsonField("type", type.name)
            append(',')
            appendJsonField("start", startTime.toString())
            append(',')
            appendJsonField("end", endTime.toString())
            append('}')
        }

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        append('"')
        append(escapeJson(name))
        append("\":\"")
        append(escapeJson(value))
        append('"')
    }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char < ' ') {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
}
