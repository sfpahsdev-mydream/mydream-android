package com.sfpahsdev.mydream.sleep

import java.time.LocalDateTime

interface SleepDataSource {
    suspend fun requestReadPermission(): SleepDataSourceResult<Unit>

    suspend fun getSleepSessions(
        from: LocalDateTime,
        to: LocalDateTime,
    ): SleepDataSourceResult<List<SleepSession>>
}

sealed interface SleepDataSourceResult<out T> {
    data class Success<T>(val value: T) : SleepDataSourceResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : SleepDataSourceResult<Nothing>
}
