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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VelaEnrollmentRecoveryUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: BufferRepository
    private val owner = ViewModelStore()
    @Before fun open() { repository = BufferRepository(app) }
    @After fun close() { owner.clear(); repository.close() }
    private fun model() = ViewModelProvider(owner, VelaEnrollmentViewModel.Factory(app, repository))[VelaEnrollmentViewModel::class.java]
    @Test fun pendingRecordSurvivesReopenAndCanBeCancelledWithoutBluetooth() {
        val row = repository.velaEnrollments.begin("vela-one", "AA:BB:CC:DD:EE:01")
        repository.close(); repository = BufferRepository(app)
        val model = model()
        val status = model.pending.single()
        assertEquals(row.deviceId, status.deviceId)
        assertFalse(status.toString().contains(row.token))
        model.cancelPending(status)
        assertTrue(model.pending.isEmpty())
        assertTrue(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).cancelled)
        assertNull(repository.velaAuthenticationToken(row.deviceId))
        assertFalse(repository.velaEnrollments.recoverVerifiedReceipt(row.deviceId, row.requestId, row.token))
    }
    @Test fun waitingCompletesOnlyAfterRepositoryAcceptsAuthenticatedReceipt() {
        val row = repository.velaEnrollments.begin("vela-one", "AA:BB:CC:DD:EE:01")
        val model = model()
        model.resumePending(model.pending.single())
        assertFalse(model.view.complete)
        assertNotNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
        model.refreshPaired()
        assertFalse(model.view.complete)
        repository.velaEnrollments.recoverVerifiedReceipt(row.deviceId, row.requestId, row.token)
        model.refreshPaired()
        assertTrue(model.view.complete)
        assertEquals(listOf(row.deviceId), model.paired)
        assertTrue(model.pending.isEmpty())
    }
    @Test fun staleCancelDoesNotRevokeNewRequestOrCommittedBinding() {
        val row = repository.velaEnrollments.begin("vela-one", "AA:BB:CC:DD:EE:01")
        val model = model(); val stale = model.pending.single()
        val newer = repository.velaEnrollments.retry(row.deviceId, row.requestId)
        model.cancelPending(stale)
        assertFalse(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).cancelled)
        assertEquals(newer.requestId, model.pending.single().requestId)
        val current = model.pending.single()
        repository.velaEnrollments.recoverVerifiedReceipt(newer.deviceId, newer.requestId, newer.token)
        model.cancelPending(current)
        assertEquals(newer.token, repository.pairedVelaToken(newer.deviceId))
        assertTrue(model.view.complete)
        assertTrue(model.pending.isEmpty())
    }
    @Test fun oldResumeCannotReactivateCancelledAttempt() {
        val row = repository.velaEnrollments.begin("vela-one", "AA:BB:CC:DD:EE:01")
        val model = model(); val old = model.pending.single()
        repository.velaEnrollments.cancel(row.deviceId, row.requestId)
        model.resumePending(old)
        assertTrue(model.pending.isEmpty())
        assertFalse(model.view.complete)
        assertTrue(requireNotNull(repository.velaEnrollments.pending(row.deviceId)).cancelled)
    }
}
