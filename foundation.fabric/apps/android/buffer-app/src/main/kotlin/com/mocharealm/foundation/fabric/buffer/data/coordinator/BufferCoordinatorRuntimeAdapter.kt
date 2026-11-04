package com.mocharealm.foundation.fabric.buffer.data.coordinator

/**
 * Small Android app boundary around the deterministic election domain. Call
 * [tick] from the Enrollment worker's existing 100 ms loop and route only
 * authenticated Fabric peer payloads to [onAuthenticatedMessage].
 *
 * The default readiness gate is closed: a phone cannot advertise coordinator
 * eligibility until its raw Vela input import and processing path is active.
 * The separate Vela assistant is outside this Buffer-only election.
 */
internal class BufferCoordinatorRuntimeAdapter(
    private val election: BufferCoordinatorElection,
    private val localReadiness: () -> CoordinatorReadiness,
    private val broadcastHeartbeat: (ByteArray) -> Boolean,
    private val sendToPeer: (String, ByteArray) -> Boolean,
    private val inputHandlingReady: () -> Boolean = { false },
    private val onLeaseAccepted: (CoordinatorLease) -> Unit = {},
) {
    private var lastHeartbeatAttemptElapsedMillis: Long? = null

    /** Refresh readiness, expire stale peers/leases, and broadcast at 3 s cadence. */
    fun tick(nowElapsedRealtimeMillis: Long): CoordinatorSnapshot {
        val proposed = localReadiness()
        election.updateLocalReadiness(
            proposed.copy(canCoordinate = proposed.canCoordinate && inputHandlingReady()),
            nowElapsedRealtimeMillis,
        )
        val snapshot = election.tick(nowElapsedRealtimeMillis)
        val previousSend = lastHeartbeatAttemptElapsedMillis
        if (previousSend == null ||
            nowElapsedRealtimeMillis - previousSend >= BufferCoordinatorElection.DEFAULT_HEARTBEAT_INTERVAL_MILLIS
        ) {
            lastHeartbeatAttemptElapsedMillis = nowElapsedRealtimeMillis
            broadcastHeartbeat(election.encodeHeartbeat(nowElapsedRealtimeMillis))
        }
        return snapshot
    }

    /**
     * [authenticatedPeerDeviceId] must come from the active Fabric transport
     * callback/peer identity, never an ID embedded in the application payload.
     */
    fun onAuthenticatedMessage(
        authenticatedPeerDeviceId: String,
        payload: ByteArray,
        receivedAtElapsedRealtimeMillis: Long,
    ): Boolean {
        return when (CoordinatorWireCodec.messageType(payload)) {
            "coordinator_heartbeat" -> election.receiveHeartbeatPayload(
                authenticatedPeerDeviceId,
                payload,
                receivedAtElapsedRealtimeMillis,
            )
            "coordinator_lease_grant" -> {
                val grant = CoordinatorWireCodec.decodeLeaseGrant(payload) ?: return false
                val lease = election.acceptLease(grant, authenticatedPeerDeviceId, receivedAtElapsedRealtimeMillis)
                    ?: return false
                onLeaseAccepted(lease)
                true
            }
            else -> false
        }
    }

    /** Send a coordinator-created lease to the authenticated task owner. */
    fun sendLeaseGrant(lease: CoordinatorLease): Boolean {
        val snapshot = election.snapshot()
        if (!snapshot.mayAssignJobs || lease.grant.coordinatorTerm != snapshot.coordinatorTerm) return false
        return sendToPeer(lease.grant.ownerDeviceId, CoordinatorWireCodec.encodeLeaseGrant(lease.grant))
    }

    fun snapshot(): CoordinatorSnapshot = election.snapshot()
    fun role(): CoordinatorRole = election.role()
    fun term(): Long = election.term()
}
