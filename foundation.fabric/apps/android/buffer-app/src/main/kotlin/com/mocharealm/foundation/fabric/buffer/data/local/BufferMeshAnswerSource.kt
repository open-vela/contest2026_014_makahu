package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferAnswerCodec
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject

/** Durable explicit user requests. Source-history import has no insertion
 * path here. Immutable question input is separate from the current card CAS. */
internal object BufferMeshAnswerSource {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS question_answer_heads (card_id TEXT PRIMARY KEY,event_id TEXT)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_answer_requests (
            job_id TEXT PRIMARY KEY,card_id TEXT NOT NULL,contract TEXT NOT NULL,input_json TEXT NOT NULL,
            card_state TEXT NOT NULL,event_id TEXT NOT NULL,request_event_id TEXT NOT NULL UNIQUE,terminal_event_id TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_answer_pending ON mesh_answer_requests(terminal_event_id,job_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_answer_card ON mesh_answer_requests(card_id,terminal_event_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS mesh_answer_active_card ON mesh_answer_requests(card_id) WHERE terminal_event_id IS NULL")
    }
    data class Request(val contract: JSONObject, val input: JSONObject, val cardState: String, val event: String, val terminal: String?)
    fun load(db: SQLiteDatabase, job: String): Request {
        check(db.inTransaction())
        val row = db.rawQuery("SELECT contract,input_json,card_state,event_id,terminal_event_id,card_id,request_event_id FROM mesh_answer_requests WHERE job_id=?", arrayOf(job)).use {
            check(it.moveToFirst()); List(7) { n -> if (it.isNull(n)) null else it.getString(n) }
        }
        val contract = BufferSourceEvent.parseObject(requireNotNull(row[0]), 8192)
        val input = BufferSourceEvent.parseObject(requireNotNull(row[1]), 128 * 1024)
        BufferAnswerCodec.validateInput(contract, input); check(contract.get("job_id") == job)
        check(input.get("card_id") == row[5] && contract.get("source_event_id") == row[6])
        val event = BufferEventJournal.localRecord(db, requireNotNull(row[3])).event; val p = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskAnswerInputPrepared" && p.get("card_state") == row[2] &&
            BufferCrdt.canonical(p.getJSONObject("contract")) == row[0] && BufferCrdt.canonical(p.getJSONObject("input")) == row[1])
        val original = BufferEventJournal.localRecord(db, contract.getString("source_event_id"))
        val op = original.event.getJSONObject("payload")
        check(original.envelope.get("provenance") == "native" && original.event.get("type") == "QuestionAnswerRequested" &&
            original.event.get("capture_id") == contract.get("capture_id") && original.logicalClock == contract.getLong("input_revision") &&
            original.fingerprint == contract.getString("source_event_sha256") && op.get("card_id") == input.get("card_id") &&
            op.get("input_sha256") == contract.get("input_sha256") && op.get("card_state") == row[2] && op.get("delivery") == "mesh")
        row[4]?.let {
            val terminal = BufferEventJournal.localRecord(db, it).event; val tp = terminal.getJSONObject("payload")
            check(terminal.get("type") == "MeshTaskAnswerRequestResolved" && tp.get("job_id") == job && tp.get("request_event_id") == row[3] &&
                tp.get("status") in setOf("answered", "unresolved", "superseded"))
            if (tp.get("status") == "unresolved") require(tp.get("detail") is String && tp.getString("detail").isNotBlank() && tp.getString("detail").length <= 500)
        }
        return Request(contract, input, requireNotNull(row[2]), requireNotNull(row[3]), row[4])
    }
    fun save(db: SQLiteDatabase, contract: JSONObject, input: JSONObject, state: String, emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); BufferAnswerCodec.validateInput(contract, input)
        val event = emit("MeshTaskAnswerInputPrepared", JSONObject().put("contract", contract).put("input", input).put("card_state", state))
        db.insertOrThrow("mesh_answer_requests", null, ContentValues().apply {
            put("job_id", contract.getString("job_id")); put("card_id", input.getString("card_id")); put("contract", BufferCrdt.canonical(contract))
            put("input_json", BufferCrdt.canonical(input)); put("card_state", state); put("event_id", event)
            put("request_event_id", contract.getString("source_event_id"))
        })
        load(db, contract.getString("job_id"))
    }
    fun pending(db: SQLiteDatabase, after: String = "", card: String? = null): List<Request> {
        check(db.inTransaction())
        val selection = if (card == null) "job_id>?" else "card_id=?"
        val ids = db.rawQuery("SELECT job_id FROM mesh_answer_requests WHERE terminal_event_id IS NULL AND $selection ORDER BY job_id LIMIT 8",
            arrayOf(card ?: after)).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        return ids.map { load(db, it) }
    }
    fun resolve(db: SQLiteDatabase, request: Request, status: String, emit: (String, JSONObject) -> String, detail: String? = null) {
        check(db.inTransaction()); require(status in setOf("answered", "unresolved", "superseded"))
        if (request.terminal != null) return
        if (status == "unresolved") require(detail != null && detail.isNotBlank() && detail.length <= 500 && '\u0000' !in detail)
        val job = request.contract.getString("job_id")
        val event = emit("MeshTaskAnswerRequestResolved", JSONObject().put("job_id", job).put("request_event_id", request.event)
            .put("status", status).put("detail", detail ?: JSONObject.NULL))
        check(db.update("mesh_answer_requests", ContentValues().apply { put("terminal_event_id", event) },
            "job_id=? AND terminal_event_id IS NULL", arrayOf(job)) == 1)
    }
}
