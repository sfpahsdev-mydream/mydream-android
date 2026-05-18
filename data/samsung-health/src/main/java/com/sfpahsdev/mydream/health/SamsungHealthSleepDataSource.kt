package com.sfpahsdev.mydream.health

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.SleepSession as SamsungSleepSession
import com.samsung.android.sdk.health.data.data.entries.SleepSession.SleepStage as SamsungSleepStage
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.sfpahsdev.mydream.sleep.SleepDataSource
import com.sfpahsdev.mydream.sleep.SleepDataSourceResult
import com.sfpahsdev.mydream.sleep.SleepSession
import com.sfpahsdev.mydream.sleep.SleepStage
import com.sfpahsdev.mydream.sleep.SleepStageType
import java.time.LocalDateTime

class SamsungHealthSleepDataSource(
    private val context: Context,
    private val activityProvider: () -> Activity,
) : SleepDataSource {
    override suspend fun requestReadPermission(): SleepDataSourceResult<Unit> =
        runCatching {
            val store = HealthDataService.getStore(context)
            val permissions = sleepReadPermissions()
            val grantedPermissions = store.getGrantedPermissions(permissions)

            if (!grantedPermissions.containsAll(permissions)) {
                store.requestPermissions(permissions, activityProvider())
            }
        }.fold(
            onSuccess = { SleepDataSourceResult.Success(Unit) },
            onFailure = { SleepDataSourceResult.Failure(it.toUserMessage(), it) },
        )

    override suspend fun getSleepSessions(
        from: LocalDateTime,
        to: LocalDateTime,
    ): SleepDataSourceResult<List<SleepSession>> =
        runCatching {
            val store = HealthDataService.getStore(context)
            val sessions = mutableListOf<SleepSession>()
            generateMonthlyWindows(from, to).forEach { (windowStart, windowEnd) ->
                val request = DataTypes.SLEEP.readDataRequestBuilder
                    .setLocalTimeFilter(LocalTimeFilter.of(windowStart, windowEnd))
                    .build()

                sessions += store.readData(request)
                    .dataList
                    .flatMap { dataPoint -> dataPoint.toSleepSessions() }
            }

            sessions
                .distinctBy { it.id }
                .sortedBy { it.startTime }
        }.fold(
            onSuccess = { SleepDataSourceResult.Success(it) },
            onFailure = { SleepDataSourceResult.Failure(it.toUserMessage(), it) },
        )

    private fun sleepReadPermissions(): MutableSet<Permission> =
        mutableSetOf(Permission.of(DataTypes.SLEEP, AccessType.READ))

    private fun generateMonthlyWindows(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Sequence<Pair<LocalDateTime, LocalDateTime>> =
        generateSequence(from.toLocalDate().withDayOfMonth(1).atStartOfDay()) { start ->
            start.plusMonths(1).takeIf { it < to }
        }.map { start ->
            maxOf(start, from) to minOf(start.plusMonths(1), to)
        }

    private fun HealthDataPoint.toSleepSessions(): List<SleepSession> {
        val samsungSessions = getValue(DataType.SleepType.SESSIONS).orEmpty()
        val parentId = uid

        return samsungSessions.mapIndexed { index, samsungSession ->
            SleepSession(
                id = "$parentId-$index",
                startTime = samsungSession.startTime,
                endTime = samsungSession.endTime,
                stages = samsungSession.stages.orEmpty().map { it.toSleepStage() },
            )
        }
    }

    private fun SamsungSleepSession.toSleepSession(parentId: String, index: Int): SleepSession =
        SleepSession(
            id = "$parentId-$index",
            startTime = startTime,
            endTime = endTime,
            stages = stages.orEmpty().map { it.toSleepStage() },
        )

    private fun SamsungSleepStage.toSleepStage(): SleepStage =
        SleepStage(
            type = stage.name.toSleepStageType(),
            startTime = startTime,
            endTime = endTime,
        )

    private fun String.toSleepStageType(): SleepStageType =
        when (uppercase()) {
            "AWAKE" -> SleepStageType.Awake
            "LIGHT" -> SleepStageType.Light
            "DEEP" -> SleepStageType.Deep
            "REM" -> SleepStageType.Rem
            else -> SleepStageType.Unknown
        }

    private fun Throwable.toUserMessage(): String =
        when (this) {
            is ResolvablePlatformException -> message ?: "Samsung Health 상태 확인이 필요합니다."
            is HealthDataException -> message ?: "Samsung Health Data SDK 오류가 발생했습니다."
            else -> message ?: javaClass.simpleName
        }
}
