package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

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
