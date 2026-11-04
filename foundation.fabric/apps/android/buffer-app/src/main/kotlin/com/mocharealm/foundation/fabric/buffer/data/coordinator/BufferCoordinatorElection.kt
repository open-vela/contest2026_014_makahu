package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.content.Context
import android.content.Context.MODE_PRIVATE
import java.security.MessageDigest
import java.util.UUID

/** Application-task role. This is deliberately separate from Fabric Session leadership. */
enum class CoordinatorRole {
    WAITING,
    CANDIDATE,
    COORDINATOR,
    FOLLOWER,
    HANDING_OFF,
}

enum class CoordinatorDeviceClass { PHONE, TABLET, VELA, OTHER }

enum class CoordinatorCapability { TRANSCRIBE, CLASSIFY, ANSWER, GENERATE }

data class CoordinatorMember(
    val deviceId: String,
    val deviceClass: CoordinatorDeviceClass,
)

/**
 * Readiness contains booleans and capability names only; never put credentials in a heartbeat.
 * [backgroundExecutionAvailable] must mean the app can actually finish eligible work while
 * backgrounded under current Android service/OS policy. A Fabric binding, active BLE link, or
 * recent heartbeat alone is not evidence of that capability.
 */
data class CoordinatorReadiness(
    val configurationValid: Boolean,
    val serviceReachable: Boolean,
    val backgroundExecutionAvailable: Boolean,
    val storageAvailable: Boolean,
    val canCoordinate: Boolean,
    val capabilities: Set<CoordinatorCapability>,
) {
    internal val coordinatorEligible: Boolean
        get() = configurationValid && serviceReachable && backgroundExecutionAvailable &&
            storageAvailable && canCoordinate

    internal fun canExecute(required: Set<CoordinatorCapability>): Boolean =
        configurationValid && serviceReachable && backgroundExecutionAvailable &&
            storageAvailable && capabilities.containsAll(required)
}

/** Sent over the already authenticated Fabric election channel. Times are never sent. */
data class CoordinatorHeartbeat(
    val sessionId: String,
    val membershipVersion: String,
    val deviceId: String,
    val incarnationId: String,
    val protocolVersion: Int,
    val stateRevision: Long,
    val heartbeatSequence: Long,
    val readiness: CoordinatorReadiness,
    val coordinatorTerm: Long,
    val coordinatorDeviceId: String?,
)

data class CoordinatorPersistentState(
    val term: Long,
    val coordinatorDeviceId: String?,
)

/** Implementations must persist atomically before returning true. */
interface CoordinatorStateStore {
    fun load(sessionId: String, membershipVersion: String): CoordinatorPersistentState?
    fun persist(
        sessionId: String,
        membershipVersion: String,
        state: CoordinatorPersistentState,
    ): Boolean
}

class MemoryCoordinatorStateStore : CoordinatorStateStore {
    private val states = mutableMapOf<Pair<String, String>, CoordinatorPersistentState>()

    @Synchronized
    override fun load(sessionId: String, membershipVersion: String) =
        states[sessionId to membershipVersion]

    @Synchronized
    override fun persist(
        sessionId: String,
        membershipVersion: String,
        state: CoordinatorPersistentState,
    ): Boolean {
        val key = sessionId to membershipVersion
        val previous = states[key]
        if (previous != null && state.term < previous.term) return false
        states[key] = state
        return true
    }
}

/** Android persistence adapter. A restarted process restores the term, never an old job lease. */
class SharedPreferencesCoordinatorStateStore(context: Context) : CoordinatorStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "buffer_coordinator_state_v1",
        MODE_PRIVATE,
    )

    override fun load(sessionId: String, membershipVersion: String): CoordinatorPersistentState? {
        val prefix = keyPrefix(sessionId, membershipVersion)
        if (!preferences.contains("$prefix.term")) return null
        val term = preferences.getLong("$prefix.term", -1L)
        if (term < 0L) return null
        return CoordinatorPersistentState(term, preferences.getString("$prefix.coordinator", null))
    }

    @Synchronized
    override fun persist(
        sessionId: String,
        membershipVersion: String,
        state: CoordinatorPersistentState,
    ): Boolean {
        if (state.term < 0L) return false
        val prefix = keyPrefix(sessionId, membershipVersion)
        if (preferences.getLong("$prefix.term", -1L) > state.term) return false
        val editor = preferences.edit().putLong("$prefix.term", state.term)
        if (state.coordinatorDeviceId == null) editor.remove("$prefix.coordinator")
        else editor.putString("$prefix.coordinator", state.coordinatorDeviceId)
        return editor.commit()
    }

    private fun keyPrefix(sessionId: String, membershipVersion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sessionId\u0000$membershipVersion".toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "session.$digest"
    }
}

/** A lease grant carries a relative TTL. Never compare another device's monotonic clock value. */
data class CoordinatorLeaseGrant(
    val jobId: String,
    val captureId: String,
    val stage: String,
    val inputRevision: Long,
    val skillVersion: String,
    val ownerDeviceId: String,
    val coordinatorTerm: Long,
    val leaseId: String,
    val ttlMillis: Long,
    /** Monotonically increased by the coordinator on every accepted renewal. */
    val renewalSequence: Long = 1L,
    /** Prevents grants from an earlier owner process being resumed after restart. */
    val ownerIncarnationId: String = "",
)

/** Local view of a grant; deadline is in this process's SystemClock.elapsedRealtime() domain. */
data class CoordinatorLease(
    val grant: CoordinatorLeaseGrant,
    val renewAtElapsedRealtimeMillis: Long,
    val localDeadlineElapsedRealtimeMillis: Long,
)

data class CoordinatorSnapshot(
    val role: CoordinatorRole,
    val coordinatorDeviceId: String?,
    val coordinatorTerm: Long,
    val reason: String,
) {
    val mayAssignJobs: Boolean get() = role == CoordinatorRole.COORDINATOR
    val term: Long get() = coordinatorTerm
}

/**
 * App-level election over one fixed, authenticated Fabric membership view.
 * The caller supplies local monotonic times (Android: SystemClock.elapsedRealtime()).
 * A partition may have its own coordinator; this class does not claim global consensus.
 */
class BufferCoordinatorElection(
    private val sessionId: String,
    private val membershipVersion: String,
    private val localMember: CoordinatorMember,
    members: Collection<CoordinatorMember>,
    initialReadiness: CoordinatorReadiness,
    private val store: CoordinatorStateStore,
    private val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    private val missedHeartbeats: Int = DEFAULT_MISSED_HEARTBEATS,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_DURATION_MILLIS,
    private val leaseRenewalMillis: Long = DEFAULT_LEASE_RENEWAL_MILLIS,
    private val recoveryStableMillis: Long = DEFAULT_RECOVERY_STABLE_MILLIS,
    private val incarnationId: String = UUID.randomUUID().toString(),
) {
    private data class PeerState(
        var heartbeat: CoordinatorHeartbeat,
        var lastSeenElapsedMillis: Long,
        val retiredIncarnations: MutableSet<String> = linkedSetOf(),
    )

    private data class ActiveLease(
        var grant: CoordinatorLeaseGrant,
        var deadlineElapsedMillis: Long,
        val requiredCapabilities: Set<CoordinatorCapability>,
    )

    private val membersById = members.associateBy { it.deviceId }
    private val peers = mutableMapOf<String, PeerState>()
    private val activeLeases = mutableMapOf<String, ActiveLease>()
    private val retiredLeaseIds = linkedSetOf<String>()
    private val heartbeatTimeoutMillis =
        Math.multiplyExact(heartbeatIntervalMillis, missedHeartbeats.toLong())

    private var readiness = initialReadiness
    private var stateRevision = 1L
    private var heartbeatSequence = 0L
    private var lastEmittedState: Triple<Long, String?, CoordinatorReadiness>? = null
    private var lastEmittedRevision = 0L
    private var currentTerm = store.load(sessionId, membershipVersion)?.term ?: 0L
    private var currentCoordinatorId: String? = null
    private var lastCoordinatorSeenElapsedMillis: Long? = null
    private var startedAtElapsedMillis: Long? = null
    private var leaderLostAtElapsedMillis: Long? = null
    private var preferredCandidateId: String? = null
    private var preferredCandidateSinceElapsedMillis: Long? = null
    private var lastObservedElapsedMillis: Long? = null
    private var role = CoordinatorRole.WAITING
    private var reason = "waiting for an authenticated ready member"

    init {
        require(sessionId.isNotBlank())
        require(membershipVersion.isNotBlank())
        require(localMember.deviceId.isNotBlank())
        require(incarnationId.isNotBlank())
        require(membersById[localMember.deviceId] == localMember) {
            "local device must be present in the authenticated membership"
        }
        require(membersById.size == members.size) { "device IDs in a membership must be unique" }
        require(heartbeatIntervalMillis > 0L && missedHeartbeats > 0)
        require(leaseDurationMillis > 0L && leaseRenewalMillis in 1 until leaseDurationMillis)
        require(recoveryStableMillis > 0L)
    }

    @Synchronized
    fun updateLocalReadiness(
        readiness: CoordinatorReadiness,
        nowElapsedRealtimeMillis: Long,
    ): CoordinatorSnapshot {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        if (this.readiness != readiness) {
            this.readiness = readiness
            stateRevision = Math.addExact(stateRevision, 1L)
            retireUnreadyOwnerLocked(localMember.deviceId, readiness)
            if (!readiness.coordinatorEligible && currentCoordinatorId == localMember.deviceId) {
                currentCoordinatorId = null
                lastCoordinatorSeenElapsedMillis = null
                leaderLostAtElapsedMillis = nowElapsedRealtimeMillis
                retireAllLeasesLocked()
                reason = "local coordinator readiness was lost"
            }
        }
        return tickLocked(nowElapsedRealtimeMillis)
    }

    /** Returns false for unknown, unauthenticated, stale, or malformed member announcements. */
    @Synchronized
    fun receiveHeartbeat(
        authenticatedDeviceId: String,
        heartbeat: CoordinatorHeartbeat,
        receivedAtElapsedRealtimeMillis: Long,
    ): Boolean {
        requireMonotonicTime(receivedAtElapsedRealtimeMillis)
        if (heartbeat.sessionId != sessionId || heartbeat.membershipVersion != membershipVersion ||
            heartbeat.protocolVersion != PROTOCOL_VERSION || heartbeat.deviceId != authenticatedDeviceId ||
            authenticatedDeviceId == localMember.deviceId || authenticatedDeviceId !in membersById ||
            heartbeat.incarnationId.isBlank() || heartbeat.stateRevision < 0L ||
            heartbeat.heartbeatSequence <= 0L || heartbeat.coordinatorTerm < 0L ||
            (heartbeat.coordinatorTerm == 0L && heartbeat.coordinatorDeviceId != null)
        ) return false

        val existing = peers[authenticatedDeviceId]
        if (existing != null) {
            val oldHeartbeat = existing.heartbeat
            if (heartbeat.incarnationId in existing.retiredIncarnations) return false
            if (heartbeat.incarnationId == oldHeartbeat.incarnationId) {
                if (heartbeat.stateRevision < oldHeartbeat.stateRevision) return false
                // A retransmitted packet is not fresh evidence of liveness. In particular, do
                // not let replaying one captured heartbeat keep an old coordinator alive.
                if (heartbeat.heartbeatSequence <= oldHeartbeat.heartbeatSequence) return false
                if (heartbeat.stateRevision == oldHeartbeat.stateRevision &&
                    (heartbeat.readiness != oldHeartbeat.readiness ||
                        heartbeat.coordinatorTerm != oldHeartbeat.coordinatorTerm ||
                        heartbeat.coordinatorDeviceId != oldHeartbeat.coordinatorDeviceId)
                ) return false
            } else {
                // Revisions and sequences belong to one process incarnation.
                // A restarted member starts them again at one; comparing them
                // with its previous process would permanently exclude it.
                existing.retiredIncarnations += oldHeartbeat.incarnationId
                while (existing.retiredIncarnations.size > MAX_RETIRED_INCARNATIONS) {
                    existing.retiredIncarnations.remove(existing.retiredIncarnations.first())
                }
                // A lease from the old coordinator/owner process cannot be
                // continued by the replacement process, even at the same term.
                if (currentCoordinatorId == authenticatedDeviceId) {
                    currentCoordinatorId = null
                    lastCoordinatorSeenElapsedMillis = null
                    leaderLostAtElapsedMillis = receivedAtElapsedRealtimeMillis
                    retireAllLeasesLocked()
                } else {
                    activeLeases.filterValues { it.grant.ownerDeviceId == authenticatedDeviceId }
                        .keys.toList().forEach(::retireLeaseLocked)
                }
            }
            // Recovery stability belongs to one continuously live process.
            // Updating lastSeen first would let a restarted/reappearing peer
            // borrow the previous process's recovery window.
            if (preferredCandidateId == authenticatedDeviceId &&
                (heartbeat.incarnationId != oldHeartbeat.incarnationId ||
                    receivedAtElapsedRealtimeMillis - existing.lastSeenElapsedMillis >= heartbeatTimeoutMillis ||
                    !heartbeat.readiness.coordinatorEligible)) {
                preferredCandidateId = null
                preferredCandidateSinceElapsedMillis = null
            }
            existing.heartbeat = heartbeat
            existing.lastSeenElapsedMillis = receivedAtElapsedRealtimeMillis
        } else {
            peers[authenticatedDeviceId] = PeerState(heartbeat, receivedAtElapsedRealtimeMillis)
        }

        retireUnreadyOwnerLocked(authenticatedDeviceId, heartbeat.readiness)
        if (currentCoordinatorId == authenticatedDeviceId && !heartbeat.readiness.coordinatorEligible) {
            currentCoordinatorId = null
            lastCoordinatorSeenElapsedMillis = null
            leaderLostAtElapsedMillis = receivedAtElapsedRealtimeMillis
            retireAllLeasesLocked()
            reason = "authenticated coordinator withdrew readiness"
        }

        // A follower can have persisted a newer fence while retaining us as owner
        // (for example after recovering its durable election state). Previously
        // only a sender's own leadership claim advanced our term, leaving phone
        // handoffs permanently at term N while that follower required N+1.
        // This does not elect an owner from a relayed claim: we must already be
        // the eligible local owner, and all identity/membership/replay checks
        // above must have succeeded. Retire old work before any new-term job.
        if (heartbeat.coordinatorDeviceId == localMember.deviceId &&
            currentCoordinatorId == localMember.deviceId && readiness.coordinatorEligible &&
            heartbeat.coordinatorTerm > currentTerm &&
            store.persist(sessionId, membershipVersion,
                CoordinatorPersistentState(heartbeat.coordinatorTerm, localMember.deviceId))
        ) {
            currentTerm = heartbeat.coordinatorTerm
            retireAllLeasesLocked()
            stateRevision = Math.addExact(stateRevision, 1L)
            reason = "authenticated follower reported a newer fence for the current local coordinator"
        }

        // Update the recovery-stability timer before considering an incoming leadership claim.
        tickLocked(receivedAtElapsedRealtimeMillis)
        if (heartbeat.coordinatorDeviceId == authenticatedDeviceId &&
            heartbeat.coordinatorTerm > 0L && heartbeat.readiness.coordinatorEligible
        ) {
            observeLeaderLocked(authenticatedDeviceId, heartbeat.coordinatorTerm,
                receivedAtElapsedRealtimeMillis)
        }
        tickLocked(receivedAtElapsedRealtimeMillis)
        return true
    }

    /** Re-evaluate heartbeat timeouts, candidate stability, role and expired local leases. */
    @Synchronized
    fun tick(nowElapsedRealtimeMillis: Long): CoordinatorSnapshot {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        return tickLocked(nowElapsedRealtimeMillis)
    }

    /** Build one status announcement; the Fabric adapter broadcasts it every three seconds. */
    @Synchronized
    fun nextHeartbeat(nowElapsedRealtimeMillis: Long): CoordinatorHeartbeat {
        val snapshot = tickLocked(nowElapsedRealtimeMillis)
        val emitted = Triple(snapshot.coordinatorTerm, snapshot.coordinatorDeviceId, readiness)
        if (lastEmittedState != null && lastEmittedState != emitted && lastEmittedRevision == stateRevision)
            stateRevision = Math.addExact(stateRevision, 1L)
        lastEmittedState = emitted; lastEmittedRevision = stateRevision
        heartbeatSequence = Math.addExact(heartbeatSequence, 1L)
        stateRevision = maxOf(stateRevision, 1L)
        return CoordinatorHeartbeat(
            sessionId = sessionId,
            membershipVersion = membershipVersion,
            deviceId = localMember.deviceId,
            incarnationId = incarnationId,
            protocolVersion = PROTOCOL_VERSION,
            stateRevision = stateRevision,
            heartbeatSequence = heartbeatSequence,
            readiness = readiness,
            coordinatorTerm = snapshot.coordinatorTerm,
            coordinatorDeviceId = snapshot.coordinatorDeviceId,
        )
    }

    /** Ready-to-send application payload for the authenticated Fabric election channel. */
    @Synchronized
    fun encodeHeartbeat(nowElapsedRealtimeMillis: Long): ByteArray =
        CoordinatorWireCodec.encodeHeartbeat(nextHeartbeat(nowElapsedRealtimeMillis))

    /** Decode and validate a payload against the Fabric-authenticated sender identity. */
    fun receiveHeartbeatPayload(
        authenticatedDeviceId: String,
        payload: ByteArray,
        receivedAtElapsedRealtimeMillis: Long,
    ): Boolean {
        val heartbeat = CoordinatorWireCodec.decodeHeartbeat(payload) ?: return false
        return receiveHeartbeat(authenticatedDeviceId, heartbeat, receivedAtElapsedRealtimeMillis)
    }

    /** Choose a capable executor using the documented mobile-before-Vela order. */
    @Synchronized
    fun chooseExecutor(
        requiredCapabilities: Set<CoordinatorCapability>,
        nowElapsedRealtimeMillis: Long,
        maximumConcurrentPerExecutor: Int = Int.MAX_VALUE,
    ): CoordinatorMember? {
        val snapshot = tickLocked(nowElapsedRealtimeMillis)
        if (!snapshot.mayAssignJobs || snapshot.coordinatorDeviceId != localMember.deviceId) return null
        return liveMembersLocked(nowElapsedRealtimeMillis)
            .filter { (member, memberReadiness) -> memberReadiness.canExecute(requiredCapabilities) &&
                activeLeases.values.count { it.grant.ownerDeviceId == member.deviceId && it.deadlineElapsedMillis > nowElapsedRealtimeMillis } < maximumConcurrentPerExecutor }
            .keys
            .minWithOrNull(memberOrder)
    }

    /** Create the coordinator-side lease before dispatching a task to its selected executor. */
    @Synchronized
    fun grantLease(
        jobId: String,
        captureId: String,
        stage: String,
        inputRevision: Long,
        skillVersion: String,
        requiredCapabilities: Set<CoordinatorCapability>,
        nowElapsedRealtimeMillis: Long,
        maximumConcurrentPerExecutor: Int = Int.MAX_VALUE,
    ): CoordinatorLease? {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        require(maximumConcurrentPerExecutor > 0)
        val executor = chooseExecutor(requiredCapabilities, nowElapsedRealtimeMillis, maximumConcurrentPerExecutor) ?: return null
        val ownerIncarnationId = incarnationFor(executor.deviceId) ?: return null
        if (jobId.isBlank() || captureId.isBlank() || stage.isBlank() || skillVersion.isBlank() ||
            inputRevision < 0L || activeLeases.values.any {
                it.grant.jobId == jobId && it.deadlineElapsedMillis > nowElapsedRealtimeMillis
            }
        ) return null
        val grant = CoordinatorLeaseGrant(
            jobId = jobId,
            captureId = captureId,
            stage = stage,
            inputRevision = inputRevision,
            skillVersion = skillVersion,
            ownerDeviceId = executor.deviceId,
            coordinatorTerm = currentTerm,
            leaseId = UUID.randomUUID().toString(),
            ttlMillis = leaseDurationMillis,
            ownerIncarnationId = ownerIncarnationId,
        )
        val deadline = Math.addExact(nowElapsedRealtimeMillis, leaseDurationMillis)
        activeLeases[grant.leaseId] = ActiveLease(grant, deadline, requiredCapabilities.toSet())
        return CoordinatorLease(
            grant,
            Math.addExact(nowElapsedRealtimeMillis, leaseRenewalMillis),
            deadline,
        )
    }

    /** Accept a grant only when it came from the current authenticated coordinator. */
    @Synchronized
    fun acceptLease(
        grant: CoordinatorLeaseGrant,
        authenticatedCoordinatorId: String,
        receivedAtElapsedRealtimeMillis: Long,
    ): CoordinatorLease? {
        requireMonotonicTime(receivedAtElapsedRealtimeMillis)
        tickLocked(receivedAtElapsedRealtimeMillis)
        if (authenticatedCoordinatorId != currentCoordinatorId ||
            grant.coordinatorTerm != currentTerm || grant.ownerDeviceId != localMember.deviceId ||
            grant.ttlMillis !in 1L..leaseDurationMillis || grant.leaseId.isBlank() ||
            grant.jobId.isBlank() || grant.captureId.isBlank() || grant.renewalSequence <= 0L ||
            grant.ownerIncarnationId != incarnationId || grant.leaseId in retiredLeaseIds
        ) return null
        val previous = activeLeases[grant.leaseId]
        if (previous != null) {
            if (previous.deadlineElapsedMillis <= receivedAtElapsedRealtimeMillis) {
                retireLeaseLocked(grant.leaseId)
                return null
            }
            if (!sameLease(previous.grant, grant) ||
                grant.renewalSequence <= previous.grant.renewalSequence
            ) return null
        }
        val required = when (grant.stage) {
            "transcribe" -> setOf(CoordinatorCapability.TRANSCRIBE)
            "classify" -> setOf(CoordinatorCapability.CLASSIFY)
            "answer" -> setOf(CoordinatorCapability.ANSWER)
            "generate" -> setOf(CoordinatorCapability.GENERATE)
            else -> emptySet()
        }
        if (!readiness.canExecute(required)) return null
        val deadline = Math.addExact(receivedAtElapsedRealtimeMillis, grant.ttlMillis)
        activeLeases[grant.leaseId] = ActiveLease(grant, deadline, required)
        return CoordinatorLease(
            grant,
            Math.addExact(receivedAtElapsedRealtimeMillis, leaseRenewalMillis),
            deadline,
        )
    }

    /** Coordinator-side renewal request, authenticated as the task owner. */
    @Synchronized
    fun renewLease(
        leaseId: String,
        term: Long,
        authenticatedOwnerId: String,
        authenticatedOwnerIncarnationId: String,
        nowElapsedRealtimeMillis: Long,
    ): CoordinatorLease? {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        tickLocked(nowElapsedRealtimeMillis)
        val active = activeLeases[leaseId] ?: return null
        if (role != CoordinatorRole.COORDINATOR || currentCoordinatorId != localMember.deviceId ||
            term != currentTerm || active.grant.coordinatorTerm != term ||
            active.grant.ownerDeviceId != authenticatedOwnerId ||
            active.grant.ownerIncarnationId != authenticatedOwnerIncarnationId ||
            active.deadlineElapsedMillis <= nowElapsedRealtimeMillis
        ) return null
        val nextRenewalSequence = try {
            Math.addExact(active.grant.renewalSequence, 1L)
        } catch (_: ArithmeticException) {
            return null
        }
        val deadline = Math.addExact(nowElapsedRealtimeMillis, leaseDurationMillis)
        active.deadlineElapsedMillis = deadline
        active.grant = active.grant.copy(
            ttlMillis = leaseDurationMillis,
            renewalSequence = nextRenewalSequence,
        )
        return CoordinatorLease(
            active.grant,
            Math.addExact(nowElapsedRealtimeMillis, leaseRenewalMillis),
            deadline,
        )
    }

    @Synchronized
    fun needsLeaseRenewal(
        lease: CoordinatorLease,
        nowElapsedRealtimeMillis: Long,
    ): Boolean {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        tickLocked(nowElapsedRealtimeMillis)
        val active = activeLeases[lease.grant.leaseId] ?: return false
        return active.grant == lease.grant &&
            active.deadlineElapsedMillis > nowElapsedRealtimeMillis &&
            lease.localDeadlineElapsedRealtimeMillis > nowElapsedRealtimeMillis &&
            nowElapsedRealtimeMillis >= lease.renewAtElapsedRealtimeMillis
    }

    /**
     * Run a result write while holding the same fence used to install newer terms.
     * Once a newer term has been observed locally, an older lease cannot enter this block.
     */
    @Synchronized
    fun <T> commitIfCurrent(
        lease: CoordinatorLease,
        nowElapsedRealtimeMillis: Long,
        commit: () -> T,
    ): T? {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        tickLocked(nowElapsedRealtimeMillis)
        val active = activeLeases[lease.grant.leaseId] ?: return null
        if (lease.grant.ownerDeviceId != localMember.deviceId ||
            lease.grant.coordinatorTerm != currentTerm || active.grant != lease.grant ||
            active.deadlineElapsedMillis <= nowElapsedRealtimeMillis ||
            lease.localDeadlineElapsedRealtimeMillis <= nowElapsedRealtimeMillis
        ) return null
        return commit()
    }

    @Synchronized
    fun <T> withAssignedLease(
        leaseId: String,
        term: Long,
        authenticatedOwnerId: String,
        ownerIncarnationId: String,
        nowElapsedRealtimeMillis: Long,
        requiredCapabilities: Set<CoordinatorCapability> = emptySet(),
        block: (CoordinatorLeaseGrant, Long) -> T,
    ): T? {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        tickLocked(nowElapsedRealtimeMillis)
        val active = activeLeases[leaseId] ?: return null
        if (role != CoordinatorRole.COORDINATOR || currentCoordinatorId != localMember.deviceId ||
            currentTerm != term || active.grant.coordinatorTerm != term ||
            active.grant.ownerDeviceId != authenticatedOwnerId || active.grant.ownerIncarnationId != ownerIncarnationId ||
            active.deadlineElapsedMillis <= nowElapsedRealtimeMillis ||
            incarnationFor(authenticatedOwnerId) != ownerIncarnationId
        ) return null
        val member = membersById[authenticatedOwnerId] ?: return null
        if (liveMembersLocked(nowElapsedRealtimeMillis)[member]?.canExecute(requiredCapabilities) != true) return null
        // Repository callers already hold their monitor. Keep the election
        // fence held until the remote result/renewal record is written.
        return block(active.grant, active.deadlineElapsedMillis)
    }

    @Synchronized
    fun matchesLiveExecutor(deviceId: String, incarnation: String, capabilities: Set<CoordinatorCapability>, nowElapsedRealtimeMillis: Long): Boolean {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        tickLocked(nowElapsedRealtimeMillis)
        val member = membersById[deviceId] ?: return false
        return incarnationFor(deviceId) == incarnation && liveMembersLocked(nowElapsedRealtimeMillis)[member]?.canExecute(capabilities) == true
    }

    @Synchronized
    fun releaseLease(leaseId: String, term: Long, ownerDeviceId: String): Boolean {
        val active = activeLeases[leaseId] ?: return false
        if (active.grant.coordinatorTerm != term || active.grant.ownerDeviceId != ownerDeviceId) return false
        activeLeases.remove(leaseId)
        return true
    }

    @Synchronized
    fun snapshot(): CoordinatorSnapshot =
        CoordinatorSnapshot(role, currentCoordinatorId, currentTerm, reason)

    /** Current application-task role, independent of Fabric Session leadership. */
    @Synchronized
    fun role(): CoordinatorRole = role

    /** Current application fencing term; this is not a Fabric session epoch. */
    @Synchronized
    fun term(): Long = currentTerm

    private fun tickLocked(now: Long): CoordinatorSnapshot {
        requireMonotonicTime(now)
        if (startedAtElapsedMillis == null) startedAtElapsedMillis = now
        activeLeases.filterValues { it.deadlineElapsedMillis <= now }
            .keys.toList().forEach(::retireLeaseLocked)

        val coordinatorId = currentCoordinatorId
        if (coordinatorId != null && coordinatorId != localMember.deviceId) {
            val seenAt = lastCoordinatorSeenElapsedMillis
            if (seenAt == null || now - seenAt >= heartbeatTimeoutMillis) {
                currentCoordinatorId = null
                lastCoordinatorSeenElapsedMillis = null
                leaderLostAtElapsedMillis = now
                retireAllLeasesLocked()
                reason = "coordinator heartbeat timed out"
            }
        }

        val best = liveCoordinatorCandidatesLocked(now).keys.minWithOrNull(memberOrder)
        if (best == null) {
            if (currentCoordinatorId != null) leaderLostAtElapsedMillis = now
            role = CoordinatorRole.WAITING
            currentCoordinatorId = null
            lastCoordinatorSeenElapsedMillis = null
            preferredCandidateId = null
            preferredCandidateSinceElapsedMillis = null
            retireAllLeasesLocked()
            reason = "no authenticated member is ready to coordinate"
            return snapshotLocked()
        }

        val leader = currentCoordinatorId
        if (leader == null) {
            val startupElapsed = now - (startedAtElapsedMillis ?: now)
            val canStartAfterTimeout = leaderLostAtElapsedMillis != null || startupElapsed >= heartbeatTimeoutMillis
            if (best.deviceId == localMember.deviceId && canStartAfterTimeout) {
                if (!electLocalLocked(now, "no live coordinator; local device is the best eligible candidate")) {
                    role = CoordinatorRole.WAITING
                }
            } else {
                role = if (best.deviceId == localMember.deviceId) CoordinatorRole.CANDIDATE
                else CoordinatorRole.WAITING
                reason = if (canStartAfterTimeout) "waiting for the preferred authenticated candidate"
                else "collecting heartbeats before the first election"
            }
            return snapshotLocked()
        }

        if (leader == localMember.deviceId && !readiness.coordinatorEligible) {
            currentCoordinatorId = null
            lastCoordinatorSeenElapsedMillis = null
            leaderLostAtElapsedMillis = now
            retireAllLeasesLocked()
            role = CoordinatorRole.WAITING
            reason = "local coordinator readiness was lost"
            return snapshotLocked()
        }

        if (best.deviceId == leader) {
            preferredCandidateId = null
            preferredCandidateSinceElapsedMillis = null
            role = if (leader == localMember.deviceId) CoordinatorRole.COORDINATOR
            else CoordinatorRole.FOLLOWER
            reason = if (role == CoordinatorRole.COORDINATOR) "local device is the preferred coordinator"
            else "authenticated coordinator is the preferred candidate"
            return snapshotLocked()
        }

        if (preferredCandidateId != best.deviceId) {
            preferredCandidateId = best.deviceId
            preferredCandidateSinceElapsedMillis = now
        }
        val stableFor = now - (preferredCandidateSinceElapsedMillis ?: now)
        if (stableFor >= recoveryStableMillis && best.deviceId == localMember.deviceId) {
            if (!electLocalLocked(now, "preferred local candidate remained ready through the recovery window")) {
                role = CoordinatorRole.WAITING
            }
        } else if (leader == localMember.deviceId && stableFor >= recoveryStableMillis) {
            role = CoordinatorRole.HANDING_OFF
            reason = "higher-priority candidate is stable; stop assigning new jobs and finish current leases"
        } else {
            role = if (leader == localMember.deviceId) CoordinatorRole.COORDINATOR
            else CoordinatorRole.FOLLOWER
            reason = "waiting for the preferred candidate's recovery window"
        }
        return snapshotLocked()
    }

    private fun observeLeaderLocked(deviceId: String, term: Long, now: Long) {
        val best = liveCoordinatorCandidatesLocked(now).keys.minWithOrNull(memberOrder) ?: return
        if (best.deviceId != deviceId) return

        val current = currentCoordinatorId
        if (current != null && current != deviceId && coordinatorIsLiveLocked(current, now) &&
            compareMembers(deviceId, current) < 0
        ) {
            if (preferredCandidateId != deviceId) {
                preferredCandidateId = deviceId
                preferredCandidateSinceElapsedMillis = now
            }
            val stableFor = now - (preferredCandidateSinceElapsedMillis ?: now)
            if (stableFor < recoveryStableMillis) return
        }
        val shouldAdopt = term > currentTerm ||
            (term == currentTerm && (current == null || compareMembers(deviceId, current) < 0))
        if (term < currentTerm || (term == currentTerm && current != null && current != deviceId && !shouldAdopt)) {
            return
        }
        if (shouldAdopt) {
            if (!store.persist(sessionId, membershipVersion, CoordinatorPersistentState(term, deviceId))) return
            currentTerm = term
            currentCoordinatorId = deviceId
            retireAllLeasesLocked()
            stateRevision = Math.addExact(stateRevision, 1L)
            preferredCandidateId = null
            preferredCandidateSinceElapsedMillis = null
            reason = "observed a newer authenticated coordinator term"
        }
        if (currentCoordinatorId == deviceId && currentTerm == term) {
            lastCoordinatorSeenElapsedMillis = now
            leaderLostAtElapsedMillis = null
        }
    }

    private fun electLocalLocked(now: Long, why: String): Boolean {
        val nextTerm = try {
            Math.addExact(currentTerm, 1L)
        } catch (_: ArithmeticException) {
            reason = "coordinator term exhausted"
            return false
        }
        if (!store.persist(sessionId, membershipVersion,
                CoordinatorPersistentState(nextTerm, localMember.deviceId))
        ) {
            reason = "coordinator term could not be persisted"
            return false
        }
        currentTerm = nextTerm
        currentCoordinatorId = localMember.deviceId
        lastCoordinatorSeenElapsedMillis = now
        leaderLostAtElapsedMillis = null
        preferredCandidateId = null
        preferredCandidateSinceElapsedMillis = null
        retireAllLeasesLocked()
        stateRevision = Math.addExact(stateRevision, 1L)
        role = CoordinatorRole.COORDINATOR
        reason = why
        return true
    }

    @Synchronized
    fun executableMembers(nowElapsedRealtimeMillis: Long): Map<String, Set<CoordinatorCapability>> {
        requireMonotonicTime(nowElapsedRealtimeMillis)
        return liveMembersLocked(nowElapsedRealtimeMillis).mapKeys { it.key.deviceId }.mapValues { (_, ready) ->
            CoordinatorCapability.entries.filter { ready.canExecute(setOf(it)) }.toSet()
        }
    }

    private fun liveMembersLocked(now: Long): Map<CoordinatorMember, CoordinatorReadiness> = buildMap {
        put(localMember, readiness)
        for ((deviceId, peer) in peers) {
            if (now - peer.lastSeenElapsedMillis >= heartbeatTimeoutMillis) continue
            val member = membersById[deviceId] ?: continue
            put(member, peer.heartbeat.readiness)
        }
    }

    private fun liveCoordinatorCandidatesLocked(now: Long) =
        liveMembersLocked(now).filterValues { it.coordinatorEligible }

    private fun compareMembers(firstId: String, secondId: String): Int {
        val first = membersById[firstId] ?: return 1
        val second = membersById[secondId] ?: return -1
        return memberOrder.compare(first, second)
    }

    private fun coordinatorIsLiveLocked(deviceId: String, now: Long): Boolean {
        if (deviceId == localMember.deviceId) return readiness.coordinatorEligible
        val peer = peers[deviceId] ?: return false
        val lastSeen = lastCoordinatorSeenElapsedMillis ?: return false
        return peer.heartbeat.readiness.coordinatorEligible &&
            now - peer.lastSeenElapsedMillis < heartbeatTimeoutMillis &&
            now - lastSeen < heartbeatTimeoutMillis
    }

    private fun incarnationFor(deviceId: String): String? =
        if (deviceId == localMember.deviceId) incarnationId
        else peers[deviceId]?.heartbeat?.incarnationId

    private fun sameLease(first: CoordinatorLeaseGrant, second: CoordinatorLeaseGrant): Boolean =
        first.jobId == second.jobId && first.captureId == second.captureId &&
            first.stage == second.stage && first.inputRevision == second.inputRevision &&
            first.skillVersion == second.skillVersion && first.ownerDeviceId == second.ownerDeviceId &&
            first.ownerIncarnationId == second.ownerIncarnationId &&
            first.coordinatorTerm == second.coordinatorTerm && first.leaseId == second.leaseId

    private fun retireUnreadyOwnerLocked(owner: String, readiness: CoordinatorReadiness) {
        activeLeases.filterValues { it.grant.ownerDeviceId == owner && !readiness.canExecute(it.requiredCapabilities) }
            .keys.toList().forEach(::retireLeaseLocked)
    }

    private fun retireLeaseLocked(leaseId: String) {
        activeLeases.remove(leaseId)
        retiredLeaseIds += leaseId
        while (retiredLeaseIds.size > MAX_RETIRED_LEASE_IDS) {
            retiredLeaseIds.remove(retiredLeaseIds.first())
        }
    }

    private fun retireAllLeasesLocked() {
        activeLeases.keys.toList().forEach(::retireLeaseLocked)
    }

    private fun snapshotLocked() = CoordinatorSnapshot(role, currentCoordinatorId, currentTerm, reason)

    private fun requireMonotonicTime(value: Long) {
        require(value >= 0L) { "elapsed realtime must be non-negative" }
        val previous = lastObservedElapsedMillis
        require(previous == null || value >= previous) {
            "elapsed realtime must not move backwards"
        }
        lastObservedElapsedMillis = value
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 3_000L
        const val DEFAULT_MISSED_HEARTBEATS = 3
        const val DEFAULT_LEASE_DURATION_MILLIS = 20_000L
        const val DEFAULT_LEASE_RENEWAL_MILLIS = 5_000L
        const val DEFAULT_RECOVERY_STABLE_MILLIS = 15_000L
        private const val MAX_RETIRED_INCARNATIONS = 8
        private const val MAX_RETIRED_LEASE_IDS = 512

        /** Phone and tablet share the documented mobile tier; Vela follows them. */
        private val memberOrder = compareBy<CoordinatorMember> {
            when (it.deviceClass) {
                CoordinatorDeviceClass.PHONE, CoordinatorDeviceClass.TABLET -> 0
                CoordinatorDeviceClass.VELA -> 1
                CoordinatorDeviceClass.OTHER -> 2
            }
        }.thenBy { it.deviceId }
    }
}
