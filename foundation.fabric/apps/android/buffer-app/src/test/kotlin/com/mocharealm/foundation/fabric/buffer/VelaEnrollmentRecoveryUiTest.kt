package com.mocharealm.foundation.fabric.buffer
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.device.VelaEnrollmentViewModel

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
