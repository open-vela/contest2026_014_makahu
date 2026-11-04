package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BedtimeDatabaseTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun evening(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 20, 23, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    @Test fun failedDailyMarkerRollsBackModeAndEvents() {
        repository.setBedtimeSchedule("22:30")
        val count = repository.eventCount()
        repository.writableDatabase.execSQL("""
            CREATE TRIGGER fail_bedtime BEFORE INSERT ON runtime_settings
            WHEN NEW.key = 'bedtime_last_triggered_day'
            BEGIN SELECT RAISE(ABORT, 'injected failure'); END
        """.trimIndent())
        assertThrows(SQLiteException::class.java) { repository.reconcileBedtime(evening()) }
        assertEquals("normal", repository.mode())
        assertEquals(count, repository.eventCount())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_bedtime")
        repository.reconcileBedtime(evening())
        assertEquals("bedtime", repository.mode())
        val committed = repository.eventCount()
        repository.setMode("normal")
        repository.reconcileBedtime(evening() + 1000)
        assertEquals("normal", repository.mode())
        assertEquals(committed + 1, repository.eventCount())
    }
    @Test fun focusDelaySurvivesReopenAndTriggersOnceOnExit() {
        repository.setBedtimeSchedule("22:30")
        repository.setMode("focus")
        val retry = repository.reconcileBedtime(evening())
        assertEquals("focus", repository.mode())
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(retry, repository.reconcileBedtime(evening() + 1000))
        repository.setMode("normal")
        repository.reconcileBedtime(evening() + 2000)
        assertEquals("bedtime", repository.mode())
        val count = repository.eventCount()
        repository.reconcileBedtime(evening() + 3000)
        assertEquals(count, repository.eventCount())
    }
    @Test fun version14BedtimePreferencesMigrateOnce() {
        repository.writableDatabase.execSQL("ALTER TABLE classification_jobs DROP COLUMN lease_id")
        repository.writableDatabase.version = 14
        repository.close()
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("buffer", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("bedtime_schedule", "21:15").commit())
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("21:15", repository.bedtimeSchedule())
        repository.setBedtimeSchedule("23:10")
        repository.close()
        repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals("23:10", repository.bedtimeSchedule())
    }
}
