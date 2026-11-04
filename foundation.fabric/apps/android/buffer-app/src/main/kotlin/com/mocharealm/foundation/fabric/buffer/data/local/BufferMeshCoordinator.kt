package com.mocharealm.foundation.fabric.buffer.data.local

import android.os.SystemClock
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.*
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.UUID

/** Access only under the repository monitor and its current-configuration
 * transaction. This domain is separate from the existing BLE task gate.
 * A new or reset domain starts unready; only the live execution worker may
 * publish verified service/OS/storage readiness. */
internal class BufferMeshCoordinator(private val store: CoordinatorStateStore) {
    private var revision = ""
    private var election: BufferCoordinatorElection? = null
    private var broker = BufferMeshTaskBroker()

    fun reset() { revision = ""; election = null; broker = BufferMeshTaskBroker() }

    private fun domain(db: SQLiteDatabase, local: String, plan: MeshGroupPlan): BufferCoordinatorElection {
        check(db.inTransaction() && BufferMeshActivations.current(db, plan))
        val current = plan.binding.getString("revision")
        if (revision != current || election == null) {
            broker = BufferMeshTaskBroker()
            val members = BufferMeshConfiguration.offers(db).map {
                CoordinatorMember(it.getString("device_id"), CoordinatorDeviceClass.valueOf(it.getString("device_class")))
            }
            val digest = plan.binding.getString("digest")
            election = BufferCoordinatorElection("buffer-mesh-v1-$digest", digest,
                members.single { it.deviceId == local }, members,
                CoordinatorReadiness(true, false, false, true, false, emptySet()), store)
            revision = current
        }
        return requireNotNull(election)
    }

    fun heartbeat(db: SQLiteDatabase, local: String, plan: MeshGroupPlan): JSONObject =
        BufferSourceEvent.parseObject(domain(db, local, plan).encodeHeartbeat(SystemClock.elapsedRealtime()).toString(Charsets.UTF_8), 16 * 1024)

    fun snapshot(db: SQLiteDatabase, local: String, plan: MeshGroupPlan): CoordinatorSnapshot =
        domain(db, local, plan).tick(SystemClock.elapsedRealtime())

    fun readiness(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, value: CoordinatorReadiness) {
        domain(db, local, plan).updateLocalReadiness(value, SystemClock.elapsedRealtime())
    }

    fun executableMembers(db: SQLiteDatabase, local: String, plan: MeshGroupPlan): Map<String, Set<CoordinatorCapability>> =
        domain(db, local, plan).executableMembers(SystemClock.elapsedRealtime())

    fun acceptExecution(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, coordinator: String,
                        contract: JSONObject, grant: CoordinatorLeaseGrant, started: Long): MeshExecutionLease {
        BufferMeshTaskContract.validate(contract)
        require(grant.jobId == contract.getString("job_id") && grant.captureId == BufferMeshTaskContract.target(contract) &&
            grant.inputRevision == contract.getLong("input_revision") && grant.stage == contract.getString("stage") &&
            grant.skillVersion == contract.getString("skill_version") && grant.ownerDeviceId == local)
        require(contract.getString("source_device") == local || contract.getString("source_device") in plan.peers)
        val current = domain(db, local, plan); val now = SystemClock.elapsedRealtime()
        require(started >= 0 && now >= started && now - started < 11_000 && started + grant.ttlMillis > now)
        require(executorCurrent(db, local, plan, grant))
        val accepted = if (coordinator == local) current.withAssignedLease(grant.leaseId, grant.coordinatorTerm, local,
            grant.ownerIncarnationId, now, setOf(BufferMeshTaskContract.capability(grant.stage))) { actual, deadline ->
            require(actual.copy(ttlMillis = grant.ttlMillis) == grant)
            CoordinatorLease(actual, now + 5000, minOf(deadline, started + grant.ttlMillis))
        } else current.acceptLease(grant.copy(ttlMillis = grant.ttlMillis - (now - started)), coordinator, now)
        return MeshExecutionLease(plan.binding.getString("revision"), current, requireNotNull(accepted))
    }

    fun <T : Any> execution(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, handle: MeshExecutionLease, block: () -> T): T? {
        val current = domain(db, local, plan)
        if (handle.scope != plan.binding.getString("revision") || current !== handle.election ||
            !executorCurrent(db, local, plan, handle.lease.grant)) return null
        return current.commitIfCurrent(handle.lease, SystemClock.elapsedRealtime(), block)
    }

    fun executorCurrent(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, grant: CoordinatorLeaseGrant): Boolean =
        domain(db, local, plan).matchesLiveExecutor(grant.ownerDeviceId, grant.ownerIncarnationId,
            setOf(BufferMeshTaskContract.capability(grant.stage)), SystemClock.elapsedRealtime())

    fun task(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, peer: String, sourceRevision: String, request: JSONObject,
             emit: (String, JSONObject) -> String): JSONObject {
        val current = domain(db, local, plan)
        return broker.handle(db, plan, local, peer, sourceRevision, request, current, emit)
    }

    fun cancel(db: SQLiteDatabase, local: String, peer: String, request: JSONObject, admitUnknown: () -> Boolean,
               emit: (String, JSONObject) -> String): JSONObject = broker.cancel(db, local, peer, request, admitUnknown, election, emit)

    fun receive(db: SQLiteDatabase, local: String, plan: MeshGroupPlan, peer: String, value: JSONObject) {
        check(peer in plan.peers)
        val raw = BufferCrdt.canonical(value).toByteArray(Charsets.UTF_8)
        val heartbeat = requireNotNull(CoordinatorWireCodec.decodeHeartbeat(raw))
        require(heartbeat.stateRevision in 1L..BufferSourceEvent.MAX_COUNTER &&
            heartbeat.heartbeatSequence in 1L..BufferSourceEvent.MAX_COUNTER &&
            heartbeat.coordinatorTerm in 0L..BufferSourceEvent.MAX_COUNTER)
        require(UUID.fromString(heartbeat.incarnationId).toString() == heartbeat.incarnationId)
        // The existing BLE codec is permissive for compatibility. The new mesh
        // boundary accepts only its exact schema and normalized typed fields.
        val normalized = BufferSourceEvent.parseObject(CoordinatorWireCodec.encodeHeartbeat(heartbeat).toString(Charsets.UTF_8), 16 * 1024)
        require(BufferCrdt.canonical(normalized) == BufferCrdt.canonical(value))
        require(heartbeat.coordinatorDeviceId == null || heartbeat.coordinatorDeviceId == local || heartbeat.coordinatorDeviceId in plan.peers)
        val offer = BufferMeshConfiguration.offers(db).single { it.getString("device_id") == peer }
        val versions = offer.getJSONObject("capabilities")
        val declared = versions.keys().asSequence().toSet()
        require(heartbeat.readiness.capabilities.all { it.name in declared })
        // TRANSCRIBE v2 explicitly retains v1 successful-text compatibility.
        // CLASSIFY v2 is the first remote classification task protocol.
        val compatible = heartbeat.copy(readiness = heartbeat.readiness.copy(
            capabilities = heartbeat.readiness.capabilities.filter {
                when (it) {
                    CoordinatorCapability.TRANSCRIBE -> versions.getLong(it.name) in 1L..2L
                    CoordinatorCapability.CLASSIFY -> versions.getLong(it.name) in 2L..3L
                    CoordinatorCapability.ANSWER -> versions.getLong(it.name) == 2L
                    CoordinatorCapability.GENERATE -> versions.getLong(it.name) == 2L
                    else -> versions.getLong(it.name) == 1L
                }
            }.toSet()))
        check(domain(db, local, plan).receiveHeartbeat(peer, compatible, SystemClock.elapsedRealtime())) { "过期或不匹配的协调心跳" }
    }
}
