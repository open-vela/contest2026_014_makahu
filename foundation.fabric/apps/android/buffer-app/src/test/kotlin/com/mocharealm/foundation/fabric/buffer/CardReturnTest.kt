package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.CardReturnTime
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

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
        repository.finishSourceRecovery()
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
        repository.finishSourceRecovery()
        repository.performCardAction(old.cardId, "later")
        repository.reconcileCardReturns(due(old.cardId))
        assertEquals(old.cardId, repository.cards().first().cardId)
    }
}
