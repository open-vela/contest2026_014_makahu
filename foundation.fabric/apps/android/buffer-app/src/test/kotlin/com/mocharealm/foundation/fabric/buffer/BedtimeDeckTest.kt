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

import org.junit.Assert.*
import org.junit.Test

class BedtimeDeckTest {
    private fun question() = BufferCard(
        cardId = "q", captureId = "c", kind = "question_card", title = "今天的小问题",
        summary = "一个简短回顾", state = "quick_answered",
        actions = listOf("rabbit_hole", "later", "archive", "mark_researched"),
        createdAt = 1, answer = "一段短答案", tags = listOf(BedtimeDeck.SAFE_TAG),
        researchNotes = "长篇研究", externalLinks = listOf("https://example.com"),
        relatedCardIds = listOf("another"),
    )

    @Test fun questionsRequireExplicitOptInAndAnAnswer() {
        assertNull(BedtimeDeck.project(question().copy(tags = emptyList())))
        assertNull(BedtimeDeck.project(question().copy(answer = null)))
        assertNull(BedtimeDeck.project(question().copy(answer = "  ")))
        assertNotNull(BedtimeDeck.project(question()))
    }

    @Test fun excludesTasksAndDeepWorkEvenWhenMarkedSafe() {
        for (kind in listOf("task_card", "rabbit_hole_card", "unknown")) {
            assertNull(BedtimeDeck.project(question().copy(kind = kind)))
        }
        for (state in listOf("archived", "done", "later", "rabbit_hole", "rabbit_hole_candidate")) {
            assertNull(BedtimeDeck.project(question().copy(state = state)))
        }
    }

    @Test fun removesResearchEntrypointsWithoutChangingStoredCard() {
        val original = question()
        val display = requireNotNull(BedtimeDeck.project(original))
        assertEquals(listOf("later", "archive"), display.actions)
        assertNull(display.researchNotes)
        assertTrue(display.externalLinks.isEmpty())
        assertTrue(display.relatedCardIds.isEmpty())
        assertNotNull(original.researchNotes)
        assertTrue("rabbit_hole" in original.actions)
        assertEquals(1, original.externalLinks.size)
    }

    @Test fun longContentIsDeferredRatherThanSilentlyTruncated() {
        assertNotNull(BedtimeDeck.project(question().copy(summary = "字".repeat(280), answer = "字".repeat(400))))
        assertNull(BedtimeDeck.project(question().copy(summary = "字".repeat(281))))
        assertNull(BedtimeDeck.project(question().copy(answer = "字".repeat(401))))
    }

    @Test fun careIsAvailableWithoutReadingOptIn() {
        val care = question().copy(kind = "care_card", tags = emptyList(), answer = null,
            actions = listOf("done", "later", "remind"))
        assertEquals(listOf("done", "later"), BedtimeDeck.project(care)?.actions)
        val reclassified = care.copy(answer = "旧问题答案".repeat(100))
        assertNotNull(BedtimeDeck.project(reclassified))
        assertNull(BedtimeDeck.project(reclassified)?.answer)
        assertNotNull(reclassified.answer)
    }

    @Test fun shortRecapsAndSparksRequireOptIn() {
        for (kind in listOf("spark_card", "weekly_card")) {
            assertNotNull(BedtimeDeck.project(question().copy(kind = kind, answer = null)))
            assertNull(BedtimeDeck.project(question().copy(kind = kind, tags = emptyList())))
        }
    }
}
