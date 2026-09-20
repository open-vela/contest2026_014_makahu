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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardReturnTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun task(): BufferCard {
        val original = repository.createTextCapture("待办回流")
        return requireNotNull(repository.reviewClassification(original, ClassificationDecision.EDIT, "task_card", null))
    }
    private fun due(id: String): Long = repository.readableDatabase.rawQuery(
        "SELECT due_at FROM card_returns WHERE card_id = ?", arrayOf(id)).use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun count(): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE type = 'CardResurfaced'", null).use { it.moveToFirst(); it.getInt(0) }
    @Test fun customTimeCanBeChangedWithoutLosingOriginalState() {
        val card = task()
        val first = System.currentTimeMillis() + 3_600_000
        repository.performCardAction(card.cardId, "later", requestedReturnAt = first)
        val second = first + 86_400_000
        repository.performCardAction(card.cardId, "later", requestedReturnAt = second)
        assertEquals(second, due(card.cardId))
        assertEquals(second, repository.reconcileCardReturns(first))
        repository.reconcileCardReturns(second)
        assertEquals(card.state, repository.cards().first().state)
        assertEquals(1, count())
    }
    @Test fun invalidCustomRequestsLeaveExistingPlanUntouched() {
        val card = task()
        repository.performCardAction(card.cardId, "later")
        val deadline = due(card.cardId)
        val countBefore = repository.eventCount()
        assertThrows(IllegalArgumentException::class.java) {
            repository.performCardAction(card.cardId, "later", requestedReturnAt = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.performCardAction(card.cardId, "done", requestedReturnAt = deadline)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.performCardAction(card.cardId, "later", "remote-receipt", deadline)
        }
        assertEquals(deadline, due(card.cardId))
        assertEquals(countBefore, repository.eventCount())
    }
    @Test fun presetsAndCustomTimesHandleWeekendBoundariesAndDst() {
        fun instant(s: String) = java.time.Instant.parse(s).toEpochMilli()
        val utc = java.time.ZoneId.of("UTC")
        assertEquals(instant("2026-09-26T09:00:00Z"), CardReturnTime.weekend(instant("2026-09-26T08:00:00Z"), utc))
        assertEquals(instant("2026-10-03T09:00:00Z"), CardReturnTime.weekend(instant("2026-09-26T09:00:00Z"), utc))
        val ny = java.time.ZoneId.of("America/New_York")
        assertEquals(instant("2026-03-08T13:00:00Z"), CardReturnTime.tomorrow(instant("2026-03-07T16:00:00Z"), ny))
        assertThrows(IllegalArgumentException::class.java) {
            CardReturnTime.custom(java.time.LocalDate.of(2026, 3, 8), java.time.LocalTime.of(2, 30), ny, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CardReturnTime.custom(java.time.LocalDate.of(2026, 11, 1), java.time.LocalTime.of(1, 30), ny, 0)
        }
        assertEquals(instant("2026-09-26T10:15:00Z"), CardReturnTime.custom(
            java.time.LocalDate.of(2026, 9, 26), java.time.LocalTime.of(10, 15), utc, 0))
    }
    @Test fun tomorrowReturnSurvivesReopenRestoresStateAndRisesAboveNewerCards() {
        val old = task()
        repository.performCardAction(old.cardId, "later", "later-one")
        val deadline = due(old.cardId)
        val local = java.time.Instant.ofEpochMilli(deadline).atZone(java.time.ZoneId.systemDefault())
        assertEquals(9, local.hour); assertEquals(0, local.minute)
        assertTrue(repository.cardsForMode("normal").none { it.cardId == old.cardId })
        assertEquals(deadline, repository.reconcileCardReturns(deadline - 1))
        repository.close(); repository = BufferRepository(RuntimeEnvironment.getApplication())
        task()
        assertNull(repository.reconcileCardReturns(deadline))
        assertEquals(old.cardId, repository.cardsForMode("normal").first().cardId)
        assertEquals(old.state, repository.cardsForMode("normal").first().state)
        repository.reconcileCardReturns(deadline + 1000)
        assertEquals(1, count())
    }
    @Test fun replayDoesNotRescheduleAndCompletingCancelsReturn() {
        val old = task()
        repository.performCardAction(old.cardId, "later", "receipt")
        val deadline = due(old.cardId)
        repository.performCardAction(old.cardId, "later", "receipt")
        assertEquals(deadline, due(old.cardId))
        repository.performCardAction(old.cardId, "done")
        assertNull(repository.reconcileCardReturns(deadline))
        assertEquals(0, count())
        assertTrue(repository.cards().none { it.cardId == old.cardId })
    }
    @Test fun failedEventRollsBackReturnAndRetriesOnce() {
        val old = task()
        repository.performCardAction(old.cardId, "later")
        val deadline = due(old.cardId)
        repository.writableDatabase.execSQL("""CREATE TRIGGER fail_return BEFORE INSERT ON events
            WHEN NEW.type = 'CardResurfaced' BEGIN SELECT RAISE(ABORT, 'test'); END""")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository.reconcileCardReturns(deadline) }
        assertEquals(deadline, due(old.cardId))
        assertTrue(repository.cardsForMode("normal").none { it.cardId == old.cardId })
        repository.writableDatabase.execSQL("DROP TRIGGER fail_return")
        repository.reconcileCardReturns(deadline)
        assertEquals(1, count())
    }
    @Test fun version17MigrationPreservesCards() {
        val old = task()
        repository.writableDatabase.execSQL("DROP TABLE card_returns")
        repository.writableDatabase.version = 17
        repository.close(); repository = BufferRepository(RuntimeEnvironment.getApplication())
        repository.performCardAction(old.cardId, "later")
        repository.reconcileCardReturns(due(old.cardId))
        assertEquals(old.cardId, repository.cards().first().cardId)
    }
}
