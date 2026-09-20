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

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class BedtimePlannerTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(text: String): Long = LocalDateTime.parse(text)
        .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    private val day = at("2026-09-20T00:00:00")
    private fun plan(now: String, focus: Boolean, state: BedtimeState = BedtimeState(),
                     last: Long = 0, schedule: String = "22:00") =
        BedtimePlanner.plan(schedule, at(now), focus, last, state, zone)

    @Test fun futureScheduleDoesNotTriggerEarly() {
        val result = plan("2026-09-20T21:00:00", false)
        assertNull(result.triggerDay)
        assertEquals(at("2026-09-20T22:00:00"), result.nextAlarmAt)
        assertEquals(result.nextAlarmAt, result.state.scheduledFor)
    }

    @Test fun nonFocusTriggersExactlyOncePerDay() {
        val first = plan("2026-09-20T22:00:00", false)
        assertEquals(day, first.triggerDay)
        assertEquals(at("2026-09-21T22:00:00"), first.nextAlarmAt)
        val repeat = plan("2026-09-20T22:00:15", false, first.state, day)
        assertNull(repeat.triggerDay)
        assertEquals(first.nextAlarmAt, repeat.nextAlarmAt)
    }

    @Test fun frequentPollsKeepTheOriginalRetryDeadline() {
        var result = plan("2026-09-20T22:00:00", true)
        val expected = at("2026-09-20T22:15:00")
        for (poll in 1..59) {
            result = BedtimePlanner.plan("22:00", at("2026-09-20T22:00:00") + poll * 15000,
                true, 0, result.state, zone)
            assertNull(result.triggerDay)
            assertEquals(expected, result.nextAlarmAt)
        }
        val retried = plan("2026-09-20T22:15:00", true, result.state)
        assertEquals(at("2026-09-20T22:30:00"), retried.nextAlarmAt)
    }

    @Test fun focusExitTriggersPendingDayAfterMidnight() {
        val pending = plan("2026-09-20T23:59:00", true)
        val reloaded = BedtimeState(pending.state.pendingDay, pending.state.retryAt,
            pending.state.scheduledFor)
        val stillFocused = plan("2026-09-21T00:01:00", true, reloaded)
        assertEquals(day, stillFocused.state.pendingDay)
        assertEquals(at("2026-09-21T00:14:00"), stillFocused.nextAlarmAt)
        val exited = plan("2026-09-21T00:02:00", false, stillFocused.state)
        assertEquals(day, exited.triggerDay)
        assertEquals(0, exited.state.pendingDay)
        assertEquals(at("2026-09-21T22:00:00"), exited.nextAlarmAt)
    }

    @Test fun delayedAlarmRecoversItsOriginalDay() {
        val before = plan("2026-09-20T21:00:00", false)
        val delayed = plan("2026-09-21T00:05:00", false, before.state)
        assertEquals(day, delayed.triggerDay)
        val repeated = plan("2026-09-21T00:05:15", false, delayed.state, day)
        assertNull(repeated.triggerDay)
    }

    @Test fun disableOrInvalidScheduleClearsPendingState() {
        val pending = plan("2026-09-20T22:00:00", true)
        for (schedule in listOf("", "24:00", "12:60", "bad")) {
            val result = plan("2026-09-20T22:01:00", true, pending.state, schedule = schedule)
            assertNull(result.triggerDay)
            assertNull(result.nextAlarmAt)
            assertEquals(BedtimeState(), result.state)
        }
    }

    @Test fun daysOfMissedAlarmsCoalesceToMostRecentDueDay() {
        val pending = plan("2026-09-20T22:00:00", true)
        val latest = plan("2026-09-22T23:00:00", false, pending.state)
        assertEquals(at("2026-09-22T00:00:00"), latest.triggerDay)
        assertEquals(at("2026-09-23T22:00:00"), latest.nextAlarmAt)
    }

    @Test fun newScheduleWithClearedStateDoesNotReplayOldPendingDay() {
        val changed = plan("2026-09-21T00:05:00", false, schedule = "23:00")
        assertNull(changed.triggerDay)
        assertEquals(at("2026-09-21T23:00:00"), changed.nextAlarmAt)
    }
}
