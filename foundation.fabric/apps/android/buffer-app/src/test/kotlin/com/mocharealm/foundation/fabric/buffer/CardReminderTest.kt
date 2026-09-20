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
            scheduler.onAlarm()
            assertNotNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            repository.setMode("bedtime"); scheduler.onAlarm()
            assertNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            repository.setMode("normal"); scheduler.onAlarm()
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
            scheduler.onAlarm()
            assertTrue(delivered(card.cardId))
            val posted = requireNotNull(shadowOf(manager).getNotification("buffer-card-${card.cardId}", 1410))
            assertEquals(card.cardId, CardReminderNavigation.cardId(shadowOf(posted.contentIntent).savedIntent))
            scheduler.onAlarm(); assertEquals(1, count())
            repository.performCardAction(card.cardId, "done")
            scheduler.onAlarm()
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
