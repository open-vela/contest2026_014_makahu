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
