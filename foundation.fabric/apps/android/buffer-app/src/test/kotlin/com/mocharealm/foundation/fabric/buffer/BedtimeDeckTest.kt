package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.domain.BedtimeDeck
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard

import org.junit.Assert.*
import org.junit.Test

import com.mocharealm.foundation.fabric.buffer.domain.BedtimeAssessment

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

    @Test fun careRequiresConfirmedLowAssessmentWithoutReadingOptIn() {
        val care = question().copy(kind = "care_card", tags = emptyList(), answer = null,
            actions = listOf("done", "later", "remind"), classificationConfirmed = true)
        assertNull(BedtimeDeck.project(care))
        val low = BedtimeAssessment("low", "简短照顾提醒")
        assertEquals(listOf("done", "later"), BedtimeDeck.project(care, low)?.actions)
        val reclassified = care.copy(answer = "旧问题答案".repeat(100))
        assertNotNull(BedtimeDeck.project(reclassified, low))
        assertNull(BedtimeDeck.project(reclassified, low)?.answer)
        assertNotNull(reclassified.answer)
    }

    @Test fun shortRecapsAndSparksRequireOptIn() {
        for (kind in listOf("spark_card", "weekly_card")) {
            assertNotNull(BedtimeDeck.project(question().copy(kind = kind, answer = null)))
            assertNull(BedtimeDeck.project(question().copy(kind = kind, tags = emptyList())))
        }
    }
}
