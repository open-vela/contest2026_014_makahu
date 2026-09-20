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
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardActionDatabaseTest {
    private lateinit var first: BufferRepository
    private lateinit var second: BufferRepository
    @Before fun open() {
        first = BufferRepository(RuntimeEnvironment.getApplication())
        second = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { second.close(); first.close() }
    @Test fun duplicateConcurrentActionsCommitOnce() {
        val card = first.createTextCapture("操作去重测试")
        val count = first.eventCount()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val pending = listOf(first, second).map { repo ->
                executor.submit<BufferCard?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    repo.performCardAction(card.cardId, "later", "action-shared")
                }
            }
            start.countDown()
            val results = pending.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals("later", results[0]?.state)
            assertEquals(count + 1, first.eventCount())
            assertEquals("later", first.actionResultState("action-shared"))
        } finally { executor.shutdownNow() }
    }
    @Test fun receiptFailureRollsBackActionAndRetrySucceeds() {
        val card = first.createTextCapture("失败重试测试")
        val count = first.eventCount()
        first.writableDatabase.execSQL("""
            CREATE TRIGGER fail_action BEFORE INSERT ON action_receipts
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) {
            first.performCardAction(card.cardId, "later", "action-retry")
        }
        assertEquals(card, first.cardForCapture(requireNotNull(card.captureId)))
        assertEquals(count, first.eventCount())
        assertNull(first.actionResultState("action-retry"))
        first.writableDatabase.execSQL("DROP TRIGGER fail_action")
        assertEquals("later", second.performCardAction(card.cardId, "later", "action-retry")?.state)
    }
    @Test fun operationIdCannotBeReusedForDifferentActionOrCard() {
        val card = first.createTextCapture("第一张")
        val other = first.createTextCapture("第二张")
        first.performCardAction(card.cardId, "later", "action-bound")
        val count = first.eventCount()
        assertThrows(IllegalArgumentException::class.java) {
            second.performCardAction(card.cardId, "archive", "action-bound")
        }
        assertThrows(IllegalArgumentException::class.java) {
            second.performCardAction(other.cardId, "later", "action-bound")
        }
        assertEquals(count, first.eventCount())
        assertEquals(other, first.cardForCapture(requireNotNull(other.captureId)))
    }
    @Test fun oldRetryDoesNotReapplyStateAfterNewerOperation() {
        val card = first.createTextCapture("较新的操作优先")
        first.performCardAction(card.cardId, "later", "old-action")
        first.performCardAction(card.cardId, "archive", "new-action")
        val count = first.eventCount()
        assertEquals("archived", second.performCardAction(card.cardId, "later", "old-action")?.state)
        assertEquals("later", second.actionResultState("old-action"))
        assertEquals(count, first.eventCount())
    }
}
