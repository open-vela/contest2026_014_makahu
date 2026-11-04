package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.WeeklySchedule

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeeklyScheduleTest {
    private lateinit var repository: BufferRepository
    private lateinit var oldZone: TimeZone
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val before = time("2026-09-20T19:00:00Z")
    private val due = time("2026-09-20T20:00:00Z")
    @Before fun open() {
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        repository = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { repository.close(); TimeZone.setDefault(oldZone) }
    private fun count(): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE type = 'WeeklySummaryRequested'", null).use { it.moveToFirst(); it.getInt(0) }
    @Test fun dueSurvivesReopenAndManualGenerationDoesNotDuplicate() {
        assertEquals(due, repository.reconcileWeekly(before))
        assertEquals(0, count())
        repository.generateWeeklySummary(before)
        repository.close(); repository = BufferRepository(RuntimeEnvironment.getApplication())
        assertEquals(due + 7 * 86_400_000L, repository.reconcileWeekly(due))
        repository.reconcileWeekly(due + 1000)
        assertEquals(1, count())
    }
    @Test fun disabledDoesNotGenerateAndLongAbsenceProducesOnlyLatestReport() {
        repository.reconcileWeekly(before)
        repository.setWeeklyAutoEnabled(false)
        assertNull(repository.reconcileWeekly(due))
        assertEquals(0, count())
        repository.setWeeklyAutoEnabled(true)
        repository.reconcileWeekly(before)
        repository.reconcileWeekly(time("2026-10-19T10:00:00Z"))
        assertEquals(1, count())
        repository.readableDatabase.rawQuery("SELECT created_at FROM events WHERE type = 'WeeklySummaryRequested'", null).use {
            assertTrue(it.moveToFirst()); assertEquals(time("2026-10-18T20:00:00Z"), it.getLong(0))
        }
    }
    @Test fun failedReceiptRollsBackReportAndRetainsDueForRetry() {
        repository.reconcileWeekly(before)
        repository.writableDatabase.execSQL("""CREATE TRIGGER fail_weekly BEFORE INSERT ON events
            WHEN NEW.type = 'WeeklySummaryRequested' BEGIN SELECT RAISE(ABORT, 'test'); END""")
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository.reconcileWeekly(due) }
        assertEquals(0, count())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_weekly")
        repository.reconcileWeekly(due + 1000)
        assertEquals(1, count())
    }
    @Test fun independentRepositoriesGenerateOneCardAndEventUnderRace() {
        val other = BufferRepository(RuntimeEnvironment.getApplication())
        val executor = Executors.newFixedThreadPool(2)
        try {
            repository.reconcileWeekly(before)
            val ready = java.util.concurrent.CountDownLatch(1)
            val first = executor.submit { ready.await(); repository.reconcileWeekly(due) }
            val second = executor.submit { ready.await(); other.generateWeeklySummary(due) }
            ready.countDown()
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS)
            assertEquals(1, count())
        } finally { executor.shutdownNow(); other.close() }
    }
    @Test fun weeklyTimeFollowsLocalSundayAcrossDst() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(time("2026-03-09T00:00:00Z"), WeeklySchedule.next(time("2026-03-08T01:00:00Z"), zone))
        assertEquals(time("2026-11-02T01:00:00Z"), WeeklySchedule.next(time("2026-11-01T01:00:00Z"), zone))
        assertEquals(due, WeeklySchedule.latest(due, ZoneId.of("UTC")))
    }
}
