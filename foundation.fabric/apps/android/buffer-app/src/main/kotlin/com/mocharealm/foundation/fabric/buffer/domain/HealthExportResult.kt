package com.mocharealm.foundation.fabric.buffer.domain

sealed interface HealthExportResult {
    data class Synced(val recordType: String, val recordId: String? = null, val clientRecordId: String? = null) : HealthExportResult
    data class NeedsDetail(val message: String) : HealthExportResult
    data class Unsupported(val message: String) : HealthExportResult
    data class Failed(val message: String) : HealthExportResult
}
