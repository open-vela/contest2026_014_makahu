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

import android.app.Application
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeeklyLlmQueueTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun result() = JSONObject().put("title", "模型标题").put("summary", "这是测试服务返回的周总结原文。")
    @Test fun requestCreatesNoSummaryAndSurvivesReopen() {
        val now = System.currentTimeMillis()
        val id = repository.generateWeeklySummary(now)
        assertNull(repository.findCard(id))
        assertNull(repository.findCard("$id-llm"))
        repository.close(); open()
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        assertEquals(id, job.id)
        assertTrue(repository.completeWeeklyLlm(job, result(), "test-model"))
        assertEquals(result().getString("summary"), repository.findCard("$id-llm")?.summary)
        assertFalse(repository.completeWeeklyLlm(job, result(), "test-model"))
    }
    @Test fun expiredLeaseRejectsLateResponseAndFailureRemainsRetryable() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val old = requireNotNull(repository.claimWeeklyLlm(now))
        assertNull(repository.claimWeeklyLlm(now + 1000))
        val current = requireNotNull(repository.claimWeeklyLlm(now + 180001))
        assertFalse(repository.completeWeeklyLlm(old, result(), "old"))
        repository.failWeeklyLlm(current, now + 180002)
        assertNull(repository.findCard("${old.id}-llm"))
        assertNull(repository.claimWeeklyLlm(now + 180003))
        repository.generateWeeklySummary(now)
        assertNotNull(repository.claimWeeklyLlm(now + 180004))
    }
    @Test fun resultReceiptFailureRollsBackCardAndKeepsLease() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_weekly BEFORE INSERT ON events WHEN NEW.type = 'WeeklySummaryGenerated' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository.completeWeeklyLlm(job, result(), "test") }
        assertNull(repository.findCard("${job.id}-llm"))
        repository.writableDatabase.execSQL("DROP TRIGGER fail_weekly")
        assertTrue(repository.completeWeeklyLlm(job, result(), "test"))
    }
    @Test fun deletedLlmSummaryIsNotRegeneratedByScheduleOrReplayedResponse() {
        val now = System.currentTimeMillis()
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        repository.completeWeeklyLlm(job, result(), "test")
        repository.deleteCards(listOf("${job.id}-llm"))
        repository.generateWeeklySummary(now)
        assertNull(repository.claimWeeklyLlm(now + 200000))
        assertFalse(repository.completeWeeklyLlm(job, result(), "test"))
        assertNull(repository.findCard("${job.id}-llm"))
    }
}
