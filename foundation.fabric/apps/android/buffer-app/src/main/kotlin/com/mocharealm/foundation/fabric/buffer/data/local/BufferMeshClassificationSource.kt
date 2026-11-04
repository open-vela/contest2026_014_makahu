package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationCodec
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferClassificationJob
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.FocusSession
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import org.json.JSONArray
import org.json.JSONObject

/** Source-owned input and disclosure proofs. All entry points require the
 * repository monitor and its transaction. The caller checks current member,
 * source intent, executor incarnation and disclosure permit on every read.
 * Imported source history cannot create these local execution checkpoints. */
internal object BufferMeshClassificationSource {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_classification_inputs (job_id TEXT PRIMARY KEY,input_json TEXT NOT NULL,event_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_classification_requests (job_id TEXT PRIMARY KEY,request_event_id TEXT NOT NULL,lease_id TEXT NOT NULL,event_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_classification_request_lease ON mesh_classification_requests(lease_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_classification_reads (
            job_id TEXT NOT NULL,lease_id TEXT NOT NULL,sequence INTEGER NOT NULL,event_id TEXT NOT NULL,
            PRIMARY KEY(job_id,lease_id,sequence))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_classification_records (
            job_id TEXT NOT NULL,card_id TEXT NOT NULL,detail INTEGER NOT NULL,view_sha256 TEXT NOT NULL,event_id TEXT NOT NULL,
            PRIMARY KEY(job_id,card_id,detail))""")
    }

    fun bind(db: SQLiteDatabase, intent: BufferMeshSourceTasks.Intent, lease: String, emit: (String, JSONObject) -> String) {
        check(db.inTransaction() && intent.terminal == null); load(db, intent.contract)
        val job = intent.contract.getString("job_id")
        val prior = request(db, intent)
        if (prior == lease) return
        val event = emit("MeshTaskClassificationRequestBound", JSONObject().put("job_id", job)
            .put("request_event_id", intent.request).put("lease_id", lease))
        db.insertWithOnConflict("mesh_classification_requests", null, ContentValues().apply {
            put("job_id", job); put("request_event_id", intent.request); put("lease_id", lease); put("event_id", event)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    fun request(db: SQLiteDatabase, intent: BufferMeshSourceTasks.Intent): String? {
        check(db.inTransaction())
        val job = intent.contract.getString("job_id")
        val saved = db.rawQuery("SELECT request_event_id,lease_id,event_id FROM mesh_classification_requests WHERE job_id=?", arrayOf(job))
            .use { if (it.moveToFirst()) List(3) { n -> it.getString(n) } else null } ?: return null
        val event = BufferEventJournal.localRecord(db, saved[2]).event
        val p = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskClassificationRequestBound" && p.get("job_id") == job &&
            p.get("request_event_id") == saved[0] && p.get("lease_id") == saved[1])
        return saved[1].takeIf { saved[0] == intent.request }
    }

    fun input(job: BufferClassificationJob, focus: JSONObject?, allowAssistantDecision: Boolean = false): JSONObject {
        require(job.captureId.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(job.transcript.isNotBlank() && job.transcript.length <= 32_000 && '\u0000' !in job.transcript)
        val value = JSONObject().put("version", if (allowAssistantDecision) 2 else 1).put("capture_id", job.captureId).put("transcript", job.transcript)
            .put("focus_session", focus?.let { FocusSession.parse(it).json() } ?: JSONObject.NULL)
        if (allowAssistantDecision) value.put("allow_device_assistant", true)
        return BufferSourceEvent.parseObject(BufferCrdt.canonical(value), BufferMeshTaskContract.MAX_CLASSIFICATION_INPUT_BYTES)
    }

    fun validateInput(contract: JSONObject, value: JSONObject): BufferClassificationJob {
        BufferMeshTaskContract.validate(contract); require(contract.get("stage") == "classify")
        val assistant = value.get("version") == 2
        require(value.keys().asSequence().toSet() == setOf("version", "capture_id", "transcript", "focus_session") +
            (if (assistant) setOf("allow_device_assistant") else emptySet()))
        require(if (assistant) contract.get("skill_version") == "buffer-classify-v2" && value.get("allow_device_assistant") == true else value.get("version") == 1)
        require(value.get("capture_id") is String && value.get("transcript") is String && value.get("capture_id") == contract.get("capture_id"))
        val job = BufferClassificationJob(value.getString("capture_id"), value.getString("transcript"))
        val copy = input(job, if (value.isNull("focus_session")) null else value.getJSONObject("focus_session"), assistant)
        require(BufferCrdt.canonical(value) == BufferCrdt.canonical(copy))
        require(BufferCrdt.canonical(copy).toByteArray(Charsets.UTF_8).size.toLong() == contract.getLong("input_bytes") &&
            BufferMeshTaskContract.digest(copy) == contract.getString("input_sha256"))
        return job
    }

    private fun original(db: SQLiteDatabase, contract: JSONObject, value: JSONObject) {
        validateInput(contract, value)
        val record = BufferEventJournal.localRecord(db, contract.getString("source_event_id"))
        require(record.envelope.get("provenance") == "native" && record.event.get("type") == "CaptureTranscribed" &&
            record.event.get("capture_id") == contract.get("capture_id") && record.fingerprint == contract.getString("source_event_sha256") &&
            record.logicalClock == contract.getLong("input_revision") && record.event.getJSONObject("payload").get("transcript") == value.get("transcript"))
    }

    fun save(db: SQLiteDatabase, contract: JSONObject, value: JSONObject, emit: (String, JSONObject) -> String) {
        check(db.inTransaction()); original(db, contract, value)
        val job = contract.getString("job_id")
        val prior = db.rawQuery("SELECT 1 FROM mesh_classification_inputs WHERE job_id=?", arrayOf(job)).use { it.moveToFirst() }
        if (prior) { check(BufferCrdt.canonical(load(db, contract)) == BufferCrdt.canonical(value)); return }
        val event = emit("MeshTaskClassificationInputPrepared", JSONObject().put("contract", contract).put("input", value))
        db.insertOrThrow("mesh_classification_inputs", null, ContentValues().apply {
            put("job_id", job); put("input_json", BufferCrdt.canonical(value)); put("event_id", event)
        })
    }

    fun load(db: SQLiteDatabase, contract: JSONObject): JSONObject {
        check(db.inTransaction())
        val saved = db.rawQuery("SELECT input_json,event_id FROM mesh_classification_inputs WHERE job_id=?", arrayOf(contract.getString("job_id")))
            .use { check(it.moveToFirst()); it.getString(0) to it.getString(1) }
        val value = BufferSourceEvent.parseObject(saved.first, BufferMeshTaskContract.MAX_CLASSIFICATION_INPUT_BYTES)
        original(db, contract, value)
        val event = BufferEventJournal.localRecord(db, saved.second).event
        val payload = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskClassificationInputPrepared" &&
            BufferCrdt.canonical(payload.getJSONObject("contract")) == BufferCrdt.canonical(contract) &&
            BufferCrdt.canonical(payload.getJSONObject("input")) == saved.first)
        return value
    }

    private fun proof(db: SQLiteDatabase, job: String, id: String, detail: Boolean): String? {
        val saved = db.rawQuery("SELECT view_sha256,event_id FROM mesh_classification_records WHERE job_id=? AND card_id=? AND detail=?",
            arrayOf(job, id, if (detail) "1" else "0")).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return null
        val event = BufferEventJournal.localRecord(db, saved.second).event
        val payload = event.getJSONObject("payload")
        check(event.get("type") == "MeshTaskClassificationRecordsServed" && payload.get("job_id") == job && payload.get("detail") == detail)
        val records = payload.getJSONArray("records")
        check((0 until records.length()).any { records.getJSONObject(it).let { row -> row.get("card_id") == id && row.get("view_sha256") == saved.first } })
        return saved.first
    }

    /** Returns data only. At most eight record queries per live lease; the
     * returned records are hashed before the reply leaves this transaction. */
    fun records(db: SQLiteDatabase, contract: JSONObject, lease: String, action: String, argument: String,
                authorize: () -> Unit, search: (String) -> List<BufferCard>, read: (String) -> BufferCard?,
                emit: (String, JSONObject) -> String): JSONObject {
        check(db.inTransaction()); authorize(); load(db, contract)
        val job = contract.getString("job_id"); val capture = contract.getString("capture_id")
        val count = db.rawQuery("SELECT COUNT(*) FROM mesh_classification_reads WHERE job_id=? AND lease_id=?", arrayOf(job, lease))
            .use { check(it.moveToFirst()); it.getInt(0) }
        require(count < 8) { "本次整理查询次数过多" }
        val detail = action == "read_record"
        val cards = when (action) {
            "search_records" -> { require(argument.isNotBlank() && argument.length <= 120); search(argument) }
            "read_record" -> {
                require(proof(db, job, argument, false) != null) { "记录不在已提供的搜索结果中" }
                listOfNotNull(read(argument)?.also { require(it.cardId == argument) })
            }
            else -> error("unsupported classification read")
        }.filter { it.captureId != capture && it.state !in setOf("deleted", "pending_transcription") }
        require(cards.size <= (if (detail) 1 else 12))
        require(cards.map { it.cardId }.distinct().size == cards.size)
        val records = JSONArray(cards.map { BufferClassificationCodec.record(it, detail) })
        val response = BufferSourceEvent.parseObject(BufferCrdt.canonical(JSONObject().put("records", records)), 192 * 1024)
        val observations = JSONArray(cards.map { card -> JSONObject().put("card_id", card.cardId)
            .put("view_sha256", BufferMeshTaskContract.digest(BufferClassificationCodec.record(card, detail))) })
        authorize()
        val event = emit("MeshTaskClassificationRecordsServed", JSONObject().put("job_id", job).put("lease_id", lease)
            .put("sequence", count + 1).put("detail", detail).put("records", observations))
        db.insertOrThrow("mesh_classification_reads", null, ContentValues().apply {
            put("job_id", job); put("lease_id", lease); put("sequence", count + 1); put("event_id", event)
        })
        for (index in 0 until observations.length()) {
            val observed = observations.getJSONObject(index)
            db.insertWithOnConflict("mesh_classification_records", null, ContentValues().apply {
                put("job_id", job); put("card_id", observed.getString("card_id")); put("detail", if (detail) 1 else 0)
                put("view_sha256", observed.getString("view_sha256")); put("event_id", event)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
        return response
    }

    /** Caller selects IDs from the proposed result, never from executor claims.
     * A changed/deleted record loses its association evidence. */
    fun trustedIds(db: SQLiteDatabase, contract: JSONObject, ids: Set<String>, read: (String) -> BufferCard?): Set<String> {
        check(db.inTransaction()); load(db, contract); require(ids.size <= 64)
        val job = contract.getString("job_id"); val capture = contract.getString("capture_id")
        return ids.filter { id ->
            val card = read(id)
            card != null && card.captureId != capture && card.state !in setOf("deleted", "pending_transcription") &&
                listOf(false, true).any { detail -> proof(db, job, id, detail)?.let {
                    it == BufferMeshTaskContract.digest(BufferClassificationCodec.record(card, detail))
                } == true }
        }.toSet()
    }
}
