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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CalendarWriteSelectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun confirm(date: LocalDate? = LocalDate.of(2026, 9, 20),
                        time: LocalTime? = LocalTime.of(23, 30),
                        duration: Int = 60, calendar: Long = 42,
                        priority: CalendarPriority = CalendarPriority.NORMAL) =
        CalendarWriteSelection.confirm(calendar, date, time, duration, priority, zone, now)

    @Test fun explicitChoicePreservesCalendarZoneAndCrossMidnightDuration() {
        val selection = confirm()
        assertEquals(42, selection.calendarId)
        assertEquals("Asia/Shanghai", selection.timeZone)
        assertEquals(Instant.parse("2026-09-20T15:30:00Z").toEpochMilli(), selection.startMillis)
        assertEquals(Instant.parse("2026-09-20T16:30:00Z").toEpochMilli(), selection.endMillis)
    }

    @Test fun missingTimeStaysUnconfirmedInsteadOfGuessing() {
        assertThrows(IllegalArgumentException::class.java) { confirm(date = null) }
        assertThrows(IllegalArgumentException::class.java) { confirm(time = null) }
    }

    @Test fun invalidCalendarAndDurationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { confirm(calendar = 0) }
        assertThrows(IllegalArgumentException::class.java) { confirm(duration = 0) }
        assertThrows(IllegalArgumentException::class.java) { confirm(duration = 1441) }
        assertEquals(86_400_000L, confirm(duration = 1440).let { it.endMillis - it.startMillis })
    }

    @Test fun pastTimeCannotBeAccidentallyScheduled() {
        assertThrows(IllegalArgumentException::class.java) { confirm(date = LocalDate.of(2025, 12, 31)) }
    }

    @Test fun priorityIsIncludedWithoutLosingTaskDescription() {
        for (priority in CalendarPriority.entries) {
            val selection = confirm(priority = priority)
            assertEquals(priority, selection.priority)
            assertEquals("任务原文\n\n优先级：${priority.label}", selection.description("任务原文"))
        }
        assertEquals(listOf("low", "normal", "high"), CalendarPriority.entries.map { it.wireName })
    }

    @Test fun daylightSavingGapAndRepeatedHourRequireAnotherChoice() {
        val newYork = ZoneId.of("America/New_York")
        for (dateTime in listOf(LocalDate.of(2026, 3, 8) to LocalTime.of(2, 30),
                                LocalDate.of(2026, 11, 1) to LocalTime.of(1, 30))) {
            assertThrows(IllegalArgumentException::class.java) {
                CalendarWriteSelection.confirm(42, dateTime.first, dateTime.second, 60,
                    CalendarPriority.NORMAL, newYork, now)
            }
        }
    }
}
