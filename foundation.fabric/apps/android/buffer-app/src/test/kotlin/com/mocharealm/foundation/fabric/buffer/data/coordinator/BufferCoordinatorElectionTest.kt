package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.app.Application
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
class BufferCoordinatorElectionTest {
    @Test
    fun authenticatedFollowerHigherTermConvergesForAlreadyElectedLocalOwner() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val store = MemoryCoordinatorStateStore()
        assertTrue(store.persist("buffer-session", "membership-1", CoordinatorPersistentState(6L, phone.deviceId)))
        val election = engine(phone, listOf(phone, vela), readiness(CoordinatorCapability.CLASSIFY), store)
        election.tick(0L)
        assertEquals(7L, election.tick(9_000L).term)
        val oldLease = election.grantLease("job", "capture", "classify", 1L, "skill@1",
            setOf(CoordinatorCapability.CLASSIFY), 9_000L)!!

        val follower = heartbeat(vela, readiness(CoordinatorCapability.CLASSIFY),
            sequence = 1L, term = 8L, coordinatorId = phone.deviceId)
        assertTrue(election.receiveHeartbeat(vela.deviceId, follower, 9_001L))
        assertEquals(CoordinatorRole.COORDINATOR, election.role())
        assertEquals(phone.deviceId, election.snapshot().coordinatorDeviceId)
        assertEquals(8L, election.term())
        assertNull(election.commitIfCurrent(oldLease, 9_002L) { "stale" })
        val retry = election.grantLease("job", "capture", "classify", 1L, "skill@1",
            setOf(CoordinatorCapability.CLASSIFY), 9_002L)!!
        assertEquals(8L, retry.grant.coordinatorTerm)
        assertEquals("saved", election.commitIfCurrent(retry, 9_003L) { "saved" })
        val announced = election.nextHeartbeat(9_004L)
        assertEquals(8L, announced.coordinatorTerm)
        assertEquals(phone.deviceId, announced.coordinatorDeviceId)
        assertEquals(8L, store.load("buffer-session", "membership-1")!!.term)
    }

    @Test
    fun followerTermCannotBypassIdentityMembershipOrReplayValidation() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val election = engine(phone, listOf(phone, vela), readiness(CoordinatorCapability.CLASSIFY))
        election.tick(0L)
        election.tick(9_000L)
        val initial = heartbeat(vela, readiness(CoordinatorCapability.CLASSIFY),
            sequence = 3L, revision = 3L, term = 1L, coordinatorId = phone.deviceId)
        assertTrue(election.receiveHeartbeat(vela.deviceId, initial, 9_001L))
        val higher = initial.copy(heartbeatSequence = 4L, stateRevision = 4L, coordinatorTerm = 8L)
        val invalid = listOf(
            "unknown" to higher,
            phone.deviceId to higher,
            vela.deviceId to higher.copy(deviceId = "unknown"),
            vela.deviceId to higher.copy(sessionId = "different-session"),
            vela.deviceId to higher.copy(membershipVersion = "different-membership"),
            vela.deviceId to higher.copy(protocolVersion = 99),
            vela.deviceId to higher.copy(heartbeatSequence = 3L),
            vela.deviceId to higher.copy(stateRevision = 2L),
            vela.deviceId to higher.copy(stateRevision = 3L),
        )
        for ((authenticated, message) in invalid) {
            assertFalse(election.receiveHeartbeat(authenticated, message, 9_002L))
            assertEquals(1L, election.term())
        }
        assertTrue(election.receiveHeartbeat(vela.deviceId, higher, 9_003L))
        assertEquals(8L, election.term())
        assertTrue(election.receiveHeartbeat(vela.deviceId, higher.copy(
            heartbeatSequence = 5L, stateRevision = 5L, coordinatorTerm = 7L), 9_004L))
        assertEquals(8L, election.term())
    }

    @Test
    fun followerCannotInstallLocalCoordinatorBeforeLocalElection() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val election = engine(phone, listOf(phone, vela), readiness(CoordinatorCapability.CLASSIFY))
        assertTrue(election.receiveHeartbeat(vela.deviceId, heartbeat(vela,
            readiness(CoordinatorCapability.CLASSIFY), sequence = 1L, term = 8L,
            coordinatorId = phone.deviceId), 0L))
        assertEquals(CoordinatorRole.CANDIDATE, election.role())
        assertNull(election.snapshot().coordinatorDeviceId)
        assertEquals(0L, election.term())
    }

    @Test
    fun phoneWinsOverVelaAndTaskExecutorIsChosenByCapability() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val store = MemoryCoordinatorStateStore()
        val election = engine(
            local = phone,
            members = listOf(phone, vela),
            localReady = readiness(CoordinatorCapability.ANSWER),
            store = store,
        )

        for ((sequence, now) in listOf(1L to 0L, 2L to 3_000L, 3L to 6_000L, 4L to 9_000L)) {
            assertTrue(election.receiveHeartbeat(
                authenticatedDeviceId = vela.deviceId,
                heartbeat = heartbeat(
                    device = vela,
                    readiness = readiness(CoordinatorCapability.TRANSCRIBE, CoordinatorCapability.CLASSIFY),
                    sequence = sequence,
                ),
                receivedAtElapsedRealtimeMillis = now,
            ))
        }

        val elected = election.tick(9_000L)
        assertEquals(CoordinatorRole.COORDINATOR, elected.role)
        assertEquals(phone.deviceId, elected.coordinatorDeviceId)
        assertEquals(1L, elected.term)
        assertEquals(
            vela,
            election.chooseExecutor(setOf(CoordinatorCapability.TRANSCRIBE), 9_000L),
        )
        assertNull(election.chooseExecutor(setOf(CoordinatorCapability.GENERATE), 9_000L))
    }

    @Test
    fun phoneHeartbeatTimeoutElectsVelaAndOldLeaseExpiresWithoutRenewal() {
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val election = engine(
            local = vela,
            members = listOf(vela, phone),
            localReady = readiness(CoordinatorCapability.CLASSIFY),
        )

        val phoneLeader = heartbeat(
            phone,
            readiness(CoordinatorCapability.ANSWER),
            sequence = 1L,
            term = 1L,
            coordinatorId = phone.deviceId,
        )
        assertTrue(election.receiveHeartbeat(phone.deviceId, phoneLeader, 0L))
        assertEquals(CoordinatorRole.FOLLOWER, election.role())
        assertEquals(1L, election.term())
        assertTrue(election.receiveHeartbeat(phone.deviceId, phoneLeader.copy(heartbeatSequence = 2L), 3_000L))
        assertTrue(election.receiveHeartbeat(phone.deviceId, phoneLeader.copy(heartbeatSequence = 3L), 6_000L))
        assertFalse(election.receiveHeartbeat(
            phone.deviceId,
            phoneLeader.copy(heartbeatSequence = 3L),
            14_999L,
        ))
        assertEquals(CoordinatorRole.FOLLOWER, election.tick(14_999L).role)

        val elected = election.tick(15_000L)
        assertEquals(CoordinatorRole.COORDINATOR, elected.role)
        assertEquals(2L, elected.term)

        val lease = election.grantLease(
            jobId = "job-1",
            captureId = "capture-1",
            stage = "classify",
            inputRevision = 4L,
            skillVersion = "capture-organizer@1",
            requiredCapabilities = setOf(CoordinatorCapability.CLASSIFY),
            nowElapsedRealtimeMillis = 15_000L,
        )
        assertNotNull(lease)
        val activeLease = lease ?: error("expected a local classification lease")
        assertTrue(election.needsLeaseRenewal(activeLease, 20_000L))
        val renewed = election.renewLease(
            activeLease.grant.leaseId,
            activeLease.grant.coordinatorTerm,
            activeLease.grant.ownerDeviceId,
            activeLease.grant.ownerIncarnationId,
            20_000L,
        )
        assertNotNull(renewed)
        val renewedLease = renewed ?: error("expected lease renewal")
        assertEquals(2L, renewedLease.grant.renewalSequence)
        assertEquals(renewedLease.grant, CoordinatorWireCodec.decodeLeaseGrant(
            CoordinatorWireCodec.encodeLeaseGrant(renewedLease.grant),
        ))
        assertFalse(election.needsLeaseRenewal(renewedLease, 20_001L))
        assertNull(election.acceptLease(renewedLease.grant, vela.deviceId, 20_002L))
        assertEquals("committed", election.commitIfCurrent(renewedLease, 20_003L) { "committed" })
        assertNull(election.commitIfCurrent(renewedLease, 40_000L) { "expired result" })
        assertNull(election.acceptLease(activeLease.grant, vela.deviceId, 40_000L))
        assertNull(election.chooseExecutor(setOf(CoordinatorCapability.GENERATE), 40_000L))
    }

    @Test
    fun higherPriorityRecoveryWaitsForStableWindowBeforeFencingCurrentWork() {
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val election = engine(
            local = vela,
            members = listOf(vela, phone),
            localReady = readiness(CoordinatorCapability.CLASSIFY),
        )
        election.tick(0L)
        assertEquals(CoordinatorRole.COORDINATOR, election.tick(9_000L).role)

        val initialLease = election.grantLease(
            "job-2", "capture-2", "classify", 1L, "skill@1",
            setOf(CoordinatorCapability.CLASSIFY), 9_000L,
        )!!
        val firstPhoneHeartbeat = heartbeat(
            phone,
            readiness(CoordinatorCapability.CLASSIFY),
            sequence = 1L,
            revision = 2L,
            term = 2L,
            coordinatorId = phone.deviceId,
        )
        assertTrue(election.receiveHeartbeat(phone.deviceId, firstPhoneHeartbeat, 10_000L))
        assertEquals(CoordinatorRole.COORDINATOR, election.tick(10_000L).role)
        assertEquals(1L, election.snapshot().term)
        assertEquals("current work may finish", election.commitIfCurrent(initialLease, 10_001L) {
            "current work may finish"
        })

        for ((sequence, now) in listOf(2L to 13_000L, 3L to 16_000L, 4L to 19_000L, 5L to 22_000L)) {
            assertTrue(election.receiveHeartbeat(
                phone.deviceId,
                firstPhoneHeartbeat.copy(heartbeatSequence = sequence),
                now,
            ))
        }
        assertEquals(CoordinatorRole.HANDING_OFF, election.tick(25_000L).role)
        assertEquals(1L, election.snapshot().term)

        assertTrue(election.receiveHeartbeat(
            phone.deviceId,
            firstPhoneHeartbeat.copy(heartbeatSequence = 6L),
            25_000L,
        ))
        assertEquals(phone.deviceId, election.snapshot().coordinatorDeviceId)
        assertEquals(2L, election.snapshot().term)
        assertNull(election.commitIfCurrent(initialLease, 25_001L) { "stale result" })
    }

    @Test
    fun restartRestoresTermButDoesNotRestoreAnOldLease() {
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val store = MemoryCoordinatorStateStore()
        val first = engine(vela, listOf(vela), readiness(CoordinatorCapability.CLASSIFY), store)
        first.tick(0L)
        assertEquals(CoordinatorRole.COORDINATOR, first.tick(9_000L).role)
        val oldLease = first.grantLease(
            "job-3", "capture-3", "classify", 1L, "skill@1",
            setOf(CoordinatorCapability.CLASSIFY), 9_000L,
        )!!

        val restarted = engine(vela, listOf(vela), readiness(CoordinatorCapability.CLASSIFY), store)
        assertEquals(CoordinatorRole.CANDIDATE, restarted.tick(0L).role)
        assertNull(restarted.commitIfCurrent(oldLease, 1L) { "must not commit" })
        assertEquals(CoordinatorRole.COORDINATOR, restarted.tick(9_000L).role)
        assertEquals(2L, restarted.snapshot().term)
    }

    @Test
    fun rejectsUnknownIdentityAndStaleHeartbeatSequence() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val election = engine(phone, listOf(phone, vela), readiness(CoordinatorCapability.ANSWER))
        val message = heartbeat(vela, readiness(CoordinatorCapability.CLASSIFY), sequence = 3L)

        assertFalse(election.receiveHeartbeat("unknown", message.copy(deviceId = "unknown"), 0L))
        assertTrue(election.receiveHeartbeat(vela.deviceId, message, 1_000L))
        assertFalse(election.receiveHeartbeat(
            vela.deviceId,
            message.copy(heartbeatSequence = 2L),
            2_000L,
        ))
    }

    @Test
    fun heartbeatPayloadCodecRoundTripsAndReceiveUsesFabricIdentity() {
        val phone = CoordinatorMember("phone-01", CoordinatorDeviceClass.PHONE)
        val vela = CoordinatorMember("vela-01", CoordinatorDeviceClass.VELA)
        val election = engine(phone, listOf(phone, vela), readiness(CoordinatorCapability.ANSWER))
        val message = heartbeat(vela, readiness(CoordinatorCapability.CLASSIFY), sequence = 7L)
        val payload = CoordinatorWireCodec.encodeHeartbeat(message)

        assertEquals(message, CoordinatorWireCodec.decodeHeartbeat(payload))
        assertFalse(election.receiveHeartbeatPayload("untrusted-id", payload, 1_000L))
        assertTrue(election.receiveHeartbeatPayload(vela.deviceId, payload, 1_001L))
        assertFalse(election.receiveHeartbeatPayload(vela.deviceId, byteArrayOf(0x7f), 1_002L))
        assertFalse(election.receiveHeartbeatPayload(vela.deviceId, payload, 1_003L))
    }

    private fun engine(
        local: CoordinatorMember,
        members: Collection<CoordinatorMember>,
        localReady: CoordinatorReadiness,
        store: CoordinatorStateStore = MemoryCoordinatorStateStore(),
    ) = BufferCoordinatorElection(
        sessionId = "buffer-session",
        membershipVersion = "membership-1",
        localMember = local,
        members = members,
        initialReadiness = localReady,
        store = store,
    )

    private fun heartbeat(
        device: CoordinatorMember,
        readiness: CoordinatorReadiness,
        sequence: Long,
        revision: Long = 1L,
        term: Long = 0L,
        coordinatorId: String? = null,
    ) = CoordinatorHeartbeat(
        sessionId = "buffer-session",
        membershipVersion = "membership-1",
        deviceId = device.deviceId,
        incarnationId = "boot-${device.deviceId}",
        protocolVersion = BufferCoordinatorElection.PROTOCOL_VERSION,
        stateRevision = revision,
        heartbeatSequence = sequence,
        readiness = readiness,
        coordinatorTerm = term,
        coordinatorDeviceId = coordinatorId,
    )

    private fun readiness(vararg capabilities: CoordinatorCapability) = CoordinatorReadiness(
        configurationValid = true,
        serviceReachable = true,
        backgroundExecutionAvailable = true,
        storageAvailable = true,
        canCoordinate = true,
        capabilities = capabilities.toSet(),
    )
}
