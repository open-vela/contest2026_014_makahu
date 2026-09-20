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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CollectionEditingTest {
    private lateinit var repository: BufferRepository
    @Before fun open() { repository = BufferRepository(RuntimeEnvironment.getApplication()) }
    @After fun close() { repository.close() }
    private fun audio(id: String) = repository.createAudioCapture(id, "vela", System.currentTimeMillis(), "normal", "audio/wav", 2000, byteArrayOf(1, 2))
    private fun remote(id: String) = repository.applyRemoteClassification(id,
        listOf(BufferClassificationProposal("迟到文本", "spark_card", "迟到标题", "迟到摘要", null, 0.9)))
    private fun eventCount(type: String): Int = repository.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM events WHERE type = ?", arrayOf(type)).use { it.moveToFirst(); it.getInt(0) }

    @Test fun pendingAudioNeverEntersCollectionsOrWeeklySparkCountEvenWhenArchived() {
        val card = audio("pending-audio")
        repository.performCardAction(card.cardId, "archive")
        assertTrue(repository.shelfCards().isEmpty())
        assertTrue(repository.answerBoxCards().isEmpty())
        assertTrue(repository.lifeLogCards().isEmpty())
        assertEquals(0, repository.weeklySummary().sparks)
        assertEquals(1, repository.pendingAudioCaptures().size)
        repository.completeAudioTranscription("pending-audio", "一个新的灵感")
        assertEquals(1, repository.shelfCards().size)
    }
    @Test fun editSurvivesLateClassificationAndReopenWithoutChangingCapture() {
        val card = repository.createTextCapture("最初的灵感")
        val before = repository.captures().single()
        repository.editCardContent(card, "新的标题", "修改后的内容")
        remote(requireNotNull(card.captureId))
        repository.close(); open()
        val result = requireNotNull(repository.findCard(card.cardId))
        assertEquals("新的标题", result.title)
        assertEquals("修改后的内容", result.summary)
        assertEquals(before, repository.captures().single())
        assertEquals(1, eventCount("CardContentEdited"))
    }
    @Test fun staleEditorAndBlankInputCannotOverwrite() {
        val card = repository.createTextCapture("初始内容")
        repository.editCardContent(card, "第一次修改", "新内容")
        assertThrows(IllegalArgumentException::class.java) { repository.editCardContent(card, "旧窗口", "旧内容") }
        val current = requireNotNull(repository.findCard(card.cardId))
        assertThrows(IllegalArgumentException::class.java) { repository.editCardContent(current, " ", "内容") }
        assertEquals("第一次修改", repository.findCard(card.cardId)?.title)
    }
    @Test fun deletionIsIdempotentAndLateClassificationCannotRecreateCard() {
        val card = repository.createTextCapture("将要删除的灵感")
        val captures = repository.captures()
        assertEquals(1, repository.deleteCards(listOf(card.cardId, card.cardId)))
        assertEquals(0, repository.deleteCards(listOf(card.cardId)))
        remote(requireNotNull(card.captureId))
        repository.close(); open()
        assertNull(repository.findCard(card.cardId))
        assertTrue(repository.shelfCards().isEmpty())
        assertEquals(captures, repository.captures())
        assertEquals(1, eventCount("CardDeleted"))
    }
    @Test fun batchDeletionIsAtomicWhenOneCardIsPendingAudio() {
        val card = repository.createTextCapture("保留的内容")
        val pending = audio("cannot-delete-pending")
        assertThrows(IllegalArgumentException::class.java) { repository.deleteCards(listOf(card.cardId, pending.cardId)) }
        assertNotNull(repository.findCard(card.cardId))
        assertNotNull(repository.findCard(pending.cardId))
        assertEquals(0, eventCount("CardDeleted"))
    }
    @Test fun batchDeletionKeepsOtherCardsAndCancelsReminder() {
        val first = repository.createTextCapture("第一个灵感")
        val second = repository.createTextCapture("第二个灵感")
        val keep = repository.createTextCapture("留下的灵感")
        repository.writableDatabase.execSQL("INSERT INTO card_reminders(card_id,due_at,revision,delivered) VALUES(?,?,?,1)",
            arrayOf<Any>(first.cardId, System.currentTimeMillis(), "test-reminder"))
        assertEquals(2, repository.deleteCards(listOf(first.cardId, second.cardId)))
        val cancelled = mutableListOf<String>()
        repository.reconcileCardReminders(System.currentTimeMillis(), { fail("Deleted card must not notify"); false }, { cancelled += it })
        assertEquals(listOf(first.cardId), cancelled)
        assertEquals(listOf(keep.cardId), repository.shelfCards().map { it.cardId })
    }
}
