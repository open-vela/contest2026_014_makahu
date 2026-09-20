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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeeklySummaryTest {
    private lateinit var repository: BufferRepository
    private val day = 86_400_000L
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun card(kind: String, created: Long, category: String? = null): BufferCard {
        val captured = repository.createTextCapture("回顾统计")
        val card = requireNotNull(repository.reviewClassification(captured, ClassificationDecision.EDIT, kind, category))
        repository.writableDatabase.execSQL("UPDATE cards SET created_at = ? WHERE card_id = ?", arrayOf<Any>(created, card.cardId))
        return card
    }
    private fun actionTime(card: BufferCard, time: Long, legacy: Boolean = false) {
        val updates = mutableListOf<Pair<String, String>>()
        repository.readableDatabase.rawQuery("SELECT event_id, payload FROM events WHERE type = 'CardActionPerformed'", null).use { c ->
            while (c.moveToNext()) {
                val json = JSONObject(c.getString(1))
                if (json.optString("card_id") == card.cardId) {
                    if (legacy) json.remove("card_kind")
                    updates += c.getString(0) to json.toString()
                }
            }
        }
        updates.forEach { (id, payload) -> repository.writableDatabase.execSQL(
            "UPDATE events SET created_at = ?, payload = ? WHERE event_id = ?", arrayOf<Any>(time, payload, id)) }
    }
    @Test fun lifeOverviewFiltersWindowAndConfirmationAndPersistsEvidence() {
        val now = System.currentTimeMillis() + 1000
        val sleep = card("care_card", now - day, "sleep")
        val pending = card("care_card", now - day)
        repository.writableDatabase.execSQL("UPDATE cards SET classification_confirmed = 0 WHERE card_id = ?", arrayOf(pending.cardId))
        val future = card("care_card", now + day, "sleep")
        val expired = card("care_card", now - 8 * day, "sleep")
        val planned = card("task_card", now - day, "exercise")
        val summary = repository.weeklySummary(now)
        assertEquals(listOf(sleep.cardId), summary.lifeOverview.single { it.category == "sleep" }.cardIds)
        assertEquals(1, summary.unconfirmedLifeRecords)
        assertTrue(summary.lifeOverview.single { it.category == "exercise" }.cardIds.isEmpty())
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        val records = job.facts.getJSONArray("records")
        val sleepRecord = (0 until records.length()).map(records::getJSONObject).single { it.getString("id") == sleep.cardId }
        assertEquals("sleep", sleepRecord.getString("health_category"))
        assertTrue(sleepRecord.getBoolean("classification_confirmed"))
        assertFalse((0 until records.length()).map(records::getJSONObject).any { it.getString("id") in listOf(future.cardId, expired.cardId) })
        assertNull(repository.findCard(job.id + "-llm"))

    }
    @Test fun lifeOverviewDeduplicatesCapturesAndUsesLocalCalendarDays() {
        val original = card("care_card", 0).copy(healthCategory = "sleep")
        val first = original.copy(createdAt = java.time.Instant.parse("2026-09-19T15:59:00Z").toEpochMilli())
        val second = first.copy(cardId = "second", captureId = "another", createdAt = first.createdAt + 120_000)
        val duplicate = first.copy(cardId = "duplicate")
        val result = WeeklyReflection.lifeOverview(listOf(first, second, duplicate), java.time.ZoneId.of("Asia/Shanghai"))
            .single { it.category == "sleep" }
        assertEquals(2, result.cardIds.size)
        assertEquals(2, result.recordedDays)
        assertEquals(2, result.examples.size)
        val empty = WeeklyReflection.lifeOverview(emptyList())
        assertEquals(10, empty.size)
        assertTrue(empty.all { it.cardIds.isEmpty() && it.recordedDays == 0 && it.examples.isEmpty() })
    }
    @Test fun reflectionUsesIndependentTagsAndOnlyRepeatedOpenDeferrals() {
        val now = System.currentTimeMillis() + 1000
        val first = card("spark_card", now - day)
        val second = card("question_card", now - day)
        listOf(first, second).forEach {
            repository.writableDatabase.execSQL("UPDATE cards SET tags = ? WHERE card_id = ?",
                arrayOf("[\"花园\",\"花园\"]", it.cardId))
        }
        val pending = card("task_card", now - 30 * day)
        repeat(3) { repository.performCardAction(pending.cardId, "later", "later-$it") }
        actionTime(pending, now - day)
        // Replayed receipt is idempotent, not another postponement.
        repository.performCardAction(pending.cardId, "later", "later-0")
        val done = card("task_card", now - day)
        repeat(2) { repository.performCardAction(done.cardId, "later") }
        repository.performCardAction(done.cardId, "done")
        actionTime(done, now - day)
        val stale = card("task_card", now - 30 * day)
        repeat(2) { repository.performCardAction(stale.cardId, "later") }
        actionTime(stale, now - 8 * day)
        val summary = repository.weeklySummary(now)
        assertEquals(listOf("花园"), summary.recurringThemes.map { it.tag })
        assertEquals(2, summary.recurringThemes.single().cardIds.size)
        assertEquals(listOf(pending.cardId), summary.deferredTasks.map { it.card.cardId })
        assertEquals(3, summary.deferredTasks.single().times)
        assertEquals(listOf(pending.cardId, second.cardId), summary.nextWeekSuggestions.map { it.cardId })
        repository.generateWeeklySummary(now)
        val job = requireNotNull(repository.claimWeeklyLlm(now))
        assertEquals(3, job.facts.getJSONArray("deferred_tasks").getJSONObject(0).getInt("times"))
        assertEquals(pending.cardId, job.facts.getJSONArray("deferred_tasks").getJSONObject(0).getString("card_id"))

    }
    @Test fun derivedCardsDoNotCreateRepeatedThemesAndSuggestionsStayBounded() {
        val now = System.currentTimeMillis()
        val original = card("spark_card", now)
        val copies = (1..6).map { original.copy(cardId = "copy-$it", tags = listOf("主题")) }
        assertTrue(WeeklyReflection.themes(copies).isEmpty())
        val independent = copies.mapIndexed { i, c -> c.copy(captureId = "capture-$i") }
        assertEquals(6, WeeklyReflection.themes(independent).single().cardIds.size)
        assertTrue(WeeklyReflection.themes(independent.map { it.copy(kind = "weekly_card") }).isEmpty())
        val tasks = independent.map { it.copy(kind = "task_card") }
        val deferred = WeeklyReflection.deferred(tasks, tasks.associate { it.cardId to 2 })
        assertEquals(5, deferred.size)
        val suggestions = WeeklyReflection.suggestions(deferred, independent)
        assertEquals(3, suggestions.size)
        assertTrue(WeeklyReflection.suggestions(emptyList(), emptyList()).isEmpty())
    }
    @Test fun completionUsesActionDateAndCountsEachTaskOnceIncludingLegacy() {
        val now = System.currentTimeMillis() + 1000
        val old = card("task_card", now - 30 * day)
        repository.performCardAction(old.cardId, "done")
        repository.performCardAction(old.cardId, "done")
        actionTime(old, now - day, legacy = true)
        val expired = card("task_card", now - day)
        repository.performCardAction(expired.cardId, "done")
        actionTime(expired, now - 8 * day)
        val future = card("task_card", now - day)
        repository.performCardAction(future.cardId, "done")
        actionTime(future, now + day)
        assertEquals(1, repository.weeklySummary(now).tasksCompleted)
        val generated = repository.generateWeeklySummary(now)
        assertEquals(1, repository.weeklyLlmFacts(now).getJSONObject("statistics").getInt("completed_tasks"))
        assertEquals(generated, repository.generateWeeklySummary(now))
    }
    @Test fun unresolvedQuestionsExcludeAnsweredArchivedAndOutOfWindowCandidates() {
        val now = System.currentTimeMillis() + 1000
        val unresolved = card("question_card", now - day)
        val answered = card("question_card", now - day)
        repository.performCardAction(answered.cardId, "mark_answered")
        val archived = card("question_card", now - day)
        repository.performCardAction(archived.cardId, "archive")
        card("question_card", now + day)
        card("question_card", now - 8 * day)
        val summary = repository.weeklySummary(now)
        assertEquals(1, summary.questions)
        assertEquals(listOf(unresolved.cardId), summary.rabbitHoleCandidates.map { it.cardId })
    }
    @Test fun reportingWindowIncludesBothBoundariesButNoFutureCards() {
        val now = System.currentTimeMillis() + 1000
        card("spark_card", now - 7 * day)
        card("spark_card", now)
        card("spark_card", now + 1)
        card("spark_card", now - 7 * day - 1)
        assertEquals(2, repository.weeklySummary(now).sparks)
        val task = card("task_card", now - 40 * day)
        repository.performCardAction(task.cardId, "done")
        actionTime(task, now - 7 * day)
        assertEquals(1, repository.weeklySummary(now).tasksCompleted)
        // Later reclassification must not rewrite the kind of a historical completion.
        repository.writableDatabase.execSQL("UPDATE cards SET kind = 'spark_card' WHERE card_id = ?", arrayOf(task.cardId))
        assertEquals(1, repository.weeklySummary(now).tasksCompleted)
    }
}
