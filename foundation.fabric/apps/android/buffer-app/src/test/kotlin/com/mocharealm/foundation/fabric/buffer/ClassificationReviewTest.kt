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

class ClassificationReviewTest {
    private fun proposal() = BufferCard(
        cardId = "card", captureId = "capture", kind = "question_card", title = "原提案标题",
        summary = "原始提案内容", state = "unanswered_gray",
        actions = listOf("later", "archive", "accept_classification"), createdAt = 123,
        answer = "用户答案", researchNotes = "用户笔记", tags = listOf("标签"),
        externalLinks = listOf("https://example.com"), relatedCardIds = listOf("related"),
    )

    @Test fun legacyProposalGetsAllReviewChoices() {
        val card = ClassificationReview.normalizeActions(proposal())
        assertTrue(card.actions.containsAll(listOf("accept_classification", "edit_classification", "reject_classification")))
        assertEquals(card, ClassificationReview.normalizeActions(card))
    }

    @Test fun acceptConfirmsWithoutChangingContentAndIsIdempotent() {
        val old = proposal()
        val accepted = ClassificationReview.decide(old, ClassificationDecision.ACCEPT)
        assertEquals("ClassificationAccepted", accepted.eventType)
        assertTrue(accepted.card.classificationConfirmed)
        assertEquals(old.summary, accepted.card.summary)
        assertEquals(listOf("later", "archive"), accepted.card.actions)
        assertNull(ClassificationReview.decide(accepted.card, ClassificationDecision.ACCEPT).eventType)
    }

    @Test fun rejectRetainsSourceAndResearchButDisablesExternalWrites() {
        val old = proposal()
        val rejected = ClassificationReview.decide(old.copy(healthCategory = "hydration"), ClassificationDecision.REJECT)
        assertEquals("ClassificationRejected", rejected.eventType)
        assertEquals(ClassificationReview.UNCLASSIFIED, rejected.card.kind)
        assertFalse(rejected.card.classificationConfirmed)
        assertNull(rejected.card.healthCategory)
        assertEquals(listOf("edit_classification", "archive", "later"), rejected.card.actions)
        assertEquals(old.captureId, rejected.card.captureId)
        assertEquals(old.summary, rejected.card.summary)
        assertEquals(old.answer, rejected.card.answer)
        assertEquals(old.researchNotes, rejected.card.researchNotes)
        assertEquals(old.externalLinks, rejected.card.externalLinks)
        assertEquals(old.relatedCardIds, rejected.card.relatedCardIds)
        assertEquals(old.tags, rejected.card.tags)
        assertEquals(old.createdAt, rejected.card.createdAt)
        assertNull(ClassificationReview.decide(rejected.card, ClassificationDecision.REJECT).eventType)
    }

    @Test fun rejectedProposalRequiresAnExplicitNewClassification() {
        val rejected = ClassificationReview.decide(proposal(), ClassificationDecision.REJECT).card
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationReview.decide(rejected, ClassificationDecision.ACCEPT)
        }
        val edited = ClassificationReview.decide(rejected, ClassificationDecision.EDIT, "task_card")
        assertEquals("ClassificationEdited", edited.eventType)
        assertEquals("inbox", edited.card.state)
        assertTrue(edited.card.classificationConfirmed)
        assertTrue("calendar" in edited.card.actions)
        assertFalse("accept_classification" in edited.card.actions)
        assertNull(ClassificationReview.decide(edited.card, ClassificationDecision.EDIT, "task_card").eventType)
    }

    @Test fun editValidatesHealthTypeAndDoesNotDiscardAnswers() {
        val care = ClassificationReview.decide(proposal(), ClassificationDecision.EDIT, "care_card", "hydration").card
        assertEquals("hydration", care.healthCategory)
        assertTrue("health_sync" in care.actions)
        assertEquals("用户答案", care.answer)
        assertNull(care.displayAnswer)
        assertEquals("用户答案", proposal().displayAnswer)
        for ((kind, health) in listOf("invalid" to null, "care_card" to "invalid", "question_card" to "hydration")) {
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationReview.decide(proposal(), ClassificationDecision.EDIT, kind, health)
            }
        }
    }

    @Test fun allEditableKindsHaveConfirmedActionsAfterEditing() {
        for (kind in ClassificationReview.kinds.keys) {
            val edited = ClassificationReview.decide(proposal(), ClassificationDecision.EDIT, kind).card
            assertEquals(kind, edited.kind)
            assertTrue(edited.classificationConfirmed)
            assertFalse(edited.actions.any { it.endsWith("_classification") })
            if (kind == "question_card") assertEquals("quick_answered", edited.state)
        }
    }

    @Test fun rejectionAndEditingDoNotUndoPriorArchiveOrLaterChoice() {
        for (state in listOf("archived", "done", "later")) {
            val old = proposal().copy(state = state)
            assertEquals(state, ClassificationReview.decide(old, ClassificationDecision.REJECT).card.state)
            assertEquals(state, ClassificationReview.decide(old, ClassificationDecision.EDIT, "task_card").card.state)
        }
    }

    @Test fun pendingAudioCannotBeReviewedBeforeTranscription() {
        val pending = proposal().copy(state = "pending_transcription")
        assertEquals(listOf("later", "archive"), ClassificationReview.normalizeActions(pending).actions)
        for (decision in ClassificationDecision.entries) {
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationReview.decide(pending, decision, "task_card")
            }
        }
    }

    @Test fun rejectedCardNeverRegainsAnAcceptButtonAfterOtherActions() {
        val rejected = ClassificationReview.decide(proposal(), ClassificationDecision.REJECT).card
        val actions = ClassificationReview.preserveActions(rejected.copy(state = "later"), listOf("archive", "later"))
        assertEquals(listOf("archive", "later", "edit_classification"), actions)
    }

    @Test fun staleRejectionCannotOverwriteAnAcceptedDecision() {
        val accepted = ClassificationReview.decide(proposal(), ClassificationDecision.ACCEPT).card
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationReview.decide(accepted, ClassificationDecision.REJECT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationReview.decide(accepted, ClassificationDecision.EDIT, "task_card")
        }
    }
    @Test fun changedProposalCannotBeAcceptedUsingAnOldScreenSnapshot() {
        val displayed = proposal()
        val replacement = displayed.copy(kind = "task_card", summary = "后台更新后的内容")
        for (decision in ClassificationDecision.entries) {
            assertThrows(IllegalArgumentException::class.java) {
                ClassificationReview.decide(replacement, decision, "spark_card", expected = displayed)
            }
        }
        assertEquals("ClassificationAccepted",
            ClassificationReview.decide(replacement, ClassificationDecision.ACCEPT, expected = replacement).eventType)
    }

}
