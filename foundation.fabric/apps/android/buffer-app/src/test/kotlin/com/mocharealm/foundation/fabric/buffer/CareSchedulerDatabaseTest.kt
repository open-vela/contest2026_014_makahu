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
        scheduler.onAlarm()
        assertEquals(1L, repository.focusNextReminderAt())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_reminder")
        scheduler.onAlarm()
        assertTrue(repository.focusNextReminderAt() > System.currentTimeMillis())
        val count = repository.eventCount()
        scheduler.onAlarm()
        assertEquals(count, repository.eventCount())
    }

    @Test fun failedModeReadCanRecoverWithoutRecreatingScheduler() {
        repository.writableDatabase.execSQL("ALTER TABLE runtime_settings RENAME TO temporarily_unavailable")
        scheduler.onAlarm()
        repository.writableDatabase.execSQL("ALTER TABLE temporarily_unavailable RENAME TO runtime_settings")
        repository.setMode("focus")
        val next = repository.focusNextReminderAt()
        scheduler.onAlarm()
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
        scheduler.onAlarm()
        scheduler.onAlarm()
        scheduler.onAlarm()
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
