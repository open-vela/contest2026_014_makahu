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
