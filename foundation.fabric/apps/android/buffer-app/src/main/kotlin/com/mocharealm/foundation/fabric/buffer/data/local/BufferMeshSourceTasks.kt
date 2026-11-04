package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorLeaseGrant
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorSnapshot
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorWireCodec
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.UUID

/** Durable source intent; ephemeral disclosure permits. Replaying source
 * history or reopening the app never restores an audio disclosure permit. */
internal class BufferMeshSourceTasks {
    companion object {
        fun grant(value: JSONObject): CoordinatorLeaseGrant {
            val grant = requireNotNull(CoordinatorWireCodec.decodeLeaseGrant(value.toString().toByteArray(Charsets.UTF_8)))
            val normalized = BufferSourceEvent.parseObject(CoordinatorWireCodec.encodeLeaseGrant(grant).toString(Charsets.UTF_8), 16 * 1024)
            require(BufferCrdt.canonical(normalized) == BufferCrdt.canonical(value))
            require(grant.ttlMillis in 1L..20_000 && grant.coordinatorTerm in 1L..BufferSourceEvent.MAX_COUNTER &&
                grant.renewalSequence in 1L..BufferSourceEvent.MAX_COUNTER && grant.inputRevision in 0L..BufferSourceEvent.MAX_COUNTER)
            require(UUID.fromString(grant.leaseId).toString() == grant.leaseId &&
                UUID.fromString(grant.ownerIncarnationId).toString() == grant.ownerIncarnationId && grant.ownerDeviceId.matches(Regex("[0-9a-f]{64}")))
            return grant
        }
        fun create(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_sources (
                job_id TEXT PRIMARY KEY, contract TEXT NOT NULL, scope_revision TEXT NOT NULL,
                capture_state TEXT NOT NULL, request_event_id TEXT NOT NULL, terminal_event_id TEXT)""")
            db.execSQL("CREATE INDEX IF NOT EXISTS mesh_task_source_pending ON mesh_task_sources(scope_revision,terminal_event_id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS mesh_task_source_failures (capture_id TEXT PRIMARY KEY,job_id TEXT NOT NULL,event_id TEXT NOT NULL)")
        }
    }
    data class Intent(val contract: JSONObject, val scope: String, val state: String, val request: String, val terminal: String?)
    private data class Permit(val coordinator: String, val grant: CoordinatorLeaseGrant, val deadline: Long, val scope: String)
    private val permits = linkedMapOf<String, Permit>()
    fun reset() { permits.clear() }
    fun retirePeer(peer: String) { permits.entries.removeAll { it.value.coordinator == peer || it.value.grant.ownerDeviceId == peer } }
    fun retireCapture(capture: String) { permits.entries.removeAll { it.value.grant.captureId == capture } }

    fun load(db: SQLiteDatabase, job: String): Intent? {
        check(db.inTransaction())
        return db.rawQuery("SELECT contract,scope_revision,capture_state,request_event_id,terminal_event_id FROM mesh_task_sources WHERE job_id=?", arrayOf(job)).use {
            if (!it.moveToFirst()) return@use null
            val intent = Intent(BufferSourceEvent.parseObject(it.getString(0), 8192), it.getString(1), it.getString(2), it.getString(3),
                if (it.isNull(4)) null else it.getString(4))
            BufferMeshTaskContract.validate(intent.contract); check(intent.contract.getString("job_id") == job)
            val event = BufferEventJournal.localRecord(db, intent.request).event
            val p = event.getJSONObject("payload")
            check(event.getString("type") == "MeshTaskRequested" && p.getString("scope_revision") == intent.scope &&
                p.getString("capture_state") == intent.state && BufferCrdt.canonical(p.getJSONObject("contract")) == BufferCrdt.canonical(intent.contract))
            if (intent.terminal != null) {
                val terminal = BufferEventJournal.localRecord(db, intent.terminal).event
                val tp = terminal.getJSONObject("payload")
                check(terminal.getString("type") == "MeshTaskSourceResolved" && tp.getString("request_event_id") == intent.request &&
                    tp.getString("job_id") == job && tp.getString("status") in setOf("applied", "superseded", "failed"))
            }
            intent
        }
    }
    fun prepare(db: SQLiteDatabase, plan: MeshGroupPlan, contract: JSONObject, state: String, emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction()); BufferMeshTaskContract.validate(contract)
        val job = contract.getString("job_id"); val scope = plan.binding.getString("revision")
        val previous = load(db, job)
        if (previous != null) {
            check(BufferCrdt.canonical(previous.contract) == BufferCrdt.canonical(contract))
            if (previous.terminal != null) return previous.contract
            if (previous.state != state) {
                // A retry cannot silently move the original write precondition
                // past a user edit and authorize overwriting the newer state.
                resolve(db, previous, "superseded", JSONObject().put("reason", "capture_changed"), emit)
                return previous.contract
            }
            if (previous.scope == scope) return previous.contract
        }
        val count = db.rawQuery("SELECT COUNT(*) FROM mesh_task_sources WHERE scope_revision=? AND terminal_event_id IS NULL AND job_id<>?", arrayOf(scope, job))
            .use { check(it.moveToFirst()); it.getInt(0) }
        require(count < 4) { "本机已有待处理协作任务" }
        val event = emit("MeshTaskRequested", JSONObject().put("contract", contract).put("scope_revision", scope).put("capture_state", state))
        db.insertWithOnConflict("mesh_task_sources", null, ContentValues().apply {
            put("job_id", job); put("contract", BufferCrdt.canonical(contract)); put("scope_revision", scope)
            put("capture_state", state); put("request_event_id", event); putNull("terminal_event_id")
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        permits.remove(job)
        return contract
    }
    fun validateGrant(intent: Intent, plan: MeshGroupPlan, coordinator: String, grant: CoordinatorLeaseGrant, snapshot: CoordinatorSnapshot,
                      terminalReceipt: Boolean = false) {
        val c = intent.contract
        require(intent.scope == plan.binding.getString("revision") && intent.terminal == null)
        require(snapshot.coordinatorDeviceId == coordinator && grant.coordinatorTerm > 0 &&
            (terminalReceipt || snapshot.coordinatorTerm == grant.coordinatorTerm))
        require(coordinator == plan.binding.getString("device_id") || coordinator in plan.peers)
        if (!terminalReceipt) require(grant.ownerDeviceId == plan.binding.getString("device_id") || grant.ownerDeviceId in plan.peers)
        // A current authenticated coordinator may return a result it durably
        // accepted before a restart/term change. This is an immutable completed
        // receipt, never admission of an old grant or a newly arriving result.
        // Terms belong to a membership domain. A historical result may come
        // from a different domain, so its term is not ordered against this one.
        require(grant.jobId == c.getString("job_id") && grant.captureId == BufferMeshTaskContract.target(c) &&
            grant.inputRevision == c.getLong("input_revision") && grant.stage == c.getString("stage") && grant.skillVersion == c.getString("skill_version"))
    }
    fun pending(db: SQLiteDatabase, plan: MeshGroupPlan): List<Intent> {
        check(db.inTransaction())
        val ids = db.rawQuery("SELECT job_id FROM mesh_task_sources WHERE scope_revision=? AND terminal_event_id IS NULL ORDER BY job_id LIMIT 5",
            arrayOf(plan.binding.getString("revision"))).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        check(ids.size <= 4)
        return ids.map { requireNotNull(load(db, it)) }
    }
    fun pin(db: SQLiteDatabase, plan: MeshGroupPlan, coordinator: String, grant: CoordinatorLeaseGrant,
            snapshot: CoordinatorSnapshot, started: Long, now: Long) {
        val intent = requireNotNull(load(db, grant.jobId)); validateGrant(intent, plan, coordinator, grant, snapshot)
        require(started >= 0 && now >= started && now - started < 12_000 && grant.ttlMillis in 1L..20_000)
        // Charge the entire request round trip to the TTL. Another process's
        // monotonic timestamp is never compared with this process's clock.
        val deadline = Math.addExact(started, grant.ttlMillis); require(deadline > now)
        val prior = permits[grant.jobId]
        if (prior != null && prior.scope == intent.scope && prior.grant.leaseId == grant.leaseId) {
            require(prior.coordinator == coordinator && prior.grant.copy(ttlMillis = grant.ttlMillis, renewalSequence = grant.renewalSequence) == grant)
            require(grant.renewalSequence >= prior.grant.renewalSequence)
            if (grant.renewalSequence == prior.grant.renewalSequence) {
                permits[grant.jobId] = prior.copy(deadline = minOf(prior.deadline, deadline))
                return
            }
            // A higher sequence comes from a fresh authenticated coordinator
            // response: the coordinator has renewed its still-live lease.
            // The source may have missed intermediate renewals while offline;
            // only a same-sequence replay must retain the old deadline above.
        }
        permits[grant.jobId] = Permit(coordinator, grant, deadline, intent.scope)
    }
    fun authorize(db: SQLiteDatabase, plan: MeshGroupPlan, peer: String, job: String, lease: String,
                  snapshot: CoordinatorSnapshot, now: Long): Intent {
        val intent = requireNotNull(load(db, job)); val permit = requireNotNull(permits[job])
        validateGrant(intent, plan, permit.coordinator, permit.grant, snapshot)
        require(permit.scope == intent.scope && permit.deadline > now && permit.grant.leaseId == lease && permit.grant.ownerDeviceId == peer)
        return intent
    }
    fun permitGrant(job: String): CoordinatorLeaseGrant = requireNotNull(permits[job]).grant
    fun resolve(db: SQLiteDatabase, intent: Intent, status: String, evidence: JSONObject, emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); require(status in setOf("applied", "superseded", "failed"))
        require(status != "failed" || intent.contract.get("stage") == "transcribe")
        val job = intent.contract.getString("job_id")
        val event = emit("MeshTaskSourceResolved", JSONObject().put("job_id", job).put("request_event_id", intent.request)
            .put("status", status).put("evidence", evidence))
        check(db.update("mesh_task_sources", ContentValues().apply { put("terminal_event_id", event) },
            "job_id=? AND request_event_id=? AND terminal_event_id IS NULL", arrayOf(job, intent.request)) == 1)
        if (status == "failed") db.insertWithOnConflict("mesh_task_source_failures", null, ContentValues().apply {
            put("capture_id", intent.contract.getString("capture_id")); put("job_id", job); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        permits.remove(job)
    }
    fun failure(db: SQLiteDatabase, capture: String): String? {
        check(db.inTransaction())
        val pending = db.rawQuery("SELECT transcript FROM captures WHERE capture_id=?", arrayOf(capture))
            .use { it.moveToFirst() && (it.isNull(0) || it.getString(0).isBlank()) }
        if (!pending) return null
        val saved = db.rawQuery("SELECT job_id,event_id FROM mesh_task_source_failures WHERE capture_id=?", arrayOf(capture))
            .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
        val intent = requireNotNull(load(db, saved.first))
        check(intent.contract.getString("capture_id") == capture && intent.terminal == saved.second)
        val event = BufferEventJournal.localRecord(db, saved.second).event
        val p = event.getJSONObject("payload"); check(p.getString("status") == "failed")
        val receipt = p.getJSONObject("evidence").getJSONObject("receipt")
        val result = receipt.getJSONObject("result"); BufferMeshExecution.validateResult(intent.contract, result)
        check(receipt.getString("result_sha256") == BufferMeshTaskContract.digest(result))
        return result.getString("failure")
    }
}
