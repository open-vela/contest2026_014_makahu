package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CareSchedulerDatabaseTest {
    private lateinit var repository: BufferRepository
    private lateinit var scheduler: BufferCareScheduler
    @Before fun open() {
        val context = RuntimeEnvironment.getApplication()
        repository = BufferRepository(context)
        scheduler = BufferCareScheduler(context, repository)
    }
    @After fun close() { scheduler.stop(); repository.close() }

    @Test fun reminderDatabaseFailureDoesNotEscapeAndNextReconciliationRetries() {
        repository.setMode("focus")
        repository.writableDatabase.execSQL("UPDATE runtime_settings SET value = '1' WHERE key = 'focus_next_at'")
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_reminder BEFORE INSERT ON events WHEN NEW.type = 'FocusReminderShown'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        scheduler.awaitAlarm()
        assertEquals(1L, repository.focusNextReminderAt())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_reminder")
        scheduler.awaitAlarm()
        assertTrue(repository.focusNextReminderAt() > System.currentTimeMillis())
        val count = repository.eventCount()
        scheduler.awaitAlarm()
        assertEquals(count, repository.eventCount())
    }

    @Test fun failedModeReadCanRecoverWithoutRecreatingScheduler() {
        repository.writableDatabase.execSQL("ALTER TABLE runtime_settings RENAME TO temporarily_unavailable")
        scheduler.awaitAlarm()
        repository.writableDatabase.execSQL("ALTER TABLE temporarily_unavailable RENAME TO runtime_settings")
        repository.setMode("focus")
        val next = repository.focusNextReminderAt()
        scheduler.awaitAlarm()
        assertEquals(next, repository.focusNextReminderAt())
        assertEquals("focus", repository.mode())
    }

    @Test fun refusedDeliveryRetriesWithoutAdvancingDeadlineAgain() {
        var attempts = 0
        scheduler.stop()
        scheduler = BufferCareScheduler(RuntimeEnvironment.getApplication(), repository) { ++attempts > 1 }
        repository.setMode("focus")
        repository.fireFocusReminder(System.currentTimeMillis())
        val next = repository.focusNextReminderAt()
        scheduler.awaitAlarm()
        scheduler.awaitAlarm()
        scheduler.awaitAlarm()
        assertEquals(2, attempts)
        assertEquals(next, repository.focusNextReminderAt())
    }
    @Test fun pendingDeliverySurvivesRepositoryReopen() {
        repository.setMode("focus")
        repository.fireFocusReminder(System.currentTimeMillis())
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        var attempts = 0
        repository.deliverPendingFocusReminder { attempts++; true }
        repository.deliverPendingFocusReminder { attempts++; true }
        assertEquals(1, attempts)
    }
    @Test fun snoozeAndModeExitCancelPendingDelivery() {
        repository.setMode("focus")
        repository.fireFocusReminder(System.currentTimeMillis())
        repository.deferFocusReminder(System.currentTimeMillis(), 60000, "snooze")
        repository.deliverPendingFocusReminder { fail("snoozed reminder must not post"); true }
        repository.fireFocusReminder(System.currentTimeMillis())
        repository.setMode("normal")
        repository.deliverPendingFocusReminder { fail("inactive reminder must not post"); true }
    }
    @Test fun notificationReceiptFailureRetainsPendingDelivery() {
        repository.setMode("focus")
        repository.fireFocusReminder(System.currentTimeMillis())
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_post BEFORE INSERT ON events WHEN NEW.type = 'FocusNotificationPosted'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        var posted = 0
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            repository.deliverPendingFocusReminder { posted++; true }
        }
        repository.writableDatabase.execSQL("DROP TRIGGER fail_post")
        repository.deliverPendingFocusReminder { posted++; true }
        repository.deliverPendingFocusReminder { posted++; true }
        assertEquals(2, posted)
    }

    @Test fun failedSnoozeDoesNotEscapeOrDiscardPendingReminder() {
        repository.setMode("focus")
        repository.fireFocusReminder(System.currentTimeMillis())
        val next = repository.focusNextReminderAt()
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_snooze BEFORE INSERT ON events WHEN NEW.type = 'FocusReminderDeferred'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        scheduler.onReminderAction(BufferCareScheduler.ACTION_SNOOZE)
        assertEquals(next, repository.focusNextReminderAt())
        var attempts = 0
        repository.deliverPendingFocusReminder { attempts++; false }
        assertEquals(1, attempts)
        repository.writableDatabase.execSQL("DROP TRIGGER fail_snooze")
        scheduler.onReminderAction(BufferCareScheduler.ACTION_SNOOZE)
        repository.deliverPendingFocusReminder { fail("snoozed reminder must not post"); true }
        assertTrue(repository.focusNextReminderAt() < next)
    }
    @Test fun reminderActionsIgnoreInactiveModeAndUnknownAction() {
        val count = repository.eventCount()
        scheduler.onReminderAction(BufferCareScheduler.ACTION_SKIP)
        scheduler.onReminderAction("unknown")
        assertEquals(count, repository.eventCount())
        repository.setMode("focus")
        val next = repository.focusNextReminderAt()
        scheduler.onReminderAction("unknown")
        assertEquals(next, repository.focusNextReminderAt())
    }
}
