package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import org.json.JSONObject

internal data class MeshSourceCancellation(val contract: JSONObject, val coordinator: String, val dispatch: String) {
    val key: String get() = "${contract.getString("job_id")}:$coordinator"
}

/** Legacy dispatch checkpoints are read for cleanup only. New offers use
 * BufferMeshScopedDispatch, which retains both endpoint revisions. */
internal object BufferMeshSourceDispatch {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_task_source_dispatch (
            job_id TEXT NOT NULL, coordinator TEXT NOT NULL, scope_revision TEXT NOT NULL,
            request_event_id TEXT NOT NULL, event_id TEXT NOT NULL, ack_event_id TEXT,
            PRIMARY KEY(job_id,coordinator))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_task_cancel_pending ON mesh_task_source_dispatch(ack_event_id,job_id,coordinator)")
    }
    private fun verify(db: SQLiteDatabase, id: String, coordinator: String, eventId: String, request: String, scope: String) {
        val event = BufferEventJournal.localRecord(db, eventId).event
        val p = event.getJSONObject("payload")
        check(event.getString("type") == "MeshTaskDispatchRequested" && p.getString("job_id") == id && p.getString("coordinator") == coordinator &&
            p.getString("request_event_id") == request && p.getString("scope_revision") == scope)
        val original = BufferEventJournal.localRecord(db, request).event
        val op = original.getJSONObject("payload")
        BufferMeshTaskContract.validate(op.getJSONObject("contract"))
        check(original.getString("type") == "MeshTaskRequested" && op.getString("scope_revision") == scope && op.getJSONObject("contract").getString("job_id") == id)
    }
    fun pending(db: SQLiteDatabase, plan: MeshGroupPlan, after: String, source: BufferMeshSourceTasks): List<MeshSourceCancellation> {
        check(db.inTransaction())
        val position = after.split(':', limit = 2)
        val job = position.getOrElse(0) { "" }; val peer = position.getOrElse(1) { "" }
        val members = plan.peers + plan.binding.getString("device_id")
        val args = arrayOf(job, job, peer, *members.toTypedArray())
        val candidates = db.rawQuery("""SELECT d.job_id,d.coordinator,d.event_id,d.request_event_id,d.scope_revision
            FROM mesh_task_source_dispatch d JOIN mesh_task_sources s ON s.job_id=d.job_id
            WHERE d.ack_event_id IS NULL AND s.terminal_event_id IS NOT NULL
            AND (d.job_id>? OR (d.job_id=? AND d.coordinator>?))
            AND d.coordinator IN (${members.joinToString(",") { "?" }}) ORDER BY d.job_id,d.coordinator LIMIT 8""", args).use { rows ->
            buildList { while (rows.moveToNext()) add(List(5) { rows.getString(it) }) }
        }
        return candidates.map { row ->
            verify(db, row[0], row[1], row[2], row[3], row[4])
            val intent = requireNotNull(source.load(db, row[0])); check(intent.terminal != null)
            MeshSourceCancellation(intent.contract, row[1], row[2])
        }
    }
    fun acknowledge(db: SQLiteDatabase, cancellation: MeshSourceCancellation, result: JSONObject, emit: (String, JSONObject) -> String) {
        check(db.inTransaction())
        val id = cancellation.contract.getString("job_id")
        val status = result.getString("status")
        require(status in setOf("cancelled", "completed"))
        if (status == "completed") {
            require(result.keys().asSequence().toSet() == setOf("status", "job_id", "receipt_event_id", "result_sha256", "result", "grant"))
            val grant = BufferMeshSourceTasks.grant(result.getJSONObject("grant"))
            require(result.getString("job_id") == id && grant.jobId == id && grant.captureId == BufferMeshTaskContract.target(cancellation.contract) &&
                grant.inputRevision == cancellation.contract.getLong("input_revision") && grant.stage == cancellation.contract.getString("stage") &&
                grant.skillVersion == cancellation.contract.getString("skill_version"))
            val value = result.getJSONObject("result")
            BufferMeshExecution.validateResult(cancellation.contract, value)
            require(BufferMeshTaskContract.digest(value) == result.getString("result_sha256"))
        } else require(result.keys().asSequence().toSet() == setOf("status", "receipt_event_id"))
        val receipt = result.getString("receipt_event_id")
        require(receipt.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val row = db.rawQuery("SELECT event_id,request_event_id,scope_revision,ack_event_id FROM mesh_task_source_dispatch WHERE job_id=? AND coordinator=?",
            arrayOf(id, cancellation.coordinator)).use { if (it.moveToFirst()) List(4) { n -> if (it.isNull(n)) "" else it.getString(n) } else null }
            ?: return
        if (row[0] != cancellation.dispatch || row[3].isNotEmpty()) return
        verify(db, id, cancellation.coordinator, row[0], row[1], row[2])
        val event = emit("MeshTaskCancellationAcknowledged", JSONObject().put("job_id", id).put("coordinator", cancellation.coordinator)
            .put("dispatch_event_id", cancellation.dispatch).put("remote_status", status).put("receipt_event_id", receipt))
        check(db.update("mesh_task_source_dispatch", ContentValues().apply { put("ack_event_id", event) },
            "job_id=? AND coordinator=? AND event_id=? AND ack_event_id IS NULL", arrayOf(id, cancellation.coordinator, cancellation.dispatch)) == 1)
    }
}
