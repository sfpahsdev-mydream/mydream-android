package com.sfpahsdev.mydream.lab

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import com.sfpahsdev.mydream.export.SleepSessionJsonlExporter
import com.sfpahsdev.mydream.health.SamsungHealthSleepDataSource
import com.sfpahsdev.mydream.inference.AlarmWindowCandidateSummary
import com.sfpahsdev.mydream.inference.AlarmWindowEvaluationLog
import com.sfpahsdev.mydream.inference.AndroidInferenceValidationLog
import com.sfpahsdev.mydream.inference.AndroidSequenceModelValidator
import com.sfpahsdev.mydream.inference.DecisionPolicyEvaluator
import com.sfpahsdev.mydream.inference.DecisionPolicyInput
import com.sfpahsdev.mydream.inference.DecisionPolicyOption
import com.sfpahsdev.mydream.inference.DecisionPolicyResult
import com.sfpahsdev.mydream.inference.InputBuilderParityValidationLog
import com.sfpahsdev.mydream.inference.MultiSampleDecisionPolicyComparisonLog
import com.sfpahsdev.mydream.inference.MultiSampleParityValidationLog
import com.sfpahsdev.mydream.inference.MultiSampleTabularValidationLog
import com.sfpahsdev.mydream.inference.TabularInferenceValidationLog
import com.sfpahsdev.mydream.sleep.SleepDataSourceResult
import com.sfpahsdev.mydream.sleep.SleepStage
import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStageType
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.json.JSONObject

private val FullHistoryStartDate: LocalDate = LocalDate.of(2014, 1, 1)
private const val SLEEP_CACHE_DIR_NAME = "mydream_lab_sleep_cache"
private const val SLEEP_CACHE_FILE_NAME = "mydream_sleep_latest.jsonl"

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
    val sequenceModelValidator = remember(activity) {
        AndroidSequenceModelValidator(activity.applicationContext)
    }
    val sleepCacheDir = remember(activity) {
        File(activity.filesDir, SLEEP_CACHE_DIR_NAME)
    }
    val sleepCacheFile = remember(sleepCacheDir) {
        File(sleepCacheDir, SLEEP_CACHE_FILE_NAME)
    }
    var state by remember { mutableStateOf(CollectorUiState()) }

    fun readSleepCacheFile(): CachedSleepData? {
        if (!sleepCacheFile.exists()) {
            return null
        }
        val sessions = sleepCacheFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
            line.toSleepSession()
        }
        return CachedSleepData(
            sessions = sessions,
            file = sleepCacheFile,
            loadedAt = Instant.ofEpochMilli(sleepCacheFile.lastModified()),
        )
    }

    fun writeSleepCacheFile(sessions: List<SleepSession>): File {
        if (sleepCacheDir.exists()) {
            sleepCacheDir.deleteRecursively()
        }
        sleepCacheDir.mkdirs()
        val file = sleepCacheFile
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

    LaunchedEffect(Unit) {
        val cached = runCatching { readSleepCacheFile() }.getOrNull()
        if (cached != null) {
            state = state.copy(
                status = "${cached.sessions.size}개 수면 세션을 저장된 데이터에서 불러왔습니다.",
                sessions = cached.sessions,
                exportFile = cached.file,
                lastLoadedAt = cached.loadedAt,
                loadedDataSource = "Saved",
            )
        }
    }

    CollectorScreen(
        state = state,
        onReadSleep = {
            scope.launch {
                val lookupStartedAt = System.currentTimeMillis()
                val to = LocalDateTime.now()
                val from = FullHistoryStartDate.atStartOfDay()

                state = state.copy(
                    isLoading = true,
                    status = "Samsung Health 권한 확인 중...",
                    from = from,
                    to = to,
                )
                when (val permissionResult = sleepDataSource.requestReadPermission()) {
                    is SleepDataSourceResult.Success -> {
                        state = state.copy(status = "전체 수면 단계 데이터 조회 중...")
                    }
                    is SleepDataSourceResult.Failure -> {
                        state = state.copy(
                            isLoading = false,
                            status = permissionResult.message,
                            lookupDurationMs = System.currentTimeMillis() - lookupStartedAt,
                        )
                        return@launch
                    }
                }
                state = when (val result = sleepDataSource.getSleepSessions(from, to)) {
                    is SleepDataSourceResult.Success -> {
                        val lookupDurationMs = System.currentTimeMillis() - lookupStartedAt
                        val loadedAt = Instant.now()
                        state.copy(
                            isLoading = false,
                            status = "${result.value.size}개 수면 세션을 가져왔습니다.",
                            sessions = result.value,
                            exportFile = writeSleepCacheFile(result.value),
                            lookupDurationMs = lookupDurationMs,
                            lastLoadedAt = loadedAt,
                            loadedDataSource = "Samsung Health",
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
        onRunTfliteValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running fixed parity validation...",
                    selectedValidationAction = ValidationAction.GRU_FLOAT32,
                )
                state = try {
                    val log = sequenceModelValidator.runFixedParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Fixed parity validation finished.",
                        tfliteValidationLog = log,
                        multiSampleParityLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "TFLite validation failed: ${error.message}",
                        tfliteValidationLog = null,
                    )
                }
            }
        },
        onRunFloat16TfliteValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running float16 parity validation...",
                    selectedValidationAction = ValidationAction.GRU_FLOAT16,
                )
                state = try {
                    val log = sequenceModelValidator.runFixedFloat16ParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Float16 parity validation finished.",
                        tfliteValidationLog = log,
                        multiSampleParityLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Float16 validation failed: ${error.message}",
                        tfliteValidationLog = null,
                    )
                }
            }
        },
        onRunMultiFloat32TfliteValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running multi-sample float32 parity validation...",
                    selectedValidationAction = ValidationAction.GRU_MULTI_FLOAT32,
                )
                state = try {
                    val log = sequenceModelValidator.runMultiSampleFloat32ParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float32 validation finished.",
                        tfliteValidationLog = null,
                        multiSampleParityLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float32 validation failed: ${error.message}",
                        multiSampleParityLog = null,
                    )
                }
            }
        },
        onRunMultiFloat16TfliteValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running multi-sample float16 parity validation...",
                    selectedValidationAction = ValidationAction.GRU_MULTI_FLOAT16,
                )
                state = try {
                    val log = sequenceModelValidator.runMultiSampleFloat16ParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float16 validation finished.",
                        tfliteValidationLog = null,
                        multiSampleParityLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float16 validation failed: ${error.message}",
                        multiSampleParityLog = null,
                    )
                }
            }
        },
        onRunTabularValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running tabular parity validation...",
                    selectedValidationAction = ValidationAction.TABULAR_FLOAT32,
                )
                state = try {
                    val log = sequenceModelValidator.runFixedTabularParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Tabular parity validation finished.",
                        tabularValidationLog = log,
                        multiSampleTabularValidationLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Tabular validation failed: ${error.message}",
                        tabularValidationLog = null,
                    )
                }
            }
        },
        onRunMultiTabularValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running multi-sample tabular validation...",
                    selectedValidationAction = ValidationAction.TABULAR_MULTI_FLOAT32,
                )
                state = try {
                    val log = sequenceModelValidator.runMultiSampleTabularParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample tabular validation finished.",
                        multiSampleTabularValidationLog = log,
                        tabularValidationLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample tabular validation failed: ${error.message}",
                        multiSampleTabularValidationLog = null,
                    )
                }
            }
        },
        onRunFloat16TabularValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running float16 tabular validation...",
                    selectedValidationAction = ValidationAction.TABULAR_FLOAT16,
                )
                state = try {
                    val log = sequenceModelValidator.runFixedFloat16TabularParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Float16 tabular validation finished.",
                        tabularValidationLog = log,
                        multiSampleTabularValidationLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Float16 tabular validation failed: ${error.message}",
                        tabularValidationLog = null,
                    )
                }
            }
        },
        onRunMultiFloat16TabularValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running multi-sample float16 tabular validation...",
                    selectedValidationAction = ValidationAction.TABULAR_MULTI_FLOAT16,
                )
                state = try {
                    val log = sequenceModelValidator.runMultiSampleFloat16TabularParityValidation()
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float16 tabular validation finished.",
                        multiSampleTabularValidationLog = log,
                        tabularValidationLog = null,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample float16 tabular validation failed: ${error.message}",
                        multiSampleTabularValidationLog = null,
                    )
                }
            }
        },
        onRunInputBuilderValidation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running input builder parity validation...",
                    selectedValidationAction = ValidationAction.INPUT_BUILDERS,
                )
                state = try {
                    val log = sequenceModelValidator.runInputBuilderParityValidation(state.sessions)
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Input builder parity validation finished.",
                        inputBuilderParityLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Input builder parity validation failed: ${error.message}",
                        inputBuilderParityLog = null,
                    )
                }
            }
        },
        onRunAlarmWindowEvaluation = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Running latest-session 30-minute alarm window evaluation...",
                    selectedValidationAction = ValidationAction.ALARM_WINDOW,
                )
                state = try {
                    val log = sequenceModelValidator.runLatestSessionAlarmWindowEvaluation(state.sessions)
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Latest-session alarm window evaluation finished.",
                        alarmWindowEvaluationLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Alarm window evaluation failed: ${error.message}",
                        alarmWindowEvaluationLog = null,
                    )
                }
            }
        },
        onToggleDecisionOption = { option ->
            state = state.copy(
                selectedDecisionOptions = if (option in state.selectedDecisionOptions) {
                    state.selectedDecisionOptions - option
                } else {
                    state.selectedDecisionOptions + option
                },
            )
        },
        onCompareSelectedDecisionOptions = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Comparing selected decision options...",
                )
                state = try {
                    val log = state.tfliteValidationLog
                        ?: sequenceModelValidator.runFixedParityValidation()
                    val tabularLog = state.tabularValidationLog
                        ?: sequenceModelValidator.runFixedTabularParityValidation()
                    val input = log.toDecisionPolicyInput(tabularLog.tabularScoreAndroid)
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Decision option comparison finished.",
                        tfliteValidationLog = log,
                        tabularValidationLog = tabularLog,
                        multiSampleParityLog = null,
                        decisionPolicyResults = DecisionPolicyEvaluator.evaluateAll(
                            options = state.selectedDecisionOptions,
                            input = input,
                        ),
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Decision option comparison failed: ${error.message}",
                        decisionPolicyResults = emptyList(),
                    )
                }
            }
        },
        onCompareMultiSampleDecisionOptions = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Comparing multi-sample decision options...",
                )
                state = try {
                    val log = sequenceModelValidator.runMultiSampleDecisionPolicyComparison(
                        options = state.selectedDecisionOptions,
                    )
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample decision comparison finished.",
                        multiSampleDecisionPolicyLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Multi-sample decision comparison failed: ${error.message}",
                        multiSampleDecisionPolicyLog = null,
                    )
                }
            }
        },
        onCompareRecent30DayDecisionOptions = {
            scope.launch {
                state = state.copy(
                    isTfliteValidationRunning = true,
                    tfliteValidationStatus = "Comparing recent 30-day policy options...",
                )
                state = try {
                    val log = sequenceModelValidator.runRecent30DayDecisionPolicyComparison(
                        sessions = state.sessions,
                        options = state.selectedDecisionOptions,
                    )
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Recent 30-day policy comparison finished.",
                        multiSampleDecisionPolicyLog = log,
                    )
                } catch (error: Throwable) {
                    state.copy(
                        isTfliteValidationRunning = false,
                        tfliteValidationStatus = "Recent 30-day policy comparison failed: ${error.message}",
                        multiSampleDecisionPolicyLog = null,
                    )
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun CollectorScreen(
    state: CollectorUiState,
    onReadSleep: () -> Unit,
    onShareExport: () -> Unit,
    onRunTfliteValidation: () -> Unit,
    onRunFloat16TfliteValidation: () -> Unit,
    onRunMultiFloat32TfliteValidation: () -> Unit,
    onRunMultiFloat16TfliteValidation: () -> Unit,
    onRunTabularValidation: () -> Unit,
    onRunMultiTabularValidation: () -> Unit,
    onRunFloat16TabularValidation: () -> Unit,
    onRunMultiFloat16TabularValidation: () -> Unit,
    onRunInputBuilderValidation: () -> Unit,
    onRunAlarmWindowEvaluation: () -> Unit,
    onToggleDecisionOption: (DecisionPolicyOption) -> Unit,
    onCompareSelectedDecisionOptions: () -> Unit,
    onCompareMultiSampleDecisionOptions: () -> Unit,
    onCompareRecent30DayDecisionOptions: () -> Unit,
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
        StatusCard(state)
        DataActionsCard(
            state = state,
            onReadSleep = onReadSleep,
            onShareExport = onShareExport,
        )
        ValidationActionsCard(
            state = state,
            onRunTfliteValidation = onRunTfliteValidation,
            onRunFloat16TfliteValidation = onRunFloat16TfliteValidation,
            onRunMultiFloat32TfliteValidation = onRunMultiFloat32TfliteValidation,
            onRunMultiFloat16TfliteValidation = onRunMultiFloat16TfliteValidation,
            onRunTabularValidation = onRunTabularValidation,
            onRunMultiTabularValidation = onRunMultiTabularValidation,
            onRunFloat16TabularValidation = onRunFloat16TabularValidation,
            onRunMultiFloat16TabularValidation = onRunMultiFloat16TabularValidation,
            onRunInputBuilderValidation = onRunInputBuilderValidation,
            onRunAlarmWindowEvaluation = onRunAlarmWindowEvaluation,
        )
        ModelValidationCard(state)
        InputBuilderValidationCard(state)
        AlarmWindowEvaluationCard(state)
        DecisionPolicyComparisonCard(
            state = state,
            onToggleDecisionOption = onToggleDecisionOption,
            onCompareSelectedDecisionOptions = onCompareSelectedDecisionOptions,
            onCompareMultiSampleDecisionOptions = onCompareMultiSampleDecisionOptions,
            onCompareRecent30DayDecisionOptions = onCompareRecent30DayDecisionOptions,
        )
        val stats = remember(state.sessions) { state.sessions.toCollectorStats() }
        DataQualityCard(stats)
        MonthlyCoverageCard(stats)
    }
}

@Composable
private fun DataActionsCard(
    state: CollectorUiState,
    onReadSleep: () -> Unit,
    onShareExport: () -> Unit,
) {
    ActionCard(
        title = "Data",
        copyText = "Data\nsessions=${state.sessions.size}\nstages=${state.sessions.sumOf { it.stages.size }}\nlast_loaded=${state.lastLoadedAt?.toLocalDateTimeLabel() ?: "-"}\nsource=${state.loadedDataSource}\nexport=${state.exportFile?.name ?: "-"}",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onReadSleep,
                enabled = !state.isLoading,
                modifier = Modifier.weight(1f),
            ) {
                Text("수면 가져오기")
            }
            Button(
                onClick = onShareExport,
                enabled = state.exportFile != null && !state.isLoading,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export")
            }
        }
    }
}

@Composable
private fun ValidationActionsCard(
    state: CollectorUiState,
    onRunTfliteValidation: () -> Unit,
    onRunFloat16TfliteValidation: () -> Unit,
    onRunMultiFloat32TfliteValidation: () -> Unit,
    onRunMultiFloat16TfliteValidation: () -> Unit,
    onRunTabularValidation: () -> Unit,
    onRunMultiTabularValidation: () -> Unit,
    onRunFloat16TabularValidation: () -> Unit,
    onRunMultiFloat16TabularValidation: () -> Unit,
    onRunInputBuilderValidation: () -> Unit,
    onRunAlarmWindowEvaluation: () -> Unit,
) {
    val enabled = !state.isLoading && !state.isTfliteValidationRunning
    ActionCard(
        title = "Validation",
        copyText = "Validation\nlast_action=${state.selectedValidationAction ?: "-"}\nstatus=${state.tfliteValidationStatus}",
    ) {
        Text("Model parity", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValidationButton(
                action = ValidationAction.GRU_FLOAT32,
                selectedAction = state.selectedValidationAction,
                onClick = onRunTfliteValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "GRU f32",
            )
            ValidationButton(
                action = ValidationAction.GRU_FLOAT16,
                selectedAction = state.selectedValidationAction,
                onClick = onRunFloat16TfliteValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "GRU f16",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValidationButton(
                action = ValidationAction.GRU_MULTI_FLOAT32,
                selectedAction = state.selectedValidationAction,
                onClick = onRunMultiFloat32TfliteValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "GRU multi",
            )
            ValidationButton(
                action = ValidationAction.GRU_MULTI_FLOAT16,
                selectedAction = state.selectedValidationAction,
                onClick = onRunMultiFloat16TfliteValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "GRU multi f16",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValidationButton(
                action = ValidationAction.TABULAR_FLOAT32,
                selectedAction = state.selectedValidationAction,
                onClick = onRunTabularValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "Tabular f32",
            )
            ValidationButton(
                action = ValidationAction.TABULAR_MULTI_FLOAT32,
                selectedAction = state.selectedValidationAction,
                onClick = onRunMultiTabularValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "Tabular multi f32",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValidationButton(
                action = ValidationAction.TABULAR_FLOAT16,
                selectedAction = state.selectedValidationAction,
                onClick = onRunFloat16TabularValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "Tabular f16",
            )
            ValidationButton(
                action = ValidationAction.TABULAR_MULTI_FLOAT16,
                selectedAction = state.selectedValidationAction,
                onClick = onRunMultiFloat16TabularValidation,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                text = "Tabular multi f16",
            )
        }
        Text("Real session checks", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ValidationButton(
                action = ValidationAction.INPUT_BUILDERS,
                selectedAction = state.selectedValidationAction,
                onClick = onRunInputBuilderValidation,
                enabled = state.sessions.isNotEmpty() && enabled,
                modifier = Modifier.weight(1f),
                text = "Builders",
            )
            ValidationButton(
                action = ValidationAction.ALARM_WINDOW,
                selectedAction = state.selectedValidationAction,
                onClick = onRunAlarmWindowEvaluation,
                enabled = state.sessions.isNotEmpty() && enabled,
                modifier = Modifier.weight(1f),
                text = "Latest 30m",
            )
        }
    }
}

@Composable
private fun ValidationButton(
    action: ValidationAction,
    selectedAction: ValidationAction?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    text: String,
) {
    val isSelected = action == selectedAction
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (isSelected) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(text)
    }
}

@Composable
private fun StatusCard(state: CollectorUiState) {
    CopyableCard(
        title = "Status",
        copyText = state.statusCopyText(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
            state.lastLoadedAt?.let { loadedAt ->
                Text("최근 불러온 데이터: ${loadedAt.toLocalDateTimeLabel()} (${state.loadedDataSource})")
            }
            state.exportFile?.let { file ->
                Text("Export: ${file.name}")
            }
    }
}

@Composable
private fun ModelValidationCard(state: CollectorUiState) {
    CopyableCard(
        title = "Model validation",
        copyText = state.modelValidationCopyText(),
    ) {
        Text(state.tfliteValidationStatus)
        state.tfliteValidationLog?.let { log ->
            Text("GRU: ${"%.8f".format(log.gruScoreAndroid)}")
            log.absDiff?.let { diff ->
                Text("Result: ${if (diff <= 0.0001f) "PASS" else "CHECK"}")
                Text("Diff: ${"%.8f".format(diff)}")
            }
            Text("Model: ${log.modelVersion}")
        }
        state.multiSampleParityLog?.let { log ->
            Text("Model: ${log.modelVersion}")
            Text("Samples: ${log.sampleCount}")
            Text(
                "Result: ${
                    if (
                        log.meanAbsDiff <= 0.0001f &&
                        log.maxAbsDiff <= 0.001f &&
                        log.thresholdFlipCount == 0
                    ) {
                        "PASS"
                    } else {
                        "CHECK"
                    }
                }",
            )
            Text("Max diff: ${"%.8f".format(log.maxAbsDiff)}")
            Text("Threshold flips: ${log.thresholdFlipCount}")
        }
        state.tabularValidationLog?.let { log ->
            Text("Tabular score: ${"%.8f".format(log.tabularScoreAndroid)}")
            log.absDiff?.let { diff ->
                Text("Tabular result: ${if (diff <= 0.0001f) "PASS" else "CHECK"}")
                Text("Tabular diff: ${"%.8f".format(diff)}")
            }
            Text("Tabular model: ${log.modelVersion}")
        }
        state.multiSampleTabularValidationLog?.let { log ->
            Text("Tabular model: ${log.modelVersion}")
            Text("Tabular samples: ${log.sampleCount}")
            Text(
                "Tabular result: ${
                    if (
                        log.meanAbsDiff <= 0.0001f &&
                        log.maxAbsDiff <= 0.001f &&
                        log.thresholdFlipCount == 0
                    ) {
                        "PASS"
                    } else {
                        "CHECK"
                    }
                }",
            )
            Text("Tabular max diff: ${"%.8f".format(log.maxAbsDiff)}")
            Text("Tabular threshold flips: ${log.thresholdFlipCount}")
        }
    }
}

@Composable
private fun InputBuilderValidationCard(state: CollectorUiState) {
    CopyableCard(
        title = "Input builder validation",
        copyText = state.inputBuilderParityLog?.toCopyText() ?: "Input builder validation\nNot run",
    ) {
        state.inputBuilderParityLog?.let { log ->
            Text("Result: ${if (log.passed) "PASS" else "CHECK"}")
            Text(log.message)
            Text("Sample: ${log.sampleId}")
            Text("Session: ${log.sessionId.shortId()}")
            Text("Session matched: ${log.sessionMatched}")
            log.sequenceMismatchCount?.let { count ->
                Text("Stage sequence mismatches: $count")
            }
            maxOfNotNull(
                log.contextRawMaxAbsDiff,
                log.contextScaledMaxAbsDiff,
                log.tabularRawMaxAbsDiff,
                log.tabularScaledMaxAbsDiff,
            )?.let { diff ->
                Text("Max feature diff: ${"%.8f".format(diff)}")
            }
        } ?: Text("Not run")
    }
}

@Composable
private fun AlarmWindowEvaluationCard(state: CollectorUiState) {
    CopyableCard(
        title = "Alarm window evaluation",
        copyText = state.alarmWindowEvaluationLog?.toCopyText() ?: "Alarm window evaluation\nNot run",
    ) {
        state.alarmWindowEvaluationLog?.let { log ->
            Text("Source: ${log.sourceLabel}")
            Text("Deadline policy: ${log.deadlinePolicy}")
            Text("Selected: ${log.selectedAlarmTime.toLocalTimeLabel()}")
            Text("Decision: ${log.selectedDecision}")
            Text("Reason: ${log.selectedReason}")
            Text("Fallback: ${log.fallbackUsed}")
            Text("Minutes before deadline: ${"%.1f".format(log.selectedMinutesBeforeDeadline)}")
            log.selectedGruScore?.let { score ->
                Text("GRU: ${"%.8f".format(score)}")
            }
            log.selectedTabularScore?.let { score ->
                Text("Tabular: ${"%.8f".format(score)}")
            }
            log.selectedCombinedScore?.let { score ->
                Text("Combined: ${"%.8f".format(score)}")
            }
            Text("Candidates: ${log.candidateCount}, SMART_WAKE: ${log.smartWakeCount}, WAIT: ${log.waitCount}")
            log.lateWindowBestCandidate?.let { candidate ->
                Text("Best in last 10m", style = MaterialTheme.typography.titleSmall)
                CandidateSummaryLine(candidate)
            }
            if (log.topCandidates.isNotEmpty()) {
                Text("Top candidates", style = MaterialTheme.typography.titleSmall)
                log.topCandidates.forEach { candidate ->
                    CandidateSummaryLine(candidate)
                }
            }
            Text("Deadline: ${log.deadlineTime.toLocalTimeLabel()}")
            Text("Session: ${log.sessionId.shortId()}")
        } ?: Text("Not run")
    }
}

@Composable
private fun CandidateSummaryLine(candidate: AlarmWindowCandidateSummary) {
    val combined = candidate.combinedScore?.let { "%.4f".format(it) } ?: "-"
    Text(
        "${candidate.candidateTime.toLocalTimeLabel()} " +
            "(${candidate.minutesBeforeDeadline.formatOneDecimal()}m) " +
            "score $combined / ${candidate.decision}",
    )
}

@Composable
private fun ActionCard(
    title: String,
    copyText: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    CopyableCard(
        title = title,
        copyText = copyText,
        content = content,
    )
}

@Composable
private fun CopyableCard(
    title: String,
    copyText: String,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(copyText))
                    },
                ) {
                    CopyIcon()
                }
            }
            content()
        }
    }
}

@Composable
private fun CopyIcon() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = Offset(7.dp.toPx(), 3.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(3.dp.toPx(), 7.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            style = stroke,
        )
    }
}

@Composable
private fun DecisionPolicyComparisonCard(
    state: CollectorUiState,
    onToggleDecisionOption: (DecisionPolicyOption) -> Unit,
    onCompareSelectedDecisionOptions: () -> Unit,
    onCompareMultiSampleDecisionOptions: () -> Unit,
    onCompareRecent30DayDecisionOptions: () -> Unit,
) {
    CopyableCard(
        title = "Policy experiments",
        copyText = state.policyExperimentsCopyText(),
    ) {
        DecisionPolicyOption.entries.forEach { option ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = option in state.selectedDecisionOptions,
                    onCheckedChange = { onToggleDecisionOption(option) },
                )
                Text(option.label)
            }
        }
        Button(
            onClick = onCompareSelectedDecisionOptions,
            enabled = state.selectedDecisionOptions.isNotEmpty() && !state.isTfliteValidationRunning,
        ) {
            Text("Compare selected options")
        }
        Button(
            onClick = onCompareMultiSampleDecisionOptions,
            enabled = state.selectedDecisionOptions.isNotEmpty() && !state.isTfliteValidationRunning,
        ) {
            Text("Compare 9 samples")
        }
        Button(
            onClick = onCompareRecent30DayDecisionOptions,
            enabled = state.sessions.isNotEmpty() &&
                state.selectedDecisionOptions.isNotEmpty() &&
                !state.isTfliteValidationRunning,
        ) {
            Text("Compare 30 days")
        }
        state.decisionPolicyResults.forEach { result ->
            Text(result.option.label, style = MaterialTheme.typography.titleSmall)
            Text("Score: ${result.score?.let { "%.8f".format(it) } ?: "NOT_AVAILABLE"}")
            Text("Decision: ${result.decision}")
            Text("Reason: ${result.reason}")
        }
        state.multiSampleDecisionPolicyLog?.let { log ->
            Text("Policy summary", style = MaterialTheme.typography.titleSmall)
            Text("Source: ${log.sourceLabel}")
            log.sessionCount?.let { count ->
                Text("Sessions: $count")
            }
            Text("Samples: ${log.sampleCount}")
            Text("Threshold: ${log.threshold}")
            log.summaries.forEach { summary ->
                Text(summary.option.label, style = MaterialTheme.typography.titleSmall)
                Text("Mean score: ${summary.meanScore?.let { "%.8f".format(it) } ?: "NOT_AVAILABLE"}")
                Text("Available scores: ${summary.availableScoreCount}/${log.sampleCount}")
                Text("SMART_WAKE: ${summary.smartWakeCount}")
                Text("WAIT: ${summary.waitCount}")
                Text("SKIP_TOO_EARLY: ${summary.skipTooEarlyCount}")
                Text("SKIP_UNKNOWN_TOO_HIGH: ${summary.skipUnknownTooHighCount}")
                Text("NOT_AVAILABLE: ${summary.notAvailableCount}")
            }
        }
    }
}

@Composable
private fun DataQualityCard(stats: CollectorStats) {
    CopyableCard(
        title = "데이터 품질 요약",
        copyText = stats.dataQualityCopyText(),
    ) {
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

@Composable
private fun MonthlyCoverageCard(stats: CollectorStats) {
    CopyableCard(
        title = "최근 월별 기록",
        copyText = stats.monthlyCoverageCopyText(),
    ) {
        Text("기록 월 ${stats.monthsWithSessions} / 빈 월 ${stats.emptyMonthsBetweenFirstAndLast}")
        stats.recentMonthSummaries.forEach { month ->
            MonthlyCoverageRow(month)
        }
    }
}

@Composable
private fun MonthlyCoverageRow(month: MonthSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = month.month.toString(),
            modifier = Modifier.weight(1.1f),
        )
        Text(
            text = "${month.sessions} sessions",
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${month.stages} stages",
            modifier = Modifier.weight(1f),
        )
        Text(
            text = month.coverageStatus(),
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CollectorScreenPreview() {
    CollectorScreen(
        state = CollectorUiState(status = "미리보기"),
        onReadSleep = {},
        onShareExport = {},
        onRunTfliteValidation = {},
        onRunFloat16TfliteValidation = {},
        onRunMultiFloat32TfliteValidation = {},
        onRunMultiFloat16TfliteValidation = {},
        onRunTabularValidation = {},
        onRunMultiTabularValidation = {},
        onRunFloat16TabularValidation = {},
        onRunMultiFloat16TabularValidation = {},
        onRunInputBuilderValidation = {},
        onRunAlarmWindowEvaluation = {},
        onToggleDecisionOption = {},
        onCompareSelectedDecisionOptions = {},
        onCompareMultiSampleDecisionOptions = {},
        onCompareRecent30DayDecisionOptions = {},
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
    val isTfliteValidationRunning: Boolean = false,
    val tfliteValidationStatus: String = "TFLite validation has not run yet.",
    val tfliteValidationLog: AndroidInferenceValidationLog? = null,
    val multiSampleParityLog: MultiSampleParityValidationLog? = null,
    val tabularValidationLog: TabularInferenceValidationLog? = null,
    val multiSampleTabularValidationLog: MultiSampleTabularValidationLog? = null,
    val inputBuilderParityLog: InputBuilderParityValidationLog? = null,
    val alarmWindowEvaluationLog: AlarmWindowEvaluationLog? = null,
    val selectedValidationAction: ValidationAction? = null,
    val multiSampleDecisionPolicyLog: MultiSampleDecisionPolicyComparisonLog? = null,
    val selectedDecisionOptions: Set<DecisionPolicyOption> = setOf(
        DecisionPolicyOption.GRU_ONLY,
        DecisionPolicyOption.GRU_DEADLINE,
        DecisionPolicyOption.GRU_STRICT_DEADLINE_GATE,
        DecisionPolicyOption.GRU_UNKNOWN_GATE,
        DecisionPolicyOption.GRU_DEADLINE_UNKNOWN_GATE,
        DecisionPolicyOption.GRU_TABULAR,
    ),
    val decisionPolicyResults: List<DecisionPolicyResult> = emptyList(),
    val lastLoadedAt: Instant? = null,
    val loadedDataSource: String = "-",
)

private data class CachedSleepData(
    val sessions: List<SleepSession>,
    val file: File,
    val loadedAt: Instant,
)

private enum class ValidationAction {
    GRU_FLOAT32,
    GRU_FLOAT16,
    GRU_MULTI_FLOAT32,
    GRU_MULTI_FLOAT16,
    TABULAR_FLOAT32,
    TABULAR_MULTI_FLOAT32,
    TABULAR_FLOAT16,
    TABULAR_MULTI_FLOAT16,
    INPUT_BUILDERS,
    ALARM_WINDOW,
}

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

private fun MonthSummary.coverageStatus(): String = when {
    sessions >= 15 -> "OK"
    sessions > 0 -> "LOW"
    else -> "EMPTY"
}

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
    )
}

private fun SleepSession.durationMinutes(): Long =
    maxOf(0, Duration.between(startTime, endTime).toMinutes())

private fun com.sfpahsdev.mydream.sleep.SleepStage.durationMinutes(): Long =
    maxOf(0, Duration.between(startTime, endTime).toMinutes())

private fun CollectorUiState.statusCopyText(): String = buildString {
    appendLine("Status")
    appendLine(status)
    appendLine("sessions=${sessions.size}")
    appendLine("stages=${sessions.sumOf { it.stages.size }}")
    lastLoadedAt?.let { appendLine("last_loaded=${it.toLocalDateTimeLabel()}") }
    appendLine("source=$loadedDataSource")
    from?.let { appendLine("from=${it.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}") }
    to?.let { appendLine("to=${it.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}") }
    lookupDurationMs?.let { appendLine("lookup=${it.formatDurationMs()}") }
    exportFile?.let { appendLine("export=${it.name}") }
}

private fun CollectorUiState.modelValidationCopyText(): String = buildString {
    appendLine("Model validation")
    appendLine(tfliteValidationStatus)
    tfliteValidationLog?.let { log ->
        appendLine("GRU=${"%.8f".format(log.gruScoreAndroid)}")
        appendLine("GRU diff=${log.absDiff?.let { "%.8f".format(it) } ?: "-"}")
        appendLine("model=${log.modelVersion}")
    }
    multiSampleParityLog?.let { log ->
        appendLine("GRU samples=${log.sampleCount}")
        appendLine("GRU max diff=${"%.8f".format(log.maxAbsDiff)}")
        appendLine("GRU threshold flips=${log.thresholdFlipCount}")
    }
    tabularValidationLog?.let { log ->
        appendLine("Tabular=${"%.8f".format(log.tabularScoreAndroid)}")
        appendLine("Tabular diff=${log.absDiff?.let { "%.8f".format(it) } ?: "-"}")
        appendLine("tabular_model=${log.modelVersion}")
    }
    multiSampleTabularValidationLog?.let { log ->
        appendLine("Tabular samples=${log.sampleCount}")
        appendLine("Tabular max diff=${"%.8f".format(log.maxAbsDiff)}")
        appendLine("Tabular threshold flips=${log.thresholdFlipCount}")
    }
}

private fun InputBuilderParityValidationLog.toCopyText(): String = buildString {
    appendLine("Input builder validation")
    appendLine("result=${if (passed) "PASS" else "CHECK"}")
    appendLine(message)
    appendLine("sample=$sampleId")
    appendLine("session=$sessionId")
    appendLine("session_matched=$sessionMatched")
    appendLine("stage_sequence_mismatches=${sequenceMismatchCount ?: "-"}")
    appendLine(
        "max_feature_diff=${
            maxOfNotNull(
                contextRawMaxAbsDiff,
                contextScaledMaxAbsDiff,
                tabularRawMaxAbsDiff,
                tabularScaledMaxAbsDiff,
            )?.let { "%.8f".format(it) } ?: "-"
        }",
    )
}

private fun AlarmWindowEvaluationLog.toCopyText(): String = buildString {
    appendLine("Alarm window evaluation")
    appendLine("source=$sourceLabel")
    appendLine("deadline_policy=$deadlinePolicy")
    appendLine("selected=${selectedAlarmTime.toLocalTimeLabel()}")
    appendLine("decision=$selectedDecision")
    appendLine("reason=$selectedReason")
    appendLine("fallback=$fallbackUsed")
    appendLine("minutes_before_deadline=${"%.1f".format(selectedMinutesBeforeDeadline)}")
    appendLine("gru=${selectedGruScore?.let { "%.8f".format(it) } ?: "-"}")
    appendLine("tabular=${selectedTabularScore?.let { "%.8f".format(it) } ?: "-"}")
    appendLine("combined=${selectedCombinedScore?.let { "%.8f".format(it) } ?: "-"}")
    appendLine("candidates=$candidateCount, smart=$smartWakeCount, wait=$waitCount")
    lateWindowBestCandidate?.let { appendLine("best_last_10m=${it.toCopyLine()}") }
    if (topCandidates.isNotEmpty()) {
        appendLine("top_candidates")
        topCandidates.forEach { appendLine(it.toCopyLine()) }
    }
    appendLine("deadline=${deadlineTime.toLocalTimeLabel()}")
    appendLine("session=$sessionId")
}

private fun AlarmWindowCandidateSummary.toCopyLine(): String {
    val combined = combinedScore?.let { "%.4f".format(it) } ?: "-"
    return "${candidateTime.toLocalTimeLabel()} (${minutesBeforeDeadline.formatOneDecimal()}m) score=$combined decision=$decision"
}

private fun CollectorUiState.policyExperimentsCopyText(): String = buildString {
    appendLine("Policy experiments")
    appendLine("selected=${selectedDecisionOptions.joinToString { it.label }}")
    decisionPolicyResults.forEach { result ->
        appendLine("${result.option.label}: score=${result.score?.let { "%.8f".format(it) } ?: "NOT_AVAILABLE"}, decision=${result.decision}, reason=${result.reason}")
    }
    multiSampleDecisionPolicyLog?.let { log ->
        appendLine("source=${log.sourceLabel}")
        log.sessionCount?.let { appendLine("sessions=$it") }
        appendLine("samples=${log.sampleCount}, threshold=${log.threshold}")
        log.summaries.forEach { summary ->
            appendLine("${summary.option.label}: mean=${summary.meanScore?.let { "%.8f".format(it) } ?: "NOT_AVAILABLE"}, smart=${summary.smartWakeCount}, wait=${summary.waitCount}, unavailable=${summary.notAvailableCount}")
        }
    }
}

private fun CollectorStats.dataQualityCopyText(): String = buildString {
    appendLine("데이터 품질 요약")
    appendLine("first=${firstSessionDate ?: "-"}")
    appendLine("last=${lastSessionDate ?: "-"}")
    appendLine("total_sleep_minutes=$totalSleepMinutes")
    appendLine("average_sleep_minutes=$averageSleepMinutes")
    appendLine("training_eligible_sessions=$trainingEligibleSessions")
    appendLine("too_short_sessions=$tooShortSessions")
    appendLine("too_long_sessions=$tooLongSessions")
    appendLine("no_stage_sessions=$noStageSessions")
    appendLine("deep_stage_sessions=$deepStageSessions")
    appendLine("unknown_stage_count=$unknownStageCount")
    stageSummaries.forEach { summary ->
        appendLine("${summary.type}: count=${summary.count}, minutes=${summary.minutes}")
    }
}

private fun CollectorStats.monthlyCoverageCopyText(): String = buildString {
    appendLine("최근 월별 기록")
    appendLine("months_with_sessions=$monthsWithSessions")
    appendLine("empty_months=$emptyMonthsBetweenFirstAndLast")
    recentMonthSummaries.forEach { month ->
        appendLine("${month.month}: sessions=${month.sessions}, stages=${month.stages}, status=${month.coverageStatus()}")
    }
}

private fun String.shortId(): String =
    take(8)

private fun Instant.toLocalTimeLabel(): String =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

private fun Instant.toLocalDateTimeLabel(): String =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun String.toSleepSession(): SleepSession {
    val json = JSONObject(this)
    val stages = json.getJSONArray("stages")
    return SleepSession(
        id = json.getString("session_id"),
        startTime = Instant.parse(json.getString("start")),
        endTime = Instant.parse(json.getString("end")),
        stages = List(stages.length()) { index ->
            stages.getJSONObject(index).toSleepStage()
        },
    )
}

private fun JSONObject.toSleepStage(): SleepStage =
    SleepStage(
        type = SleepStageType.valueOf(getString("type")),
        startTime = Instant.parse(getString("start")),
        endTime = Instant.parse(getString("end")),
    )

private fun maxOfNotNull(vararg values: Float?): Float? =
    values.filterNotNull().maxOrNull()

private fun Float.formatOneDecimal(): String =
    "%.1f".format(this)

private fun AndroidInferenceValidationLog.toDecisionPolicyInput(
    tabularScore: Float? = tabularScoreServerExpected,
): DecisionPolicyInput =
    DecisionPolicyInput(
        gruScore = gruScoreAndroid,
        tabularScore = tabularScore,
        minutesBeforeDeadline = contextRaw22.getOrElse(1) { 0f },
        sequenceUnknownRatio = contextRaw22.getOrElse(18) { 1f },
    )

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
