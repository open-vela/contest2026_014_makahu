package com.mocharealm.foundation.fabric.buffer.domain

internal data class HealthSyncOutcome(val success: Boolean, val message: String)

/** Keep external writes and local receipt recovery serialized across Activities. */
internal object HealthSyncRecovery {
    @Synchronized
    fun sync(
        alreadyWritten: () -> Boolean,
        restore: () -> Unit,
        export: () -> HealthExportResult,
        commit: (HealthExportResult.Synced) -> Unit,
        recordFailure: (String) -> Unit,
    ): HealthSyncOutcome {
        var externallyWritten = false
        val detail = try {
            if (alreadyWritten()) {
                externallyWritten = true
                restore()
                return HealthSyncOutcome(true, "已恢复健康数据同步状态")
            }
            when (val result = export()) {
                is HealthExportResult.Synced -> {
                    externallyWritten = true
                    commit(result)
                    return HealthSyncOutcome(true, "已同步到 Health Connect")
                }
                is HealthExportResult.Failed -> result.message
                is HealthExportResult.NeedsDetail -> result.message
                is HealthExportResult.Unsupported -> result.message
            }
        } catch (error: Exception) {
            if (externallyWritten) "健康数据已写入，本地记录失败，可重试恢复"
            else error.message ?: "健康数据同步未完成，可重试"
        }
        val failure = runCatching { recordFailure(detail) }.exceptionOrNull()
        return HealthSyncOutcome(false, detail + if (failure != null) "；失败记录也未保存" else "")
    }
}
