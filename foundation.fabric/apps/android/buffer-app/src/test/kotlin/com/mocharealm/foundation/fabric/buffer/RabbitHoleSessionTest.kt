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
class RabbitHoleSessionTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun topic() = repository.createTextCapture("为什么天空是蓝色的？")

    @Test fun restartKeepsDeadlineAndExpiryEndsOnlyOnce() {
        val card = topic()
        val now = System.currentTimeMillis()
        val session = repository.startRabbitSession(card.cardId, 60, now)
        assertEquals("rabbit_hole", repository.mode())
        repository.close(); open()
        assertEquals(session, repository.rabbitSession())
        assertEquals(session.endsAt, repository.reconcileRabbitSession(session.endsAt - 1))
        assertNull(repository.reconcileRabbitSession(session.endsAt + 60_000))
        val count = repository.eventCount()
        assertNull(repository.reconcileRabbitSession(session.endsAt + 120_000))
        assertEquals(count, repository.eventCount())
        assertEquals("time_up", repository.rabbitSession()?.reason)
        assertEquals(session.endsAt, repository.rabbitSession()?.finishedAt)
        assertEquals("normal", repository.mode())
        assertEquals(card, repository.findCard(card.cardId))
    }

    @Test fun outcomeIsDurableAndStaleFinishCannotStopNewSession() {
        val card = topic()
        val session = repository.startRabbitSession(card.cardId, 90)
        val finished = repository.finishRabbitSession(session.id, "找到光散射的解释")
        assertEquals("找到光散射的解释", finished.outcome)
        assertEquals(finished, repository.finishRabbitSession(session.id, "重复提交"))
        val next = repository.startRabbitSession(card.cardId, 120)
        assertThrows(IllegalArgumentException::class.java) { repository.finishRabbitSession(session.id) }
        assertEquals(next, repository.rabbitSession())
        repository.close(); open()
        assertEquals(next, repository.rabbitSession())
    }

    @Test fun switchingModesEndsResearchWithoutOverwritingSelectedMode() {
        val session = repository.startRabbitSession(topic().cardId, 60)
        repository.setMode("bedtime")
        assertEquals("mode_changed", repository.rabbitSession()?.reason)
        assertEquals("bedtime", repository.mode())
        repository.reconcileRabbitSession(session.endsAt)
        assertEquals("bedtime", repository.mode())
    }

    @Test fun invalidStartAndConcurrentStartDoNotReplaceSession() {
        val card = topic()
        assertThrows(IllegalArgumentException::class.java) { repository.startRabbitSession(card.cardId, 1) }
        assertNull(repository.rabbitSession())
        val current = repository.startRabbitSession(card.cardId, 60)
        val other = BufferRepository(RuntimeEnvironment.getApplication())
        try {
            assertThrows(IllegalStateException::class.java) { other.startRabbitSession(card.cardId, 90) }
            assertEquals(current, other.rabbitSession())
        } finally { other.close() }
    }

    @Test fun eventFailureRollsBackModeAndSessionTogether() {
        val card = topic()
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_research BEFORE INSERT ON events WHEN NEW.type = 'RabbitHoleStarted' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(Exception::class.java) { repository.startRabbitSession(card.cardId, 60) }
        assertNull(repository.rabbitSession())
        assertEquals("normal", repository.mode())
    }
    @Test fun expiryAllowsNotesWithoutChangingEndTimeOrNextSession() {
        val card = topic()
        val session = repository.startRabbitSession(card.cardId, 60)
        repository.reconcileRabbitSession(session.endsAt)
        val updated = repository.recordRabbitOutcome(session.id, "稍后补写研究发现")
        assertEquals(session.endsAt, updated.finishedAt)
        assertEquals("time_up", updated.reason)
        val count = repository.eventCount()
        repository.recordRabbitOutcome(session.id, updated.outcome)
        assertEquals(count, repository.eventCount())
        repository.startRabbitSession(card.cardId, 60)
        assertThrows(IllegalArgumentException::class.java) { repository.recordRabbitOutcome(session.id, "旧对话框") }
    }

    @Test fun endingEventFailurePreservesActiveSessionAndMode() {
        val current = repository.startRabbitSession(topic().cardId, 60)
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_research_end BEFORE INSERT ON events WHEN NEW.type = 'RabbitHoleEnded' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(Exception::class.java) { repository.setMode("bedtime") }
        assertEquals(current, repository.rabbitSession())
        assertEquals("rabbit_hole", repository.mode())
        repository.writableDatabase.execSQL("DROP TRIGGER fail_research_end")
        repository.setMode("bedtime")
        assertFalse(requireNotNull(repository.rabbitSession()).active)
    }

    @Test fun historyKeepsLatestSnapshotPerSessionAcrossRestartAndPagination() {
        val card = topic()
        val first = repository.startRabbitSession(card.cardId, 60)
        repository.finishRabbitSession(first.id, "最初发现")
        val amended = repository.recordRabbitOutcome(first.id, "补充后的完整发现")
        val second = repository.startRabbitSession(card.cardId, 90)
        repository.close(); open()
        assertEquals(listOf(second, amended), repository.rabbitHistory())
        assertEquals(listOf(second), repository.rabbitHistory(1))
        assertEquals(listOf(amended), repository.rabbitHistory(1, 1))
        assertTrue(repository.rabbitHistory(1, 2).isEmpty())
        assertEquals(card.cardId, repository.rabbitHistory()[1].cardId)
    }

    @Test fun failedOutcomeDoesNotLeakIntoHistoricalProjection() {
        val session = repository.startRabbitSession(topic().cardId, 60)
        val finished = repository.finishRabbitSession(session.id, "保留这条")
        repository.writableDatabase.execSQL("CREATE TRIGGER fail_note BEFORE INSERT ON events WHEN NEW.type = 'RabbitHoleOutcomeRecorded' BEGIN SELECT RAISE(ABORT, 'test'); END")
        assertThrows(Exception::class.java) { repository.recordRabbitOutcome(session.id, "不能显示这条") }
        assertEquals(listOf(finished), repository.rabbitHistory())
        assertEquals(finished, repository.rabbitSession())
    }

    @Test fun wireSnapshotIncludesResearchAndConsistentModeAfterExpiry() {
        assertFalse(BufferWire.cardsUpdated(repository).getJSONObject("research_session").getBoolean("present"))
        val session = repository.startRabbitSession(topic().cardId, 60)
        val active = BufferWire.cardsUpdated(repository)
        assertEquals("rabbit_hole", active.getString("mode"))
        assertEquals(session.id, active.getJSONObject("research_session").getString("id"))
        assertTrue(active.getJSONObject("research_session").getLong("remaining_ms") in 1..3_600_000)
        val ended = repository.rabbitSnapshot(session.endsAt)
        assertFalse(ended.getBoolean("active"))
        assertEquals(0L, ended.getLong("remaining_ms"))
        assertEquals("time_up", ended.getString("reason"))
        assertEquals("normal", BufferWire.cardsUpdated(repository).getString("mode"))
    }

    @Test fun wireTitleFitsDeviceBufferWithoutSplittingUnicode() {
        val session = repository.startRabbitSession(repository.createTextCapture("为什么" + "🌍".repeat(80) + "？").cardId, 120)
        val title = repository.rabbitSnapshot(session.startedAt).getString("title")
        assertTrue(title.toByteArray(Charsets.UTF_8).size < 192)
        assertFalse(Character.isHighSurrogate(title.last()))
        assertEquals(session.title, repository.rabbitSession()?.title)
    }

}
