package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.system.HealthConnectExporter
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationDecision
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LifeLogCategoriesTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    @Test fun localProposalsSeparateMoodLearningAndMindfulness() {
        for ((text, category) in listOf("刚才心情很平静" to "mood", "学习了三十分钟" to "learning", "冥想了十分钟" to "mindfulness")) {
            val card = repository.createTextCapture(text)
            assertEquals("care_card", card.kind)
            assertEquals(category, card.healthCategory)
            assertFalse(card.classificationConfirmed)
            assertEquals(category == "mindfulness", "health_sync" in card.actions)
        }
        val question = repository.createTextCapture("为什么学习之后感到疲劳？")
        assertEquals("question_card", question.kind)
        assertNull(question.healthCategory)
        assertFalse("health_sync" in question.actions)
    }
    @Test fun confirmedRecordsAppearInSeparateWeeklyGroupsAndSurviveReopen() {
        val records = listOf("心情很平静", "学习了三十分钟").map { text ->
            val proposal = repository.createTextCapture(text)
            requireNotNull(repository.reviewClassification(proposal, ClassificationDecision.ACCEPT))
        }
        repository.close(); repository = BufferRepository(RuntimeEnvironment.getApplication())
        val overview = repository.weeklySummary(System.currentTimeMillis() + 1000).lifeOverview
        assertEquals(listOf(records[0].cardId), overview.single { it.category == "mood" }.cardIds)
        assertEquals(listOf(records[1].cardId), overview.single { it.category == "learning" }.cardIds)
        assertTrue(overview.single { it.category == "mindfulness" }.cardIds.isEmpty())
        assertTrue(records.all { repository.findCard(it.cardId)?.captureId == it.captureId })
    }
    @Test fun editCanSelectNewCategoryWithoutExportingAsMindfulness() {
        val proposal = repository.createTextCapture("一个没有明显类别的记录")
        val edited = requireNotNull(repository.reviewClassification(proposal, ClassificationDecision.EDIT, "care_card", "learning"))
        assertEquals("learning", edited.healthCategory)
        assertFalse("health_sync" in edited.actions)
        assertTrue(HealthConnectExporter.requiredPermissions("learning").isEmpty())
        assertTrue(HealthConnectExporter.requiredPermissions("mood").isEmpty())
        assertFalse(HealthConnectExporter.requiredPermissions("mindfulness").isEmpty())
        val old = edited.copy(healthCategory = "travel", actions = edited.actions + "health_sync")
        assertFalse("health_sync" in ClassificationReview.normalizeActions(old).actions)
    }
}
