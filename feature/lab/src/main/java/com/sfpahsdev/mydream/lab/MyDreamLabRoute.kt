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
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

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
                val to = LocalDateTime.now()
                val from = LocalDate.now().minusDays(400).atStartOfDay()

                state = state.copy(
                    isLoading = true,
                    status = "최근 400일 수면 단계 데이터 조회 중...",
                    from = from,
                    to = to,
                )
                state = when (val result = sleepDataSource.getSleepSessions(from, to)) {
                    is SleepDataSourceResult.Success -> state.copy(
                        isLoading = false,
                        status = "${result.value.size}개 수면 세션을 가져왔습니다.",
                        sessions = result.value,
                        exportFile = writeExportFile(result.value),
                    )
                    is SleepDataSourceResult.Failure -> state.copy(
                        isLoading = false,
                        status = result.message,
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
        state.sessions.takeLast(5).forEach { session ->
            SessionCard(session)
        }
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
            Text("세션 수: ${state.sessions.size}")
            Text("단계 수: ${state.sessions.sumOf { it.stages.size }}")
            state.exportFile?.let { file ->
                Text("Export: ${file.name}")
            }
        }
    }
}

@Composable
private fun SessionCard(session: SleepSession) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = session.id, style = MaterialTheme.typography.titleMedium)
            Text("시작: ${session.startTime}")
            Text("종료: ${session.endTime}")
            Text("수면 단계: ${session.stages.size}")
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
)
