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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositoryTransactionTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }

    private fun confirmed(kind: String, category: String? = null): BufferCard {
        val card = repository.createTextCapture("数据库恢复测试")
        return requireNotNull(repository.reviewClassification(card, ClassificationDecision.EDIT, kind, category))
    }
    private fun rejectReceipt(type: String) {
        require(type.matches(Regex("[A-Za-z]+")))
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_receipt BEFORE INSERT ON events
            WHEN NEW.type = '$type' BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END
        """.trimIndent())
    }
    private fun allowReceipts() { repository.writableDatabase.execSQL("DROP TRIGGER fail_receipt") }
    private fun current(card: BufferCard) = requireNotNull(repository.cards(100).find { it.cardId == card.cardId })

    @Test fun calendarCreationRollsBackCardAndActionWhenReceiptFails() {
        val card = confirmed("task_card")
        val count = repository.eventCount()
        rejectReceipt("CalendarEventCreated")
        assertThrows(SQLiteException::class.java) {
            repository.commitCalendarChange(card.cardId, card.captureId, "101", false)
        }
        assertEquals(card.state, current(card).state)
        assertTrue("calendar" in current(card).actions)
        assertEquals(count, repository.eventCount())
        assertNull(repository.calendarReceipt(card.cardId, card.captureId))
        allowReceipts()
        repository.commitCalendarChange(card.cardId, card.captureId, "101", false)
        assertEquals("101", repository.calendarReceipt(card.cardId, card.captureId)?.eventId)
        assertTrue("remove_calendar" in current(card).actions)
    }
    @Test fun calendarDeletionRollsBackAndCanBeRetriedAfterReopen() {
        val card = confirmed("task_card")
        repository.commitCalendarChange(card.cardId, card.captureId, "102", false)
        rejectReceipt("CalendarEventDeleted")
        assertThrows(SQLiteException::class.java) {
            repository.commitCalendarChange(card.cardId, card.captureId, "102", true)
        }
        assertTrue("remove_calendar" in current(card).actions)
        assertEquals(false, repository.calendarReceipt(card.cardId, card.captureId)?.deleted)
        allowReceipts()
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        repository.commitCalendarChange(card.cardId, card.captureId, "102", true)
        assertEquals(true, repository.calendarReceipt(card.cardId, card.captureId)?.deleted)
        assertTrue("calendar" in current(card).actions)
    }
    @Test fun healthReceiptAndCardCommitTogether() {
        val card = confirmed("weekly_card", "hydration")
        val count = repository.eventCount()
        rejectReceipt("HealthDataWritten")
        assertThrows(SQLiteException::class.java) {
            repository.markHealthSynced(card.cardId, "HydrationRecord", "hc-id", "buffer-${card.cardId}")
        }
        assertFalse(repository.hasHealthDataWritten(card.cardId))
        assertEquals(card.state, current(card).state)
        assertEquals(count, repository.eventCount())
        allowReceipts()
        repository.markHealthSynced(card.cardId, "HydrationRecord", "hc-id", "buffer-${card.cardId}")
        assertTrue(repository.hasHealthDataWritten(card.cardId))
        val committedCount = repository.eventCount()
        repository.markHealthSynced(card.cardId, "HydrationRecord")
        assertEquals(committedCount, repository.eventCount())
        assertEquals("health_synced", current(card).state)
    }
    @Test fun classificationFailurePreservesProposalForRetry() {
        val card = repository.createTextCapture("待确认分类")
        val count = repository.eventCount()
        rejectReceipt("ClassificationEdited")
        assertThrows(SQLiteException::class.java) {
            repository.reviewClassification(card, ClassificationDecision.EDIT, "task_card")
        }
        assertFalse(repository.isClassificationConfirmed(card.cardId))
        assertEquals(card.kind, current(card).kind)
        assertEquals(count, repository.eventCount())
        allowReceipts()
        repository.reviewClassification(card, ClassificationDecision.EDIT, "task_card")
        assertTrue(repository.isClassificationConfirmed(card.cardId))
    }

    @Test fun legacyCalendarReceiptRequiresUniqueCaptureOwnerInDatabase() {
        val card = confirmed("task_card")
        repository.recordCalendarWrite(card.cardId, card.captureId, "103", true)
        repository.writableDatabase.execSQL(
            "UPDATE events SET payload = ? WHERE type = 'CalendarEventCreated'",
            arrayOf("{\"event_id\":\"103\"}"),
        )
        assertEquals("103", repository.calendarReceipt(card.cardId, card.captureId)?.eventId)
        val other = confirmed("task_card")
        repository.writableDatabase.execSQL("UPDATE cards SET capture_id = ? WHERE card_id = ?",
            arrayOf(card.captureId, other.cardId))
        assertNull(repository.calendarReceipt(card.cardId, card.captureId))
        assertNull(repository.calendarReceipt(other.cardId, card.captureId))
    }

    @Test fun focusRevisionSurvivesRepositoryReopenAndSnoozeChangesIt() {
        repository.setMode("focus")
        val initial = repository.focusReminderSnapshot(System.currentTimeMillis())
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        val restored = repository.focusReminderSnapshot(System.currentTimeMillis())
        assertEquals(initial.getString("revision"), restored.getString("revision"))
        val now = System.currentTimeMillis()
        repository.deferFocusReminder(now, 60000, "snooze")
        val deferred = repository.focusReminderSnapshot(now)
        assertNotEquals(initial.getString("revision"), deferred.getString("revision"))
        assertEquals(60000L, deferred.getLong("remaining_ms"))
        assertEquals(0L, deferred.getLong("notice_remaining_ms"))
        assertTrue(deferred.getBoolean("clear_notice"))
    }
}
