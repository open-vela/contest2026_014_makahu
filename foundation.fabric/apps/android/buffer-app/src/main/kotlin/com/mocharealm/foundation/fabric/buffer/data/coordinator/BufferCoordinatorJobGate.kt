package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import org.json.JSONObject

import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureProtocol

/** Opaque token held by one worker while a Vela-originated task is running. */
internal data class BufferCoordinatorJobLease internal constructor(
    internal val generation: Long,
    internal val leaseId: String,
    internal val captureId: String,
    internal val sourceVelaId: String,
    internal val deviceTerm: Long? = null,
    internal val deviceIncarnation: String? = null,
)
internal class BufferCoordinatorAttachment internal constructor(
    internal val generation: Long,
    internal val sourceVelaId: String,
    internal val election: BufferCoordinatorElection,
    internal val isLive: () -> Boolean,
    internal val localDeviceId: String?,
    internal val membership: String?,
) {
    internal var authority: DeviceAuthority? = null
    internal val retiredIncarnations = linkedSetOf<String>()
    internal val authorityRequests = linkedMapOf<Pair<String,Long>,Long>()
}
internal data class DeviceAuthority(
    val incarnation: String,
    val sequence: Long,
    val revision: Long,
    val term: Long,
    val owner: String?,
    val seen: Long,
    val expiresAt: Long,
    val valid: Boolean,
)

/**
 * Fences only work whose persisted capture source is a Fabric Vela identity.
 * Each Enrollment attaches its authenticated peer and connection guard, calls
 * [tick] from its 100 ms worker loop, and detaches only its own handle on loss. Local
 * phone captures never enter this gate.
 *
 * This gate executes only a lease assigned to the local coordinator. Therefore
 * lease renewal is local domain state and requires no additional BLE wire
 * message; the election's 20 s TTL and 5 s renewal cadence remain authoritative.
 */
internal object BufferCoordinatorJobGate {
    private data class ActiveLease(
        val handle: BufferCoordinatorJobLease,
        var lease: CoordinatorLease,
    )

    private data class CommitResult<T : Any>(
        val committed: Boolean,
        val value: T? = null,
    )

    private val lock = Any()
    private val active = linkedMapOf<String, ActiveLease>()
    private fun key(handle: BufferCoordinatorJobLease)="${handle.sourceVelaId}:${handle.leaseId}"
    private var generation = 0L
    private val attachments=linkedMapOf<String,BufferCoordinatorAttachment>()

    /** Call once when Enrollment has an authenticated Active Fabric peer. */
    fun attach(localElection: BufferCoordinatorElection, sourceVelaId: String,
               localDeviceId: String? = null, membership: String? = null,
               isLive: () -> Boolean = {true}): BufferCoordinatorAttachment {
        require(VelaPendingCaptureProtocol.isFabricDeviceId(sourceVelaId)) {
            "sourceVelaId must be the authenticated 32-byte Fabric identity in hex"
        }
        synchronized(lock) {
            val previous=attachments[sourceVelaId]
            if(previous!=null && previous.election===localElection)return previous
            check(previous!=null || attachments.size<64) { "device connection capacity exceeded" }
            generation = Math.addExact(generation, 1L)
            previous?.let(::invalidateLocked)
            return BufferCoordinatorAttachment(generation,sourceVelaId,localElection,isLive,localDeviceId,membership).also { attachments[sourceVelaId]=it }
        }
    }

    /** The receipt is valid only until this phone's own request deadline.
     * A queued/replayed device heartbeat cannot start a new lease on arrival. */
    fun recordDeviceAuthorityRequest(attachment: BufferCoordinatorAttachment?, payload: ByteArray, sentAt: Long) {
        if(attachment?.localDeviceId==null)return
        val request=CoordinatorWireCodec.decodeHeartbeat(payload)?:return
        if(request.deviceId!=attachment.localDeviceId||request.membershipVersion!=attachment.membership||
            request.sessionId!="buffer-v1-${attachment.membership}")return
        synchronized(lock){
            if(attachments[attachment.sourceVelaId]!==attachment||!attachment.isLive())return
            attachment.authorityRequests.putIfAbsent(request.incarnationId to request.heartbeatSequence,sentAt)
            while(attachment.authorityRequests.size>24)attachment.authorityRequests.remove(attachment.authorityRequests.keys.first())
        }
    }

    /** Only the authenticated device chooses its input owner. Other phones'
     * readiness is not visible over this phone's individual BLE connection. */
    fun onDeviceHeartbeat(attachment: BufferCoordinatorAttachment?, payload: ByteArray, now: Long) {
        if (attachment == null || attachment.localDeviceId == null) return
        val heartbeat = CoordinatorWireCodec.decodeHeartbeat(payload) ?: return
        val json=runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull()?:return
        val version=json.opt("device_authority_version")
        val requestSequence=json.opt("authority_request_sequence") as? Number?:return
        val requestIncarnation=json.opt("authority_request_incarnation") as? String?:return
        val ttl=json.opt("authority_ttl_ms") as? Number?:return
        if(requestSequence.toDouble()!=requestSequence.toLong().toDouble()||requestSequence.toLong()<0||
            ttl.toDouble()!=ttl.toLong().toDouble()||ttl.toLong() !in setOf(0L,12_000L))return
        if (version != 1 || heartbeat.deviceId != attachment.sourceVelaId ||
            heartbeat.membershipVersion != attachment.membership ||
            heartbeat.sessionId != "buffer-v1-${attachment.membership}" ||
            now < 0 || heartbeat.incarnationId.length !in 1..64) return
        synchronized(lock) {
            if (attachments[attachment.sourceVelaId] !== attachment || !attachment.isLive()) return
            val requestedAt=attachment.authorityRequests[requestIncarnation to requestSequence.toLong()]
            val deadline=requestedAt?.let { Math.addExact(it,ttl.toLong()) }?:0L
            val valid=heartbeat.readiness.storageAvailable&&ttl.toLong()>0&&requestedAt!=null&&now>=requestedAt&&now<deadline
            val previous = attachment.authority
            if (heartbeat.incarnationId in attachment.retiredIncarnations) return
            if (previous != null) {
                if (now < previous.seen || heartbeat.coordinatorTerm < previous.term) return
                if (heartbeat.incarnationId == previous.incarnation &&
                    (heartbeat.heartbeatSequence <= previous.sequence || heartbeat.stateRevision < previous.revision ||
                        (heartbeat.stateRevision == previous.revision &&
                            (heartbeat.coordinatorTerm != previous.term || heartbeat.coordinatorDeviceId != previous.owner)))) return
                if (heartbeat.incarnationId != previous.incarnation) {
                    attachment.retiredIncarnations += previous.incarnation
                    while (attachment.retiredIncarnations.size > 8) attachment.retiredIncarnations.remove(attachment.retiredIncarnations.first())
                }
            }
            if (previous == null || previous.term != heartbeat.coordinatorTerm ||
                previous.owner != heartbeat.coordinatorDeviceId || previous.incarnation != heartbeat.incarnationId || now >= previous.expiresAt || !valid)
                invalidateLocked(attachment)
            attachment.authority = DeviceAuthority(heartbeat.incarnationId, heartbeat.heartbeatSequence,
                heartbeat.stateRevision, heartbeat.coordinatorTerm, heartbeat.coordinatorDeviceId, now, deadline, valid)
        }
    }
    private fun deviceAllows(attachment: BufferCoordinatorAttachment, now: Long): Boolean {
        if (attachment.localDeviceId == null) return true // In-process election-only callers.
        val authority = attachment.authority ?: return false
        return authority.valid && authority.term > 0 && authority.owner == attachment.localDeviceId &&
            now >= authority.seen && now < authority.expiresAt
    }

    /** Runtime state uses the device owner fence independently of a model job. */
    fun deviceTermIfCurrent(attachment: BufferCoordinatorAttachment?): Long? = synchronized(lock) {
        if(attachment==null||attachments[attachment.sourceVelaId]!==attachment||!attachment.isLive()||
            !deviceAllows(attachment,SystemClock.elapsedRealtime())) null else attachment.authority?.term
    }

    /** Call for every Enrollment tick, using the same elapsed-realtime clock. */
    fun tick(nowElapsedRealtimeMillis: Long) {
        synchronized(lock) {
            attachments.values.toList().forEach { tick(it,nowElapsedRealtimeMillis) }
        }
    }
    fun tick(attachment: BufferCoordinatorAttachment,nowElapsedRealtimeMillis: Long) {
        synchronized(lock) {
            if(attachments[attachment.sourceVelaId]!==attachment)return
            if(!attachment.isLive()){detach(attachment);return}
            val currentElection = attachment.election
            if (!deviceAllows(attachment,nowElapsedRealtimeMillis)) { invalidateLocked(attachment); return }
            val snapshot = try {
                currentElection.tick(nowElapsedRealtimeMillis)
            } catch (_: IllegalArgumentException) {
                // Another authenticated callback may have advanced the shared
                // election clock after this Enrollment tick sampled `now`.
                // Skip this renewal pass; the next 100 ms tick will retry.
                return
            }
            val currentSource = attachment.sourceVelaId
            for (entry in active.values.toList()) {
                if(entry.handle.sourceVelaId!=currentSource)continue
                val leaseId=entry.handle.leaseId
                val grant = entry.lease.grant
                if (currentSource != entry.handle.sourceVelaId ||
                    snapshot.role != CoordinatorRole.COORDINATOR ||
                    snapshot.coordinatorDeviceId != grant.ownerDeviceId ||
                    snapshot.coordinatorTerm != grant.coordinatorTerm
                ) {
                    removeLocked(currentElection, leaseId, entry)
                    continue
                }
                val due = try {
                    currentElection.needsLeaseRenewal(entry.lease, nowElapsedRealtimeMillis)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (!due) continue
                val renewed = try {
                    currentElection.renewLease(
                        leaseId = grant.leaseId,
                        term = grant.coordinatorTerm,
                        authenticatedOwnerId = grant.ownerDeviceId,
                        authenticatedOwnerIncarnationId = grant.ownerIncarnationId,
                        nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                    )
                } catch (_: IllegalArgumentException) {
                    continue
                }
                if (renewed == null) removeLocked(currentElection, leaseId, entry)
                else entry.lease = renewed
            }
        }
    }

    /** True means processing must fail closed unless a current lease is acquired. */
    fun requiresFence(sourceDeviceId: String): Boolean =
        VelaPendingCaptureProtocol.isFabricDeviceId(sourceDeviceId)

    /** Acquire a 20 s local-owner lease for a pending Vela transcription/classification task. */
    fun acquire(
        captureId: String,
        sourceDeviceId: String,
        stage: String,
        inputRevision: Long,
        capability: CoordinatorCapability,
        nowElapsedRealtimeMillis: Long,
    ): BufferCoordinatorJobLease? {
        if (!requiresFence(sourceDeviceId)) return null
        require(captureId.isNotBlank() && stage in setOf("transcribe", "classify"))
        require(inputRevision >= 0L)
        synchronized(lock) {
            val attachment=attachments[sourceDeviceId]?:return null
            if(!attachment.isLive()){detach(attachment);return null}
            if (!deviceAllows(attachment,nowElapsedRealtimeMillis)) return null
            val currentElection = attachment.election

            val jobId = "buffer:$captureId:$stage"
            val lease = try {
                currentElection.grantLease(
                    jobId = jobId,
                    captureId = captureId,
                    stage = stage,
                    inputRevision = inputRevision,
                    skillVersion = "buffer-v1",
                    requiredCapabilities = setOf(capability),
                    nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
                )
            } catch (_: IllegalArgumentException) {
                // A peer callback can win the election monitor after the worker
                // sampled its elapsed time. Fail closed and retry next pass.
                return null
            } ?: return null
            val after = currentElection.snapshot()
            // The Buffer copy is local. Do not run or commit this task under a
            // lease assigned to a different mobile member.
            if (after.role != CoordinatorRole.COORDINATOR ||
                after.coordinatorDeviceId != lease.grant.ownerDeviceId ||
                after.coordinatorTerm != lease.grant.coordinatorTerm
            ) {
                currentElection.releaseLease(
                    lease.grant.leaseId,
                    lease.grant.coordinatorTerm,
                    lease.grant.ownerDeviceId,
                )
                return null
            }
            val handle = BufferCoordinatorJobLease(
                generation = attachment.generation,
                leaseId = lease.grant.leaseId,
                captureId = captureId,
                sourceVelaId = sourceDeviceId,
                deviceTerm = attachment.authority?.term,
                deviceIncarnation = attachment.authority?.incarnation,
            )
            active[key(handle)] = ActiveLease(handle, lease)
            return handle
        }
    }

    /**
     * Execute the Repository write while the election domain holds its current
     * term/lease fence. A null result means role, term, connection, or lease
     * ownership changed and the caller must discard the computed output.
     * Callers whose callback writes BufferRepository must already hold that
     * repository's monitor; this fixes the lock order as Repository -> Gate ->
     * election -> DB and avoids deadlock with repository-serialized callers.
     */
    fun <T : Any> commitIfCurrent(
        handle: BufferCoordinatorJobLease,
        nowElapsedRealtimeMillis: Long,
        commit: () -> T,
    ): T? {
        synchronized(lock) {
            val currentElection = currentLocked(handle) ?: return null
            val entry = active[key(handle)] ?: return null
            if (handle != entry.handle) return null

            val grant = entry.lease.grant
            var enteredDomainFence = false
            val result = try {
                currentElection.commitIfCurrent(
                    entry.lease,
                    nowElapsedRealtimeMillis,
                ) {
                    enteredDomainFence = true
                    val snapshot = currentElection.snapshot()
                    if (snapshot.role != CoordinatorRole.COORDINATOR ||
                        snapshot.coordinatorDeviceId != grant.ownerDeviceId ||
                        snapshot.coordinatorTerm != grant.coordinatorTerm
                    ) {
                        CommitResult<T>(committed = false)
                    } else {
                        CommitResult(committed = true, value = commit())
                    }
                }
            } catch (error: IllegalArgumentException) {
                if (enteredDomainFence) throw error
                // The election monitor may have observed a newer timestamp
                // from a peer callback before this worker's commit attempt.
                return null
            }
            removeLocked(currentElection, handle.leaseId, entry)
            return result?.takeIf { it.committed }?.value
        }
    }

    /** Read the current term without consuming the worker's commit lease. */
    fun termIfCurrent(handle: BufferCoordinatorJobLease, now: () -> Long): Long? = synchronized(lock) {
        val current = currentLocked(handle) ?: return@synchronized null
        val entry = active[key(handle)] ?: return@synchronized null
        if (entry.handle != handle) return@synchronized null
        synchronized(current) {
            // Sample after entering the election monitor. A BLE heartbeat cannot
            // advance its monotonic clock between sampling and this inspection.
            try {
                current.commitIfCurrent(entry.lease, now()) {
                    val snapshot = current.snapshot()
                    if (snapshot.role == CoordinatorRole.COORDINATOR &&
                        snapshot.coordinatorDeviceId == entry.lease.grant.ownerDeviceId &&
                        snapshot.coordinatorTerm == entry.lease.grant.coordinatorTerm)
                        handle.deviceTerm ?: entry.lease.grant.coordinatorTerm else null
                }
            } catch (_: IllegalArgumentException) { null }
        }
    }

    fun release(handle: BufferCoordinatorJobLease) {
        synchronized(lock) {
            val attachment=attachments[handle.sourceVelaId]?.takeIf { it.generation==handle.generation }?:return
            val entry = active[key(handle)] ?: return
            if (handle == entry.handle) removeLocked(attachment.election, handle.leaseId, entry)
        }
    }

    /** Keep the gateway's device-owner lease while several authenticated mesh
     * reads/renewals share it. Imported facts cannot create this live token. */
    fun <T : Any> withCurrent(handle: BufferCoordinatorJobLease, now: () -> Long, block: () -> T): T? = synchronized(lock) {
        val current = currentLocked(handle) ?: return@synchronized null
        val entry = active[key(handle)] ?: return@synchronized null
        if (entry.handle != handle) return@synchronized null
        synchronized(current) {
            var entered = false
            try {
                current.commitIfCurrent(entry.lease, now()) {
                    val snapshot = current.snapshot()
                    if (snapshot.role != CoordinatorRole.COORDINATOR || snapshot.coordinatorDeviceId != entry.lease.grant.ownerDeviceId ||
                        snapshot.coordinatorTerm != entry.lease.grant.coordinatorTerm) null
                    else { entered = true; block() }
                }
            } catch (error: IllegalArgumentException) { if (entered) throw error else null }
        }
    }

    /** Process-wide shutdown/reset. Enrollment teardown uses its attachment. */
    fun detach() {
        synchronized(lock) {
            attachments.values.toList().forEach(::invalidateLocked)
            attachments.clear()
            generation = Math.addExact(generation, 1L)
        }
    }
    fun detach(attachment: BufferCoordinatorAttachment?) {
        if(attachment==null)return
        synchronized(lock) {
            if(attachments[attachment.sourceVelaId]!==attachment)return
            invalidateLocked(attachment)
            attachments.remove(attachment.sourceVelaId)
        }
    }
    fun revokeSource(sourceVelaId: String) {
        synchronized(lock) { attachments[sourceVelaId]?.let { invalidateLocked(it);attachments.remove(sourceVelaId) } }
    }
    private fun currentLocked(handle: BufferCoordinatorJobLease): BufferCoordinatorElection? {
        val attachment=attachments[handle.sourceVelaId]?.takeIf { it.generation==handle.generation }?:return null
        if(!attachment.isLive()){detach(attachment);return null}
        if ((attachment.localDeviceId != null && !deviceAllows(attachment,SystemClock.elapsedRealtime())) ||
            (handle.deviceTerm != null && (attachment.authority?.term != handle.deviceTerm ||
                attachment.authority?.incarnation != handle.deviceIncarnation))) return null
        return attachment.election
    }
    private fun invalidateLocked(attachment: BufferCoordinatorAttachment) {
        active.values.toList().forEach { entry ->
            if(entry.handle.sourceVelaId==attachment.sourceVelaId)removeLocked(attachment.election,entry.handle.leaseId,entry)
        }
    }

    private fun removeLocked(
        currentElection: BufferCoordinatorElection,
        leaseId: String,
        entry: ActiveLease,
    ) {
        if (active[key(entry.handle)] !== entry) return
        active.remove(key(entry.handle))
        currentElection.releaseLease(
            leaseId,
            entry.lease.grant.coordinatorTerm,
            entry.lease.grant.ownerDeviceId,
        )
    }
}
