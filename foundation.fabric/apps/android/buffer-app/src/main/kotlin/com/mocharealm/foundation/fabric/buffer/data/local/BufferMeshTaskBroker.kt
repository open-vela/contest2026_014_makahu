package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.mocharealm.foundation.fabric.buffer.data.coordinator.*
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Coordinator-side task inbox and immutable result receipts. Access under the
 * repository monitor, configuration transaction, then election fence. Persisted
 * requests/results never restore a lease or execute imported source history. */
internal class BufferMeshTaskBroker {
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_inbox (
                job_id TEXT PRIMARY KEY, source_peer TEXT NOT NULL, contract TEXT NOT NULL,
                scope_revision TEXT NOT NULL, offer_event_id TEXT NOT NULL, result_event_id TEXT, cancel_event_id TEXT, source_revision TEXT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS mesh_task_pending ON mesh_task_inbox(scope_revision,result_event_id,cancel_event_id,source_peer)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_cancellations (
                job_id TEXT PRIMARY KEY, source_peer TEXT NOT NULL, contract TEXT NOT NULL, event_id TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_scoped_cancellations (
                source_peer TEXT NOT NULL,job_id TEXT NOT NULL,source_revision TEXT NOT NULL,target_revision TEXT NOT NULL,
                contract_sha256 TEXT NOT NULL,event_id TEXT NOT NULL,
                PRIMARY KEY(source_peer,job_id,source_revision,target_revision))""")
            db.execSQL("CREATE INDEX IF NOT EXISTS mesh_scoped_cancellation_quota ON mesh_task_scoped_cancellations(source_peer,target_revision,source_revision)")
        }
    }
    private data class Saved(val contract: JSONObject, val source: String, val scope: String, val offer: String, val result: String?, val cancel: String?, val sourceRevision: String?)
    private val leases = linkedMapOf<String, CoordinatorLease>()

    private fun scopedCancellation(db: SQLiteDatabase, peer: String, job: String, source: String, target: String, digest: String): String? =
        db.rawQuery("SELECT contract_sha256,event_id FROM mesh_task_scoped_cancellations WHERE source_peer=? AND job_id=? AND source_revision=? AND target_revision=?",
            arrayOf(peer, job, source, target)).use {
            if (!it.moveToFirst()) return@use null
            check(it.getString(0) == digest)
            val event = BufferEventJournal.localRecord(db, it.getString(1)).event; val payload = event.getJSONObject("payload")
            val request = payload.getJSONObject("request"); BufferMeshTaskCancellation.validate(request, peer)
            check(event.get("type") == "MeshTaskScopedCancellationReceived" && payload.get("source_peer") == peer &&
                request.get("job_id") == job && request.get("source_revision") == source && request.get("target_revision") == target && request.get("contract_sha256") == digest)
            it.getString(1)
        }

    /** Authenticated source may stop only its own exact revision pair. This
     * never returns a result or accepts a grant, and works after approval loss.
     * Unknown attempts get tombstones only under current application approval. */
    fun cancel(db: SQLiteDatabase, local: String, peer: String, request: JSONObject, admitUnknown: () -> Boolean,
               election: BufferCoordinatorElection?, emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction()); BufferMeshTaskCancellation.validate(request, peer)
        val job = request.getString("job_id"); val source = request.getString("source_revision")
        val target = request.getString("target_revision"); val digest = request.getString("contract_sha256")
        scopedCancellation(db, peer, job, source, target, digest)?.let { return BufferMeshTaskCancellation.reply(local, request) }
        val saved = load(db, job)
        val matching = saved?.takeIf { it.source == peer && it.sourceRevision == source && it.scope == target }
        if (matching != null) require(BufferMeshTaskContract.digest(matching.contract) == digest)
        else {
            if (!admitUnknown()) return BufferMeshTaskCancellation.reply(local, request)
            val count = db.rawQuery("SELECT COUNT(*) FROM mesh_task_scoped_cancellations c LEFT JOIN mesh_task_inbox i ON " +
                "i.job_id=c.job_id AND i.source_peer=c.source_peer AND i.source_revision=c.source_revision AND i.scope_revision=c.target_revision " +
                "WHERE c.source_peer=? AND c.target_revision=? AND i.job_id IS NULL", arrayOf(peer, target))
                .use { check(it.moveToFirst()); it.getInt(0) }
            if (count >= 64) return BufferMeshTaskCancellation.reply(local, request, "busy")
        }
        val received = emit("MeshTaskScopedCancellationReceived", JSONObject().put("source_peer", peer).put("request", request))
        db.insertOrThrow("mesh_task_scoped_cancellations", null, ContentValues().apply {
            put("source_peer", peer); put("job_id", job); put("source_revision", source); put("target_revision", target)
            put("contract_sha256", digest); put("event_id", received)
        })
        if (matching != null && matching.result == null && matching.cancel == null) {
            val event = emit("MeshTaskCancelled", JSONObject().put("job_id", job).put("offer_event_id", matching.offer)
                .put("source_peer", peer).put("cancellation_event_id", received))
            check(db.update("mesh_task_inbox", ContentValues().apply { put("cancel_event_id", event) },
                "job_id=? AND offer_event_id=? AND result_event_id IS NULL AND cancel_event_id IS NULL", arrayOf(job, matching.offer)) == 1)
            if (election != null) release(election, job) else leases.remove(job)
        }
        return BufferMeshTaskCancellation.reply(local, request)
    }

    private fun cancellation(db: SQLiteDatabase, contract: JSONObject, peer: String): JSONObject? = db.rawQuery(
        "SELECT source_peer,contract,event_id FROM mesh_task_cancellations WHERE job_id=?", arrayOf(contract.getString("job_id"))).use {
        if (!it.moveToFirst()) return@use null
        check(it.getString(0) == peer && it.getString(1) == BufferCrdt.canonical(contract))
        val event = BufferEventJournal.localRecord(db, it.getString(2)).event
        val p = event.getJSONObject("payload")
        check(event.getString("type") == "MeshTaskCancellationRequested" && p.getString("source_peer") == peer &&
            BufferCrdt.canonical(p.getJSONObject("contract")) == BufferCrdt.canonical(contract))
        JSONObject().put("status", "cancelled").put("receipt_event_id", it.getString(2))
    }

    private fun load(db: SQLiteDatabase, id: String): Saved? = db.rawQuery(
        "SELECT contract,source_peer,scope_revision,offer_event_id,result_event_id,cancel_event_id,source_revision FROM mesh_task_inbox WHERE job_id=?", arrayOf(id)).use {
        if (!it.moveToFirst()) return@use null
        val saved = Saved(BufferSourceEvent.parseObject(it.getString(0), 8192), it.getString(1), it.getString(2), it.getString(3),
            if (it.isNull(4)) null else it.getString(4), if (it.isNull(5)) null else it.getString(5), if (it.isNull(6)) null else it.getString(6))
        BufferMeshTaskContract.validate(saved.contract)
        check(saved.contract.getString("job_id") == id && saved.contract.getString("source_device") == saved.source)
        val event = BufferEventJournal.localRecord(db, saved.offer).event
        val p = event.getJSONObject("payload")
        check(event.getString("type") == "MeshTaskOffered" && p.getString("source_peer") == saved.source &&
            p.getString("scope_revision") == saved.scope && BufferCrdt.canonical(p.getJSONObject("contract")) == BufferCrdt.canonical(saved.contract))
        check(if (saved.sourceRevision == null) !p.has("source_revision") else p.get("source_revision") == saved.sourceRevision)
        check(saved.result == null || saved.cancel == null)
        if (saved.cancel != null) {
            val cancelled = BufferEventJournal.localRecord(db, saved.cancel).event
            val cp = cancelled.getJSONObject("payload")
            check(cancelled.getString("type") == "MeshTaskCancelled" && cp.getString("job_id") == id &&
                cp.getString("offer_event_id") == saved.offer && cp.getString("source_peer") == saved.source)
        }
        saved
    }
    private fun result(db: SQLiteDatabase, saved: Saved): JSONObject? {
        val id = saved.result ?: return null
        val event = BufferEventJournal.localRecord(db, id).event
        val p = event.getJSONObject("payload")
        check(event.getString("type") == "MeshTaskResultReceived" && p.getString("job_id") == saved.contract.getString("job_id") &&
            p.getString("source_peer") == saved.source && p.getString("offer_event_id") == saved.offer &&
            p.getString("scope_revision") == saved.scope && p.getString("contract_sha256") == BufferMeshTaskContract.digest(saved.contract))
        BufferMeshExecution.validateResult(saved.contract, p.getJSONObject("result"))
        check(p.getString("result_sha256") == BufferMeshTaskContract.digest(p.getJSONObject("result")))
        val grant = requireNotNull(CoordinatorWireCodec.decodeLeaseGrant(p.getJSONObject("grant").toString().toByteArray(Charsets.UTF_8)))
        check(matches(grant, saved.contract))
        return JSONObject().put("status", "completed").put("job_id", saved.contract.getString("job_id"))
            .put("receipt_event_id", id).put("result_sha256", p.getString("result_sha256"))
            .put("result", p.getJSONObject("result")).put("grant", p.getJSONObject("grant"))
    }
    private fun matches(grant: CoordinatorLeaseGrant, contract: JSONObject): Boolean =
        grant.jobId == contract.getString("job_id") && grant.captureId == BufferMeshTaskContract.target(contract) &&
            grant.stage == contract.getString("stage") && grant.inputRevision == contract.getLong("input_revision") &&
            grant.skillVersion == contract.getString("skill_version")

    private fun current(election: BufferCoordinatorElection, id: String): CoordinatorLeaseGrant? {
        val lease = leases[id] ?: return null
        val grant = lease.grant
        val now = SystemClock.elapsedRealtime()
        return election.withAssignedLease(grant.leaseId, grant.coordinatorTerm, grant.ownerDeviceId, grant.ownerIncarnationId, now,
            setOf(BufferMeshTaskContract.capability(grant.stage))) { actual, deadline ->
            check(actual == grant)
            actual.copy(ttlMillis = deadline - now)
        }.also { if (it == null) leases.remove(id) }
    }
    private fun grantJson(grant: CoordinatorLeaseGrant): JSONObject = BufferSourceEvent.parseObject(
        CoordinatorWireCodec.encodeLeaseGrant(grant).toString(Charsets.UTF_8), 16 * 1024)
    private fun assigned(saved: Saved, grant: CoordinatorLeaseGrant): JSONObject = JSONObject()
        .put("status", "leased").put("contract", saved.contract).put("grant", grantJson(grant))
    private fun keys(value: JSONObject, vararg names: String) { require(value.keys().asSequence().toSet() == names.toSet()) }
    private fun jobId(value: JSONObject): String {
        val id = value.get("job_id")
        require(id is String && id.matches(Regex("[0-9a-f]{64}")))
        return id
    }
    private fun release(election: BufferCoordinatorElection, id: String) {
        leases.remove(id)?.grant?.let { election.releaseLease(it.leaseId, it.coordinatorTerm, it.ownerDeviceId) }
    }

    fun handle(db: SQLiteDatabase, plan: MeshGroupPlan, local: String, peer: String, sourceRevision: String, request: JSONObject,
               election: BufferCoordinatorElection, emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction() && (peer == local || peer in plan.peers))
        val snapshot = election.tick(SystemClock.elapsedRealtime())
        val coordinating = snapshot.role == CoordinatorRole.COORDINATOR && snapshot.coordinatorDeviceId == local
        val scope = plan.binding.getString("revision")
        when (request.getString("action")) {
            "offer" -> {
                keys(request, "action", "contract")
                val contract = request.getJSONObject("contract"); BufferMeshTaskContract.validate(contract)
                require(contract.getString("skill_version") == BufferMeshTaskContract.skill(BufferMeshConfiguration.offers(db), contract.getString("stage"))) { "任务协议版本与成员配置不匹配" }
                require(contract.getString("source_device") == peer) { "任务来源必须是认证提交者" }
                val id = contract.getString("job_id")
                scopedCancellation(db, peer, id, sourceRevision, scope, BufferMeshTaskContract.digest(contract))?.let {
                    return JSONObject().put("status", "cancelled").put("receipt_event_id", it)
                }
                cancellation(db, contract, peer)?.let { return it }
                var saved = load(db, id)
                val previous = saved
                if (previous != null) {
                    require(previous.source == peer && BufferCrdt.canonical(previous.contract) == BufferCrdt.canonical(contract))
                    result(db, previous)?.let { return it }
                    if (previous.cancel != null && (previous.sourceRevision == null || (previous.scope == scope && previous.sourceRevision == sourceRevision)))
                        return JSONObject().put("status", "cancelled").put("receipt_event_id", previous.cancel)
                    if (coordinating && previous.scope == scope && previous.sourceRevision == sourceRevision) current(election, id)?.let { return assigned(previous, it) }
                }
                if (!coordinating) return JSONObject().put("status", "not_coordinator")
                // Completed records stay available for idempotent replies. Only
                // pending work in the current configuration consumes admission.
                if (saved == null || saved.scope != scope || saved.sourceRevision != sourceRevision) {
                    release(election, id)
                    val counts = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN source_peer=? THEN 1 ELSE 0 END),0) FROM mesh_task_inbox WHERE scope_revision=? AND job_id<>? AND result_event_id IS NULL AND cancel_event_id IS NULL",
                        arrayOf(peer, scope, id)).use { check(it.moveToFirst()); it.getInt(0) to it.getInt(1) }
                    if (counts.first >= 64 || counts.second >= 4) return JSONObject().put("status", "busy")
                    val event = emit("MeshTaskOffered", JSONObject().put("contract", contract).put("source_peer", peer).put("scope_revision", scope).put("source_revision", sourceRevision))
                    db.insertWithOnConflict("mesh_task_inbox", null, ContentValues().apply {
                        put("job_id", id); put("source_peer", peer); put("contract", BufferCrdt.canonical(contract))
                        put("scope_revision", scope); put("offer_event_id", event); putNull("result_event_id"); putNull("cancel_event_id")
                        put("source_revision", sourceRevision)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                    saved = requireNotNull(load(db, id))
                }
                val stage = contract.getString("stage")
                val lease = election.grantLease(id, BufferMeshTaskContract.target(contract), stage, contract.getLong("input_revision"),
                    contract.getString("skill_version"), setOf(BufferMeshTaskContract.capability(stage)), SystemClock.elapsedRealtime(), maximumConcurrentPerExecutor = 1)
                    ?: return JSONObject().put("status", "waiting")
                try {
                    emit("MeshTaskLeased", JSONObject().put("job_id", id).put("source_peer", peer).put("scope_revision", scope).put("grant", grantJson(lease.grant)))
                    leases[id] = lease
                    return current(election, id)?.let { assigned(requireNotNull(saved), it) } ?: JSONObject().put("status", "waiting")
                } catch (error: Exception) {
                    election.releaseLease(lease.grant.leaseId, lease.grant.coordinatorTerm, lease.grant.ownerDeviceId)
                    leases.remove(id)
                    throw error
                }
            }
            "assigned" -> {
                keys(request, "action")
                if (!coordinating) return JSONObject().put("status", "not_coordinator")
                val items = JSONArray()
                for (id in leases.keys.toList()) {
                    val grant = current(election, id) ?: continue
                    if (grant.ownerDeviceId != peer) continue
                    val saved = load(db, id)
                    if (saved == null) { release(election, id); continue }
                    if (saved.scope == scope && saved.result == null && saved.cancel == null &&
                        (saved.source == local || saved.source in plan.peers)) items.put(assigned(saved, grant))
                    if (items.length() >= 4) break
                }
                return JSONObject().put("status", "assigned").put("items", items)
            }
            "status", "cancel" -> {
                val cancelling = request.getString("action") == "cancel"
                if (cancelling) keys(request, "action", "job_id", "contract") else keys(request, "action", "job_id")
                val id = jobId(request)
                val contract = if (cancelling) request.getJSONObject("contract").also {
                    BufferMeshTaskContract.validate(it)
                    require(it.getString("job_id") == id && it.getString("source_device") == peer)
                } else null
                if (contract != null) cancellation(db, contract, peer)?.let { return it }
                val saved = load(db, id)
                if (saved == null) {
                    if (contract == null) return JSONObject().put("status", "unknown")
                    // Cancellation can overtake the original offer on separate
                    // RPC streams. Keep a source-authorized tombstone so that
                    // the delayed offer cannot resurrect the cancelled task.
                    val event = emit("MeshTaskCancellationRequested", JSONObject().put("contract", contract).put("source_peer", peer))
                    db.insertOrThrow("mesh_task_cancellations", null, ContentValues().apply {
                        put("job_id", id); put("source_peer", peer); put("contract", BufferCrdt.canonical(contract)); put("event_id", event)
                    })
                    return JSONObject().put("status", "cancelled").put("receipt_event_id", event)
                }
                require(saved.source == peer)
                if (contract != null) require(BufferCrdt.canonical(contract) == BufferCrdt.canonical(saved.contract))
                result(db, saved)?.let { return it }
                if (saved.cancel != null) return JSONObject().put("status", "cancelled").put("receipt_event_id", saved.cancel)
                if (cancelling) {
                    val event = emit("MeshTaskCancelled", JSONObject().put("job_id", id).put("offer_event_id", saved.offer).put("source_peer", peer))
                    check(db.update("mesh_task_inbox", ContentValues().apply { put("cancel_event_id", event) },
                        "job_id=? AND result_event_id IS NULL AND cancel_event_id IS NULL", arrayOf(id)) == 1)
                    release(election, id)
                    return JSONObject().put("status", "cancelled").put("receipt_event_id", event)
                }
                if (!coordinating) return JSONObject().put("status", "not_coordinator")
                if (saved.scope != scope) return JSONObject().put("status", "configuration_changed")
                return current(election, id)?.let { assigned(saved, it) } ?: JSONObject().put("status", "waiting")
            }
            "renew", "result" -> {
                val submitting = request.getString("action") == "result"
                keys(request, *(listOf("action", "job_id", "lease_id", "term", "owner_incarnation_id") +
                    if (submitting) listOf("result") else listOf("renewal_sequence")).toTypedArray())
                val id = jobId(request)
                val saved = load(db, id) ?: return JSONObject().put("status", "unknown")
                val term = BufferMeshTaskContract.integer(request, "term", 1L..BufferSourceEvent.MAX_COUNTER)
                val leaseId = request.getString("lease_id"); val incarnation = request.getString("owner_incarnation_id")
                require(request.get("lease_id") is String && request.get("owner_incarnation_id") is String &&
                    UUID.fromString(leaseId).toString() == leaseId && UUID.fromString(incarnation).toString() == incarnation)
                if (submitting) {
                    val value = request.getJSONObject("result"); BufferMeshExecution.validateResult(saved.contract, value)
                    result(db, saved)?.let { receipt ->
                        val grant = receipt.getJSONObject("grant")
                        require(grant.getString("owner_device_id") == peer && grant.getString("lease_id") == leaseId &&
                            grant.getLong("coordinator_term") == term && grant.getString("owner_incarnation_id") == incarnation &&
                            receipt.getString("result_sha256") == BufferMeshTaskContract.digest(value)) { "结果与已提交回执冲突" }
                        return receipt
                    }
                }
                // A completed receipt remains readable after leadership loss;
                // accepting a new result still requires the live local fence.
                if (!coordinating) return JSONObject().put("status", "not_coordinator")
                if (saved.cancel != null) return JSONObject().put("status", "cancelled")
                if (saved.scope != scope || (saved.source != local && saved.source !in plan.peers)) return JSONObject().put("status", "configuration_changed")
                val assignedGrant = current(election, id) ?: return JSONObject().put("status", "expired")
                require(assignedGrant.leaseId == leaseId && assignedGrant.coordinatorTerm == term && assignedGrant.ownerDeviceId == peer && assignedGrant.ownerIncarnationId == incarnation)
                if (!submitting) {
                    val sequence = BufferMeshTaskContract.integer(request, "renewal_sequence", 1L..BufferSourceEvent.MAX_COUNTER)
                    if (assignedGrant.renewalSequence != sequence) return JSONObject().put("status", "stale_renewal")
                    if (sequence == BufferSourceEvent.MAX_COUNTER) return JSONObject().put("status", "lease_exhausted")
                    val renewed = election.renewLease(leaseId, term, peer, incarnation, SystemClock.elapsedRealtime())
                        ?: return JSONObject().put("status", "expired")
                    leases[id] = renewed
                    try {
                        emit("MeshTaskLeaseRenewed", JSONObject().put("job_id", id).put("scope_revision", scope).put("grant", grantJson(renewed.grant)))
                    } catch (error: Exception) {
                        election.releaseLease(leaseId, term, peer); leases.remove(id)
                        throw error
                    }
                    return current(election, id)?.let { assigned(saved, it) } ?: JSONObject().put("status", "expired")
                }
                val value = request.getJSONObject("result")
                val receipt = election.withAssignedLease(leaseId, term, peer, incarnation, SystemClock.elapsedRealtime(),
                    setOf(BufferMeshTaskContract.capability(saved.contract.getString("stage")))) { grant, _ ->
                    check(matches(grant, saved.contract))
                    val event = emit("MeshTaskResultReceived", JSONObject().put("job_id", id).put("source_peer", saved.source)
                        .put("scope_revision", scope).put("offer_event_id", saved.offer).put("contract_sha256", BufferMeshTaskContract.digest(saved.contract))
                        .put("grant", grantJson(grant)).put("result", value).put("result_sha256", BufferMeshTaskContract.digest(value)))
                    check(db.update("mesh_task_inbox", ContentValues().apply { put("result_event_id", event) },
                        "job_id=? AND result_event_id IS NULL", arrayOf(id)) == 1)
                    result(db, requireNotNull(load(db, id)))
                } ?: return JSONObject().put("status", "expired")
                election.releaseLease(leaseId, term, peer); leases.remove(id)
                return receipt
            }
            else -> error("unsupported mesh task action")
        }
    }
}
