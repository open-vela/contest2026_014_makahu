package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferWeeklyCodec
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** A lease on an existing weekly request, not a synthetic capture. Only the
 * local request worker writes these checkpoints; imported history cannot. */
internal object BufferMeshWeeklySource {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_weekly_inputs (
            job_id TEXT PRIMARY KEY,subject_id TEXT NOT NULL,contract TEXT NOT NULL,input_json TEXT NOT NULL,
            lease_id TEXT NOT NULL,source_request_event_id TEXT NOT NULL,event_id TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_weekly_owner ON mesh_weekly_inputs(subject_id,lease_id)")
    }
    data class Input(val contract: JSONObject, val value: JSONObject, val lease: String, val sourceRequest: String)
    fun load(db: SQLiteDatabase, job: String): Input {
        check(db.inTransaction())
        val row = db.rawQuery("SELECT subject_id,contract,input_json,lease_id,source_request_event_id,event_id FROM mesh_weekly_inputs WHERE job_id=?", arrayOf(job))
            .use { check(it.moveToFirst()); List(6) { n -> it.getString(n) } }
        val contract = BufferSourceEvent.parseObject(row[1], 8192)
        val value = BufferSourceEvent.parseObject(row[2], BufferWeeklyCodec.MAX_INPUT_BYTES)
        val facts = BufferWeeklyCodec.validateInput(contract, value)
        check(contract.get("job_id") == job && contract.get("subject_id") == row[0])
        val event = BufferEventJournal.localRecord(db, row[5]).event; val p = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskWeeklyInputPrepared" && BufferCrdt.canonical(p.getJSONObject("contract")) == row[1] &&
            BufferCrdt.canonical(p.getJSONObject("input")) == row[2] && p.get("lease_id") == row[3] && p.get("source_request_event_id") == row[4])
        val dispatch = BufferEventJournal.localRecord(db, row[4]).event
        check(dispatch.get("type") == "MeshTaskRequested" && BufferCrdt.canonical(dispatch.getJSONObject("payload").getJSONObject("contract")) == row[1])
        val original = BufferEventJournal.localRecord(db, contract.getString("source_event_id")); val op = original.event.getJSONObject("payload")
        check(original.envelope.get("provenance") == "native" && original.event.get("type") == "WeeklySummaryRequested" && original.event.isNull("capture_id") &&
            original.fingerprint == contract.getString("source_event_sha256") && original.logicalClock == contract.getLong("input_revision") &&
            op.get("job_id") == row[0] && op.get("period_end") == value.get("requested_at") && op.getLong("period_start") == facts.getLong("period_start") &&
            op.get("facts_sha256") == BufferMeshTaskContract.digest(facts))
        return Input(contract, value, row[3], row[4])
    }
    fun owned(db: SQLiteDatabase, subject: String, lease: String): Input? {
        check(db.inTransaction())
        val id = db.rawQuery("SELECT job_id FROM mesh_weekly_inputs WHERE subject_id=? AND lease_id=? ORDER BY job_id LIMIT 1", arrayOf(subject, lease))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        return load(db, id)
    }
    fun bind(db: SQLiteDatabase, intent: BufferMeshSourceTasks.Intent, value: JSONObject, lease: String, emit: (String, JSONObject) -> String) {
        check(db.inTransaction() && intent.terminal == null)
        BufferWeeklyCodec.validateInput(intent.contract, value)
        val job = intent.contract.getString("job_id")
        val prior = db.rawQuery("SELECT 1 FROM mesh_weekly_inputs WHERE job_id=?", arrayOf(job)).use { it.moveToFirst() }
        if (prior) {
            val old = load(db, job)
            check(BufferCrdt.canonical(old.value) == BufferCrdt.canonical(value))
            if (old.lease == lease && old.sourceRequest == intent.request) return
        }
        val event = emit("MeshTaskWeeklyInputPrepared", JSONObject().put("contract", intent.contract).put("input", value)
            .put("lease_id", lease).put("source_request_event_id", intent.request))
        db.insertWithOnConflict("mesh_weekly_inputs", null, ContentValues().apply {
            put("job_id", job); put("subject_id", intent.contract.getString("subject_id")); put("contract", BufferCrdt.canonical(intent.contract))
            put("input_json", BufferCrdt.canonical(value)); put("lease_id", lease); put("source_request_event_id", intent.request); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        load(db, job)
    }
}
