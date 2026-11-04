package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BufferCoordinatorJobGateTest {
    @Test
    fun followerFenceUpdateInvalidatesOldHandoffAndRetryUsesConvergedTerm() {
        val phone = CoordinatorMember("phone-local", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("a".repeat(64), CoordinatorDeviceClass.VELA)
        val store = MemoryCoordinatorStateStore()
        assertTrue(store.persist("buffer-session", "membership-1", CoordinatorPersistentState(6L, phone.deviceId)))
        val election = BufferCoordinatorElection("buffer-session", "membership-1", phone,
            listOf(phone, vela), ready(), store)
        election.tick(0L)
        assertEquals(7L, election.tick(9_000L).term)
        BufferCoordinatorJobGate.attach(election, vela.deviceId)
        fun acquire(now: Long) = requireNotNull(BufferCoordinatorJobGate.acquire(
            "capture-retry", vela.deviceId, "classify", 1L, CoordinatorCapability.CLASSIFY, now))
        val old = acquire(9_000L)
        assertEquals(7L, BufferCoordinatorJobGate.termIfCurrent(old) { 9_000L })
        assertTrue(election.receiveHeartbeat(vela.deviceId, CoordinatorHeartbeat(
            sessionId = "buffer-session", membershipVersion = "membership-1",
            deviceId = vela.deviceId, incarnationId = "vela-boot", protocolVersion = 1,
            stateRevision = 1L, heartbeatSequence = 1L, readiness = ready(),
            coordinatorTerm = 8L, coordinatorDeviceId = phone.deviceId,
        ), 9_001L))
        assertNull(BufferCoordinatorJobGate.termIfCurrent(old) { 9_002L })
        var staleCommit = false
        assertNull(BufferCoordinatorJobGate.commitIfCurrent(old, 9_002L) {
            staleCommit = true
            "must not write"
        })
        assertFalse(staleCommit)
        val retry = acquire(9_003L)
        val term = requireNotNull(BufferCoordinatorJobGate.termIfCurrent(retry) { 9_003L })
        assertEquals(8L, term)
        val payload = com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantHandoff.encode(
            com.mocharealm.foundation.fabric.buffer.data.device.VelaAssistantInput(
                vela.deviceId, "capture-retry", "c".repeat(64), "设置一个三十秒计时器"), term)
        assertEquals(8L, org.json.JSONObject(payload.toString(Charsets.UTF_8)).getLong("coordinator_term"))
        assertEquals("saved", BufferCoordinatorJobGate.commitIfCurrent(retry, 9_004L) { "saved" })
    }

    @After
    fun detachGate() {
        BufferCoordinatorJobGate.detach()
    }

    @Test
    fun renewsDuringLongWorkAndDisconnectFencesTheCommit() {
        val phone = CoordinatorMember("phone-local", CoordinatorDeviceClass.PHONE)
        val election = BufferCoordinatorElection(
            sessionId = "buffer-session",
            membershipVersion = "membership-1",
            localMember = phone,
            members = listOf(phone),
            initialReadiness = ready(),
            store = MemoryCoordinatorStateStore(),
        )
        assertEquals(CoordinatorRole.CANDIDATE, election.tick(0L).role)
        assertEquals(CoordinatorRole.COORDINATOR, election.tick(9_000L).role)

        val velaId = "a".repeat(64)
        BufferCoordinatorJobGate.attach(election, velaId)
        val lease = BufferCoordinatorJobGate.acquire(
            captureId = "vela-capture-1",
            sourceDeviceId = velaId,
            stage = "transcribe",
            inputRevision = 1_700_000_000_000L,
            capability = CoordinatorCapability.TRANSCRIBE,
            nowElapsedRealtimeMillis = 9_000L,
        )
        assertNotNull(lease)
        val currentLease = requireNotNull(lease)
        val term = BufferCoordinatorJobGate.termIfCurrent(currentLease) { 9_000L }
        assertNotNull(term)
        assertEquals(term, BufferCoordinatorJobGate.termIfCurrent(currentLease) { 9_001L })
        // Inspection must not consume the classification worker's commit lease.

        // The worker's ASR may exceed the initial 20 s TTL; Enrollment keeps
        // the lease fresh at the election's 5 s renewal cadence.
        var now = 14_000L
        while (now <= 59_000L) {
            BufferCoordinatorJobGate.tick(now)
            now += BufferCoordinatorElection.DEFAULT_LEASE_RENEWAL_MILLIS
        }
        var committed = false
        assertEquals(
            "saved",
            BufferCoordinatorJobGate.commitIfCurrent(requireNotNull(lease), 60_000L) {
                committed = true
                "saved"
            },
        )
        assertTrue(committed)

        val staleLease = BufferCoordinatorJobGate.acquire(
            captureId = "vela-capture-2",
            sourceDeviceId = velaId,
            stage = "classify",
            inputRevision = 1_700_000_000_001L,
            capability = CoordinatorCapability.CLASSIFY,
            nowElapsedRealtimeMillis = 60_000L,
        )
        assertNotNull(staleLease)
        BufferCoordinatorJobGate.detach()
        assertNull(BufferCoordinatorJobGate.termIfCurrent(requireNotNull(staleLease)) { 60_001L })
        var staleCommitRan = false
        assertNull(BufferCoordinatorJobGate.commitIfCurrent(requireNotNull(staleLease), 60_001L) {
            staleCommitRan = true
            "must not write"
        })
        assertFalse(staleCommitRan)
        assertFalse(BufferCoordinatorJobGate.requiresFence("android-local"))
    }

    @Test
    fun localCoordinatorReadinessLossFencesAnInFlightResult() {
        val phone = CoordinatorMember("phone-local", CoordinatorDeviceClass.PHONE)
        val election = BufferCoordinatorElection(
            sessionId = "buffer-session",
            membershipVersion = "membership-1",
            localMember = phone,
            members = listOf(phone),
            initialReadiness = ready(),
            store = MemoryCoordinatorStateStore(),
        )
        election.tick(0L)
        assertEquals(CoordinatorRole.COORDINATOR, election.tick(9_000L).role)

        val velaId = "b".repeat(64)
        BufferCoordinatorJobGate.attach(election, velaId)
        val lease = requireNotNull(BufferCoordinatorJobGate.acquire(
            captureId = "vela-capture-3",
            sourceDeviceId = velaId,
            stage = "classify",
            inputRevision = 1_700_000_000_002L,
            capability = CoordinatorCapability.CLASSIFY,
            nowElapsedRealtimeMillis = 9_000L,
        ))
        election.updateLocalReadiness(ready().copy(canCoordinate = false), 10_000L)

        var committed = false
        assertNull(BufferCoordinatorJobGate.commitIfCurrent(lease, 10_001L) {
            committed = true
            "must not write"
        })
        assertFalse(committed)
    }

    private fun ready() = CoordinatorReadiness(
        configurationValid = true,
        serviceReachable = true,
        backgroundExecutionAvailable = true,
        storageAvailable = true,
        canCoordinate = true,
        capabilities = setOf(CoordinatorCapability.TRANSCRIBE, CoordinatorCapability.CLASSIFY),
    )
}
