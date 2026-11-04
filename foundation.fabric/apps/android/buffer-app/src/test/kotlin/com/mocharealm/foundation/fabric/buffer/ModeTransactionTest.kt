package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.FocusReminderConfig

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ModeTransactionTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun fail(type: String) {
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_mode BEFORE INSERT ON events WHEN NEW.type = '$type'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
    }
    @Test fun failedModeReceiptLeavesModeAndFocusUnchanged() {
        val count = repository.eventCount()
        fail("ModeChanged")
        assertThrows(SQLiteException::class.java) { repository.setMode("focus", "mode-retry") }
        assertEquals("normal", repository.mode())
        assertEquals(0L, repository.focusNextReminderAt())
        assertEquals(count, repository.eventCount())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_mode")
        repository.setMode("focus", "mode-retry")
        assertEquals("focus", repository.mode())
        assertTrue(repository.focusNextReminderAt() > 0)
    }
    @Test fun failedBedtimeDeckEventRollsBackModeAndTimer() {
        repository.setMode("focus")
        val next = repository.focusNextReminderAt()
        fail("BedtimeDeckUpdated")
        assertThrows(SQLiteException::class.java) { repository.setMode("bedtime", "bedtime-retry") }
        assertEquals("focus", repository.mode())
        assertEquals(next, repository.focusNextReminderAt())
    }
    @Test fun failedSnoozeKeepsDeadlineAndRevision() {
        repository.setMode("focus")
        val before = repository.focusReminderSnapshot(1000)
        fail("FocusReminderDeferred")
        assertThrows(SQLiteException::class.java) { repository.deferFocusReminder(1000, 60000, "snooze") }
        assertEquals(before.toString(), repository.focusReminderSnapshot(1000).toString())
    }
    @Test fun failedConfigurationEventRollsBackSettings() {
        val old = repository.focusReminderConfig()
        fail("FocusReminderConfigUpdated")
        assertThrows(SQLiteException::class.java) {
            repository.setFocusReminderConfig(FocusReminderConfig(600000, 60000))
        }
        assertEquals(old, repository.focusReminderConfig())
    }
    @Test fun oldModeRetryDoesNotRevertNewerMode() {
        repository.setMode("focus", "mode-old")
        repository.setMode("normal", "mode-new")
        val count = repository.eventCount()
        repository.setMode("focus", "mode-old")
        assertEquals("normal", repository.mode())
        assertEquals(count, repository.eventCount())
        assertEquals(0L, repository.focusNextReminderAt())
    }
    @Test fun version13PreferencesMigrateOnce() {
        val db = repository.writableDatabase
        db.execSQL("DROP TABLE runtime_settings")
        db.execSQL("ALTER TABLE classification_jobs DROP COLUMN lease_id")
        db.version = 13
        repository.close()
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("buffer", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("mode", "focus")
            .putLong("focus_interval_ms", 600000).putLong("focus_next_at", 601000)
            .putString("focus_revision", "focus-migrated").commit())
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("focus", repository.mode())
        assertEquals(600000L, repository.focusReminderConfig().intervalMs)
        assertEquals(601000L, repository.focusNextReminderAt())
        assertEquals("focus-migrated", repository.focusReminderSnapshot(1000).getString("revision"))
        assertTrue(preferences.edit().putString("mode", "normal").commit())
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("focus", repository.mode())
    }
}
