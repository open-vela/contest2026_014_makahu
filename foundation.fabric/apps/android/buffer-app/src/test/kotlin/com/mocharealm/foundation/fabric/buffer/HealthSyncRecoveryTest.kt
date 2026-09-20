package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.DatabaseSettings
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferHttp
import com.mocharealm.foundation.fabric.buffer.data.llm.QuickAnswerResult
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmKey
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationProposal
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferCaptureAgent
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferTranscriptionWorker
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMoAsrClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAsrAudio
import com.mocharealm.foundation.fabric.buffer.data.llm.WeeklyLlmJob
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyLlmWorker
import com.mocharealm.foundation.fabric.buffer.data.device.BufferWire
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBlePeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaBleDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPeer
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPairing
import com.mocharealm.foundation.fabric.buffer.data.device.VelaDiscovery
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResult
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentResultReader
import com.mocharealm.foundation.fabric.buffer.data.device.PendingVelaEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStatus
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentStore
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel
import com.mocharealm.foundation.fabric.buffer.data.device.EnrollmentView
import com.mocharealm.foundation.fabric.buffer.data.device.VelaGattEnrollment
import com.mocharealm.foundation.fabric.buffer.data.device.toJson
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlan
import com.mocharealm.foundation.fabric.buffer.domain.BedtimePlanner
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeState
import com.mocharealm.foundation.fabric.buffer.domain.CalendarPriority
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipt
import com.mocharealm.foundation.fabric.buffer.domain.CalendarReceipts
import com.mocharealm.foundation.fabric.buffer.domain.CalendarRecovery
import com.mocharealm.foundation.fabric.buffer.domain.CalendarWriteSelection
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationChange
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import com.mocharealm.foundation.fabric.buffer.domain.EnrollmentAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig
import com.mocharealm.foundation.fabric.buffer.domain.HealthExportResult
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncOutcome
import com.mocharealm.foundation.fabric.buffer.domain.HealthSyncRecovery
import com.mocharealm.foundation.fabric.buffer.domain.LifeLogCategories
import com.mocharealm.foundation.fabric.buffer.domain.PhoneAuthentication
import com.mocharealm.foundation.fabric.buffer.domain.RabbitHoleSession
import com.mocharealm.foundation.fabric.buffer.domain.RecordingDestination
import com.mocharealm.foundation.fabric.buffer.domain.RecordingFinalizer
import com.mocharealm.foundation.fabric.buffer.domain.RecordingResult
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.WeeklyReflection
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule
import com.mocharealm.foundation.fabric.buffer.domain.LineTooLongException
import com.mocharealm.foundation.fabric.buffer.domain.readBoundedLine
import com.mocharealm.foundation.fabric.buffer.domain.takeCodePoints
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCapture
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferWeeklySummary
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyTheme
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyDeferredTask
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklySuggestion
import com.mocharealm.foundation.fabric.buffer.domain.model.WeeklyLifeCategory
import com.mocharealm.foundation.fabric.buffer.domain.model.displayAnswer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HealthSyncRecoveryTest {
    private val synced = HealthExportResult.Synced("HydrationRecord", "provider-id", "buffer-card")

    @Test fun existingReceiptRestoresWithoutExternalWrite() {
        var restored = false
        val result = HealthSyncRecovery.sync({ true }, { restored = true },
            { error("must not export") }, { fail("must not commit") }, { fail(it) })
        assertTrue(result.success)
        assertTrue(restored)
    }
    @Test fun localFailureIsReportedWithoutThrowing() {
        var detail = ""
        val result = HealthSyncRecovery.sync({ false }, {}, { synced },
            { error("disk full") }, { detail = it })
        assertFalse(result.success)
        assertTrue(detail.contains("已写入"))
    }
    @Test fun failureReceiptFailureIsAlsoHandled() {
        val result = HealthSyncRecovery.sync({ false }, {},
            { HealthExportResult.Failed("permission revoked") }, {}, { error("disk full") })
        assertFalse(result.success)
        assertTrue(result.message.contains("permission revoked"))
        assertTrue(result.message.contains("失败记录也未保存"))
    }
    @Test fun failedReceiptLookupDoesNotWriteExternally() {
        val result = HealthSyncRecovery.sync({ error("database unavailable") }, {},
            { error("must not export") }, {}, {})
        assertFalse(result.success)
        assertEquals("database unavailable", result.message)
    }
    @Test fun retriesCommitProviderIdentity() {
        var receipt = false
        var failCommit = true
        var committed: HealthExportResult.Synced? = null
        fun attempt() = HealthSyncRecovery.sync({ receipt }, {}, { synced }, {
            if (failCommit) error("disk full")
            committed = it
            receipt = true
        }, {})
        assertFalse(attempt().success)
        failCommit = false
        assertTrue(attempt().success)
        assertEquals(synced, committed)
    }
    @Test fun concurrentActivitiesExportOnlyOnceAfterReceiptCommits() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            var written = false
            var exports = 0
            val attempts = (1..20).map {
                executor.submit<HealthSyncOutcome> {
                    HealthSyncRecovery.sync({ written }, {}, { exports++; synced }, { written = true }, {})
                }
            }
            attempts.forEach { assertTrue(it.get(5, TimeUnit.SECONDS).success) }
            assertEquals(1, exports)
        } finally { executor.shutdownNow() }
    }
    @Test fun missingDetailNeverCommitsSuccess() {
        val result = HealthSyncRecovery.sync({ false }, {},
            { HealthExportResult.NeedsDetail("需要明确时长") }, { fail("must not commit") }, {})
        assertFalse(result.success)
        assertEquals("需要明确时长", result.message)
    }
}
