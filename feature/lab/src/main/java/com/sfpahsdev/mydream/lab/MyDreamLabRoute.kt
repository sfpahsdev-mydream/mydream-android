package com.sfpahsdev.mydream.lab

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sfpahsdev.mydream.export.SleepSessionJsonlExporter
import com.sfpahsdev.mydream.health.SamsungHealthSleepDataSource
import com.sfpahsdev.mydream.sleep.SleepDataSourceResult
import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStageType
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val FullHistoryStartDate: LocalDate = LocalDate.of(2014, 1, 1)

@Composable
fun MyDreamLabRoute(
    activity: ComponentActivity,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val sleepDataSource = remember(activity) {
        SamsungHealthSleepDataSource(
            context = activity.applicationContext,
            activityProvider = { activity },
        )
    }
    val exporter = remember { SleepSessionJsonlExporter() }
    var state by remember { mutableStateOf(CollectorUiState()) }

    fun writeExportFile(sessions: List<SleepSession>): File {
        val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "mydream_sleep_${LocalDate.now()}.jsonl")
        file.outputStream().use { outputStream ->
            exporter.export(sessions, outputStream)
        }
        return file
    }

    fun shareExport() {
        val exportFile = state.exportFile ?: return
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            exportFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Export MyDream sleep JSONL"))
    }

    CollectorScreen(
        state = state,
        onRequestPermission = {
            scope.launch {
                state = state.copy(isLoading = true, status = "Samsung Health 권한 요청 중...")
                state = when (val result = sleepDataSource.requestReadPermission()) {
                    is SleepDataSourceResult.Success -> state.copy(
                        isLoading = false,
                        status = "수면 데이터 읽기 권한을 확인했습니다.",
                    )
                    is SleepDataSourceResult.Failure -> state.copy(
                        isLoading = false,
                        status = result.message,
                    )
                }
            }
        },
        onReadSleep = {
            scope.launch {
                val lookupStartedAt = System.currentTimeMillis()
                val to = LocalDateTime.now()
                val from = FullHistoryStartDate.atStartOfDay()

                state = state.copy(
                    isLoading = true,
                    status = "전체 수면 단계 데이터 조회 중...",
                    from = from,
                    to = to,
                )
                state = when (val result = sleepDataSource.getSleepSessions(from, to)) {
                    is SleepDataSourceResult.Success -> {
                        val lookupDurationMs = System.currentTimeMillis() - lookupStartedAt
                        state.copy(
                            isLoading = false,
                            status = "${result.value.size}개 수면 세션을 가져왔습니다.",
                            sessions = result.value,
                            exportFile = writeExportFile(result.value),
                            lookupDurationMs = lookupDurationMs,
                        )
                    }
                    is SleepDataSourceResult.Failure -> state.copy(
                        isLoading = false,
                        status = result.message,
                        lookupDurationMs = System.currentTimeMillis() - lookupStartedAt,
                    )
                }
            }
        },
        onShareExport = ::shareExport,
        modifier = modifier,
    )
}

@Composable
private fun CollectorScreen(
    state: CollectorUiState,
    onRequestPermission: () -> Unit,
    onReadSleep: () -> Unit,
    onShareExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "MyDream Lab",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Samsung Health 수면 단계 데이터를 가져와 JSONL 파일로 내보냅니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRequestPermission,
                enabled = !state.isLoading,
            ) {
                Text("권한 확인")
            }
            Button(
                onClick = onReadSleep,
                enabled = !state.isLoading,
            ) {
                Text("수면 가져오기")
            }
        }
        Button(
            onClick = onShareExport,
            enabled = state.exportFile != null && !state.isLoading,
        ) {
            Text("JSONL 공유")
        }
        StatusCard(state)
        val stats = remember(state.sessions) { state.sessions.toCollectorStats() }
        DataQualityCard(stats)
        MonthlyCoverageCard(stats)
        ProblemSessionsCard(stats)
    }
}

@Composable
private fun StatusCard(state: CollectorUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = state.status, style = MaterialTheme.typography.bodyLarge)
            state.from?.let { from ->
                Text("조회 시작: ${from.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            }
            state.to?.let { to ->
                Text("조회 종료: ${to.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            }
            state.lookupDurationMs?.let { durationMs ->
                Text("탐색 시간: ${durationMs.formatDurationMs()}")
            }
            Text("세션 수: ${state.sessions.size}")
            Text("단계 수: ${state.sessions.sumOf { it.stages.size }}")
            state.exportFile?.let { file ->
                Text("Export: ${file.name}")
            }
        }
    }
}

@Composable
private fun DataQualityCard(stats: CollectorStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("데이터 품질 요약", style = MaterialTheme.typography.titleMedium)
            Text("첫 기록: ${stats.firstSessionDate ?: "-"}")
            Text("마지막 기록: ${stats.lastSessionDate ?: "-"}")
            Text("총 수면 시간: ${stats.totalSleepMinutes / 60}시간 ${stats.totalSleepMinutes % 60}분")
            Text("평균 수면 시간: ${stats.averageSleepMinutes / 60}시간 ${stats.averageSleepMinutes % 60}분")
            Text("학습 가능 예상 세션: ${stats.trainingEligibleSessions}")
            Text("3시간 미만 세션: ${stats.tooShortSessions}")
            Text("12시간 초과 세션: ${stats.tooLongSessions}")
            Text("stage 없는 세션: ${stats.noStageSessions}")
            Text("Deep 포함 세션: ${stats.deepStageSessions}")
            Text("Unknown stage 수: ${stats.unknownStageCount}")
            stats.stageSummaries.forEach { summary ->
                Text("${summary.type}: ${summary.count}개 / ${summary.minutes}분")
            }
        }
    }
}

@Composable
private fun MonthlyCoverageCard(stats: CollectorStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("월별 커버리지", style = MaterialTheme.typography.titleMedium)
            Text("기록 있는 달: ${stats.monthsWithSessions}")
            Text("기록 없는 달: ${stats.emptyMonthsBetweenFirstAndLast}")
            stats.recentMonthSummaries.forEach { month ->
                Text("${month.month}: ${month.sessions}세션 / stage ${month.stages}개")
            }
        }
    }
}

@Composable
private fun ProblemSessionsCard(stats: CollectorStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("문제 세션 미리보기", style = MaterialTheme.typography.titleMedium)
            if (stats.problemSessions.isEmpty()) {
                Text("표시할 문제 세션이 없습니다.")
            } else {
                stats.problemSessions.forEach { problem ->
                    Text("${problem.startDate} / ${problem.reason} / ${problem.id}")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CollectorScreenPreview() {
    CollectorScreen(
        state = CollectorUiState(status = "미리보기"),
        onRequestPermission = {},
        onReadSleep = {},
        onShareExport = {},
    )
}

private data class CollectorUiState(
    val isLoading: Boolean = false,
    val status: String = "Samsung Health 개발자 모드와 수면 데이터 읽기 권한을 확인하세요.",
    val from: LocalDateTime? = null,
    val to: LocalDateTime? = null,
    val sessions: List<SleepSession> = emptyList(),
    val exportFile: File? = null,
    val lookupDurationMs: Long? = null,
)

private data class CollectorStats(
    val firstSessionDate: String?,
    val lastSessionDate: String?,
    val totalSleepMinutes: Long,
    val averageSleepMinutes: Long,
    val trainingEligibleSessions: Int,
    val tooShortSessions: Int,
    val tooLongSessions: Int,
    val noStageSessions: Int,
    val deepStageSessions: Int,
    val unknownStageCount: Int,
    val stageSummaries: List<StageSummary>,
    val monthsWithSessions: Int,
    val emptyMonthsBetweenFirstAndLast: Int,
    val recentMonthSummaries: List<MonthSummary>,
    val problemSessions: List<ProblemSession>,
)

private data class StageSummary(
    val type: SleepStageType,
    val count: Int,
    val minutes: Long,
)

private data class MonthSummary(
    val month: YearMonth,
    val sessions: Int,
    val stages: Int,
)

private data class ProblemSession(
    val id: String,
    val startDate: String,
    val reason: String,
)

private fun List<SleepSession>.toCollectorStats(): CollectorStats {
    if (isEmpty()) {
        return CollectorStats(
            firstSessionDate = null,
            lastSessionDate = null,
            totalSleepMinutes = 0,
            averageSleepMinutes = 0,
            trainingEligibleSessions = 0,
            tooShortSessions = 0,
            tooLongSessions = 0,
            noStageSessions = 0,
            deepStageSessions = 0,
            unknownStageCount = 0,
            stageSummaries = emptyList(),
            monthsWithSessions = 0,
            emptyMonthsBetweenFirstAndLast = 0,
            recentMonthSummaries = emptyList(),
            problemSessions = emptyList(),
        )
    }

    val zone = ZoneId.systemDefault()
    val sortedSessions = sortedBy { it.startTime }
    val durations = associateWith { it.durationMinutes() }
    val months = sortedSessions.groupBy { YearMonth.from(it.startTime.atZone(zone)) }
    val firstMonth = months.keys.minOrNull()
    val lastMonth = months.keys.maxOrNull()
    val totalMonths = if (firstMonth != null && lastMonth != null) {
        generateSequence(firstMonth) { month ->
            month.plusMonths(1).takeIf { it <= lastMonth }
        }.count()
    } else {
        0
    }

    val stageSummaries = SleepStageType.entries.map { type ->
        val stages = sortedSessions.flatMap { it.stages }.filter { it.type == type }
        StageSummary(
            type = type,
            count = stages.size,
            minutes = stages.sumOf { it.durationMinutes() },
        )
    }

    return CollectorStats(
        firstSessionDate = sortedSessions.first().startTime.atZone(zone).toLocalDate().toString(),
        lastSessionDate = sortedSessions.last().startTime.atZone(zone).toLocalDate().toString(),
        totalSleepMinutes = durations.values.sum(),
        averageSleepMinutes = durations.values.average().toLong(),
        trainingEligibleSessions = sortedSessions.count { session ->
            durations.getValue(session) in 180..720 && session.stages.isNotEmpty()
        },
        tooShortSessions = sortedSessions.count { durations.getValue(it) < 180 },
        tooLongSessions = sortedSessions.count { durations.getValue(it) > 720 },
        noStageSessions = sortedSessions.count { it.stages.isEmpty() },
        deepStageSessions = sortedSessions.count { session ->
            session.stages.any { it.type == SleepStageType.Deep }
        },
        unknownStageCount = sortedSessions.sumOf { session ->
            session.stages.count { it.type == SleepStageType.Unknown }
        },
        stageSummaries = stageSummaries,
        monthsWithSessions = months.size,
        emptyMonthsBetweenFirstAndLast = (totalMonths - months.size).coerceAtLeast(0),
        recentMonthSummaries = months.entries
            .sortedByDescending { it.key }
            .take(12)
            .map { (month, sessions) ->
                MonthSummary(
                    month = month,
                    sessions = sessions.size,
                    stages = sessions.sumOf { it.stages.size },
                )
            },
        problemSessions = sortedSessions
            .mapNotNull { session -> session.toProblemSession(zone, durations.getValue(session)) }
            .take(10),
    )
}

private fun SleepSession.toProblemSession(zone: ZoneId, durationMinutes: Long): ProblemSession? {
    val reason = when {
        stages.isEmpty() -> "stage 없음"
        durationMinutes < 180 -> "3시간 미만"
        durationMinutes > 720 -> "12시간 초과"
        stages.any { it.type == SleepStageType.Unknown } -> "Unknown stage 포함"
        else -> return null
    }
    return ProblemSession(
        id = id,
        startDate = startTime.atZone(zone).toLocalDate().toString(),
        reason = reason,
    )
}

private fun SleepSession.durationMinutes(): Long =
    maxOf(0, Duration.between(startTime, endTime).toMinutes())

private fun com.sfpahsdev.mydream.sleep.SleepStage.durationMinutes(): Long =
    maxOf(0, Duration.between(startTime, endTime).toMinutes())

private fun Long.formatDurationMs(): String {
    val totalSeconds = this / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "${minutes}분 ${seconds}초"
    } else {
        "${seconds}초"
    }
}
