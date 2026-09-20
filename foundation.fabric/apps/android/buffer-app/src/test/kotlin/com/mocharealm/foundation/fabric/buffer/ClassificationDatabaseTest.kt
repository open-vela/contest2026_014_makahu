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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClassificationDatabaseTest {
    private lateinit var user: BufferRepository
    private lateinit var worker: BufferRepository
    private val proposal = BufferClassificationProposal("后台文本", "question_card", "后台问题", "后台摘要", null, 0.9)
    @Before fun open() {
        user = BufferRepository(RuntimeEnvironment.getApplication())
        worker = BufferRepository(RuntimeEnvironment.getApplication())
    }
    @After fun close() { worker.close(); user.close() }
    private fun remote(card: BufferCard) = worker.applyRemoteClassification(requireNotNull(card.captureId), listOf(proposal))
    private fun current(card: BufferCard) = requireNotNull(user.cards(100).find { it.cardId == card.cardId })

    @Test fun lateRemoteResultPreservesAcceptedCard() {
        val card = user.createTextCapture("一个灵感")
        val accepted = user.reviewClassification(card, ClassificationDecision.ACCEPT)
        remote(card)
        assertEquals(accepted, current(card))
    }
    @Test fun rejectionEventProtectsUnconfirmedCard() {
        val card = user.createTextCapture("拒绝旧提案")
        val rejected = user.reviewClassification(card, ClassificationDecision.REJECT)
        remote(card)
        assertEquals(rejected, current(card))
        assertFalse(current(card).classificationConfirmed)
    }
    @Test fun lateRemoteResultPreservesUserEditedCategory() {
        val card = user.createTextCapture("重新分类")
        val edited = user.reviewClassification(card, ClassificationDecision.EDIT, "task_card")
        remote(card)
        assertEquals(edited, current(card))
    }
    @Test fun remoteUpdateInvalidatesOldConfirmationSnapshot() {
        val card = user.createTextCapture("原始提案")
        remote(card)
        assertThrows(IllegalArgumentException::class.java) {
            user.reviewClassification(card, ClassificationDecision.ACCEPT)
        }
        assertFalse(current(card).classificationConfirmed)
        assertEquals(proposal.title, current(card).title)
    }
    @Test fun competingRepositoriesCannotOverwriteSuccessfulReview() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(12) {
                val card = user.createTextCapture("并发提案 $it")
                val start = CountDownLatch(1)
                val review = executor.submit<BufferCard?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { user.reviewClassification(card, ClassificationDecision.EDIT, "task_card") }
                    catch (_: IllegalArgumentException) { null } // A newer proposal requires reconfirmation.
                }
                val classify = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    remote(card)
                }
                start.countDown()
                val accepted = review.get(10, TimeUnit.SECONDS)
                classify.get(10, TimeUnit.SECONDS)
                if (accepted != null) assertEquals(accepted, current(card))
                else {
                    assertFalse(current(card).classificationConfirmed)
                    assertEquals(proposal.title, current(card).title)
                }
            }
        } finally { executor.shutdownNow() }
    }
}
