package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import android.app.Application
import android.app.NotificationManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CardReminderTest {
    private lateinit var repository: BufferRepository
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun open() { repository = BufferRepository(app) }
    @After fun close() { repository.close() }
    private fun task(): BufferCard {
        val card = repository.createTextCapture("该给花浇水了")
        return requireNotNull(repository.reviewClassification(card, ClassificationDecision.EDIT, "task_card", null))
    }
    private fun delivered(id: String): Boolean = repository.readableDatabase.rawQuery(
        "SELECT delivered FROM card_reminders WHERE card_id = ?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }
    private fun count(): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE type = 'CardReminderPosted'", null).use { it.moveToFirst(); it.getInt(0) }
    @Test fun bedtimeDefersWithoutMarkingPostedAndResumesAfterReopen() {
        val card = task(); val due = System.currentTimeMillis() + 3_600_000
        repository.performCardAction(card.cardId, "remind", requestedReturnAt = due)
        repository.setMode("bedtime")
        var calls = 0
        assertNull(repository.reconcileCardReminders(due, { calls++; true }, {}))
        assertFalse(delivered(card.cardId)); assertEquals(0, count())
        repository.close(); repository = BufferRepository(app)
        assertNull(repository.reconcileCardReminders(due + 60_000, { calls++; true }, {}))
        repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type = 'CardReminderDeferred'", null).use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
        assertEquals(0, calls)
        repository.setMode("normal")
        repository.reconcileCardReminders(due + 60_001, { calls++; true }, {})
        assertEquals(1, calls); assertTrue(delivered(card.cardId)); assertEquals(1, count())
    }
    @Test fun cancellingDuringBedtimeNeverPostsOnExit() {
        val card = task(); val due = System.currentTimeMillis() + 3_600_000
        repository.performCardAction(card.cardId, "remind", requestedReturnAt = due)
        repository.setMode("bedtime")
        repository.reconcileCardReminders(due, { fail("quiet"); true }, {})
        repository.performCardAction(card.cardId, "done")
        repository.reconcileCardReminders(due + 1, { fail("cancelled"); true }, {})
        repository.setMode("normal")
        repository.reconcileCardReminders(due + 2, { fail("cancelled"); true }, {})
        assertEquals(0, count())
        repository.readableDatabase.rawQuery("SELECT COUNT(*) FROM runtime_settings WHERE key LIKE 'card_reminder_quiet:%'", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }
    @Test fun bedtimeWithdrawsExistingNotificationWithoutReannouncingOnExit() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val card = task()
        repository.performCardAction(card.cardId, "remind")
        repository.writableDatabase.execSQL("UPDATE card_reminders SET due_at = 1")
        val scheduler = BufferCareScheduler(app, repository)
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            scheduler.awaitAlarm()
            assertNotNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            repository.setMode("bedtime"); scheduler.awaitAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            repository.setMode("normal"); scheduler.awaitAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            assertEquals(1, count())
        } finally { scheduler.stop() }
    }
    @Test fun permissionFailurePersistsAcrossReopenAndDeliveryRetriesOnce() {
        val card = task(); val due = System.currentTimeMillis() + 3_600_000
        repository.performCardAction(card.cardId, "remind", requestedReturnAt = due)
        assertEquals(due, repository.reconcileCardReminders(due - 1, { fail("early"); true }, {}))
        assertEquals(due + 60_000, repository.reconcileCardReminders(due, { false }, {}))
        assertFalse(delivered(card.cardId)); assertEquals(0, count())
        repository.close(); repository = BufferRepository(app)
        var calls = 0
        repository.reconcileCardReminders(due + 59_999, { calls++; true }, {})
        assertEquals(0, calls)
        repository.reconcileCardReminders(due + 60_000, { calls++; true }, {})
        repository.reconcileCardReminders(due + 120_000, { calls++; true }, {})
        assertEquals(1, calls); assertEquals(1, count()); assertTrue(delivered(card.cardId))
    }
    @Test fun completionCancelsAndReplayDoesNotCreateAnotherReminder() {
        val card = task()
        repository.performCardAction(card.cardId, "remind", "one-reminder")
        val before = repository.eventCount()
        repository.performCardAction(card.cardId, "remind", "one-reminder")
        assertEquals(before, repository.eventCount())
        repository.performCardAction(card.cardId, "done")
        val cancelled = mutableListOf<String>()
        assertNull(repository.reconcileCardReminders(System.currentTimeMillis() + 7_200_000,
            { fail("completed task must not notify"); true }, cancelled::add))
        assertEquals(listOf(card.cardId), cancelled)
        assertEquals(0, count())
    }
    @Test fun failedReceiptRollsBackDeliveryMarkerForRetry() {
        val card = task(); val due = System.currentTimeMillis() + 3_600_000
        repository.performCardAction(card.cardId, "remind", requestedReturnAt = due)
        repository.writableDatabase.execSQL("""CREATE TRIGGER fail_reminder BEFORE INSERT ON events
            WHEN NEW.type = 'CardReminderPosted' BEGIN SELECT RAISE(ABORT, 'test'); END""")
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.reconcileCardReminders(due, { true }, {})
        }
        assertFalse(delivered(card.cardId)); assertEquals(0, count())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_reminder")
        repository.reconcileCardReminders(due, { true }, {})
        assertTrue(delivered(card.cardId)); assertEquals(1, count())
    }
    @Test fun actualSchedulerPostsStableNotificationAndCancelsAfterDone() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val card = task()
        repository.performCardAction(card.cardId, "remind")
        repository.writableDatabase.execSQL("UPDATE card_reminders SET due_at = 1")
        val scheduler = BufferCareScheduler(app, repository)
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            scheduler.awaitAlarm()
            assertTrue(delivered(card.cardId))
            val posted = requireNotNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            assertEquals(card.cardId, CardReminderNavigation.cardId(shadowOf(posted.contentIntent).savedIntent))
            scheduler.awaitAlarm(); assertEquals(1, count())
            repository.performCardAction(card.cardId, "done")
            scheduler.awaitAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
        } finally { scheduler.stop() }
    }
    @Test fun migrationFrom18RetainsCardsAndCreatesReminders() {
        val card = task()
        repository.writableDatabase.execSQL("DROP TABLE card_reminders")
        repository.writableDatabase.version = 18
        repository.close(); repository = BufferRepository(app)
        repository.performCardAction(card.cardId, "remind")
        repository.reconcileCardReminders(System.currentTimeMillis() + 7_200_000, { true }, {})
        assertTrue(delivered(card.cardId))
    }
}
