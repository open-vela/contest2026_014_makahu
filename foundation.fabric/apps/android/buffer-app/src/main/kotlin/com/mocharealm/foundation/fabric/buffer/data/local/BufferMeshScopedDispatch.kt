package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import org.json.JSONObject
import java.util.UUID

internal data class MeshScopedCancellation(val contract: JSONObject, val source: String, val coordinator: String,
    val sourceRevision: String, val targetRevision: String, val dispatch: String, val cancellation: String) {
    val key: String get() = dispatch
    fun message(nonce: String): JSONObject = JSONObject().put("type", "buffer.mesh.cancel").put("protocol", 1)
        .put("device_id", source).put("nonce", nonce).put("job_id", contract.getString("job_id"))
        .put("source_revision", sourceRevision).put("target_revision", targetRevision)
        .put("contract_sha256", BufferMeshTaskContract.digest(contract))
}

/** Append-only dispatch attempts. One source intent per immutable job/source
 * revision is enforced by BufferMeshSourceTasks.prepare. A revision pair names
 * that intent at one recipient, independently of its current membership view.
 * Superseding an intent never hides an earlier dispatch that needs cleanup. */
internal object BufferMeshScopedDispatch {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_scoped_dispatch (
            event_id TEXT PRIMARY KEY,job_id TEXT NOT NULL,source_device TEXT NOT NULL,coordinator TEXT NOT NULL,
            source_revision TEXT NOT NULL,target_revision TEXT NOT NULL,request_event_id TEXT NOT NULL,
            cancel_event_id TEXT,ack_event_id TEXT,
            UNIQUE(job_id,coordinator,source_revision,target_revision))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_scoped_cleanup ON mesh_task_scoped_dispatch(source_device,ack_event_id,cancel_event_id,event_id)")
    }
    fun note(db: SQLiteDatabase, intent: BufferMeshSourceTasks.Intent, local: String, coordinator: String, target: String,
             emit: (String, JSONObject) -> String) {
        check(db.inTransaction() && intent.terminal == null && intent.contract.get("source_device") == local)
        require(UUID.fromString(target).toString() == target)
        val job = intent.contract.getString("job_id")
        val prior = db.rawQuery("SELECT event_id,request_event_id,cancel_event_id FROM mesh_task_scoped_dispatch " +
            "WHERE job_id=? AND coordinator=? AND source_revision=? AND target_revision=?", arrayOf(job, coordinator, intent.scope, target))
            .use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.isNull(2)) else null }
        if (prior != null) {
            check(prior.second == intent.request && prior.third)
            load(db, prior.first)
            return
        }
        val event = emit("MeshTaskScopedDispatchRequested", JSONObject().put("job_id", job).put("source_device", local)
            .put("coordinator", coordinator).put("source_revision", intent.scope).put("target_revision", target).put("request_event_id", intent.request))
        db.insertOrThrow("mesh_task_scoped_dispatch", null, ContentValues().apply {
            put("event_id", event); put("job_id", job); put("source_device", local); put("coordinator", coordinator)
            put("source_revision", intent.scope); put("target_revision", target); put("request_event_id", intent.request)
        })
    }
    private data class Dispatch(val id: String, val job: String, val source: String, val peer: String, val scope: String,
        val target: String, val request: String, val cancelled: String?, val ack: String?, val contract: JSONObject)
    private fun load(db: SQLiteDatabase, id: String): Dispatch {
        check(db.inTransaction())
        val row = db.rawQuery("SELECT job_id,source_device,coordinator,source_revision,target_revision,request_event_id,cancel_event_id,ack_event_id " +
            "FROM mesh_task_scoped_dispatch WHERE event_id=?", arrayOf(id)).use {
            check(it.moveToFirst()); List(8) { n -> if (it.isNull(n)) null else it.getString(n) }
        }
        val event = BufferEventJournal.localRecord(db, id).event; val p = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskScopedDispatchRequested")
        listOf("job_id", "source_device", "coordinator", "source_revision", "target_revision", "request_event_id").forEachIndexed { n, field -> check(p.get(field) == row[n]) }
        val original = BufferEventJournal.localRecord(db, requireNotNull(row[5])).event; val op = original.getJSONObject("payload")
        val contract = op.getJSONObject("contract"); BufferMeshTaskContract.validate(contract)
        check(original.get("type") == "MeshTaskRequested" && op.get("scope_revision") == row[3] &&
            contract.get("job_id") == row[0] && contract.get("source_device") == row[1])
        row[6]?.let {
            val cancel = BufferEventJournal.localRecord(db, it).event
            check(cancel.get("type") == "MeshTaskScopedCancellationRequested" && cancel.getJSONObject("payload").get("dispatch_event_id") == id)
        }
        row[7]?.let {
            val ack = BufferEventJournal.localRecord(db, it).event; val ap = ack.getJSONObject("payload")
            check(ack.get("type") == "MeshTaskScopedCancellationAcknowledged" && ap.get("dispatch_event_id") == id && ap.get("cancellation_event_id") == row[6])
            val cancellation = MeshScopedCancellation(contract, requireNotNull(row[1]), requireNotNull(row[2]), requireNotNull(row[3]), requireNotNull(row[4]), id, requireNotNull(row[6]))
            val reply = ap.getJSONObject("reply")
            check(BufferMeshTaskCancellation.accept(reply, cancellation.message(reply.getString("nonce")), cancellation.coordinator))
        }
        return Dispatch(id, requireNotNull(row[0]), requireNotNull(row[1]), requireNotNull(row[2]), requireNotNull(row[3]),
            requireNotNull(row[4]), requireNotNull(row[5]), row[6], row[7], contract)
    }

    fun reconcile(db: SQLiteDatabase, local: String, revision: String, source: BufferMeshSourceTasks,
                  targetRevision: (String) -> String?, emit: (String, JSONObject) -> String) {
        check(db.inTransaction())
        val ids = db.rawQuery("SELECT event_id FROM mesh_task_scoped_dispatch WHERE source_device=? AND cancel_event_id IS NULL AND ack_event_id IS NULL ORDER BY event_id", arrayOf(local))
            .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        for (id in ids) {
            val dispatch = load(db, id); val intent = source.load(db, dispatch.job)
            val target = targetRevision(dispatch.peer)
            val stale = dispatch.scope != revision || intent == null || intent.terminal != null || intent.request != dispatch.request ||
                (target != null && target != dispatch.target)
            if (!stale) continue
            val event = emit("MeshTaskScopedCancellationRequested", JSONObject().put("dispatch_event_id", id).put("job_id", dispatch.job))
            check(db.update("mesh_task_scoped_dispatch", ContentValues().apply { put("cancel_event_id", event) },
                "event_id=? AND cancel_event_id IS NULL", arrayOf(id)) == 1)
        }
    }
    fun pending(db: SQLiteDatabase, local: String, after: String): List<MeshScopedCancellation> {
        check(db.inTransaction())
        val ids = db.rawQuery("SELECT event_id FROM mesh_task_scoped_dispatch WHERE source_device=? AND cancel_event_id IS NOT NULL AND ack_event_id IS NULL AND event_id>? ORDER BY event_id LIMIT 8",
            arrayOf(local, after)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        return ids.map { id -> load(db, id).let { MeshScopedCancellation(it.contract, it.source, it.peer, it.scope, it.target, it.id, requireNotNull(it.cancelled)) } }
    }
    fun acknowledge(db: SQLiteDatabase, value: MeshScopedCancellation, reply: JSONObject, emit: (String, JSONObject) -> String) {
        val dispatch = load(db, value.dispatch)
        check(dispatch.source == value.source && dispatch.peer == value.coordinator && dispatch.scope == value.sourceRevision &&
            dispatch.target == value.targetRevision && dispatch.cancelled == value.cancellation && BufferCrdt.canonical(dispatch.contract) == BufferCrdt.canonical(value.contract))
        check(BufferMeshTaskCancellation.accept(reply, value.message(reply.getString("nonce")), value.coordinator))
        if (dispatch.ack != null) return
        val event = emit("MeshTaskScopedCancellationAcknowledged", JSONObject().put("dispatch_event_id", dispatch.id)
            .put("cancellation_event_id", dispatch.cancelled).put("reply", reply))
        check(db.update("mesh_task_scoped_dispatch", ContentValues().apply { put("ack_event_id", event) }, "event_id=? AND ack_event_id IS NULL", arrayOf(dispatch.id)) == 1)
    }
}
