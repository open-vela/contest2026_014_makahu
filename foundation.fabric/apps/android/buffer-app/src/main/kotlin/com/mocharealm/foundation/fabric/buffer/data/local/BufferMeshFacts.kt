package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferMeshTaskContract
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONArray
import org.json.JSONObject

/** A set of original task facts, never an execution inbox. Publication cursors
 * count disclosures to one participant, not contiguous source journal history.
 * Private inputs, approvals and live lease control stay in their own stores. */
internal object BufferMeshFacts {
    private val identity = Regex("[0-9a-f]{64}")
    private val types = setOf("MeshTaskRequested", "MeshTaskOffered", "MeshTaskExecuted", "MeshTaskAttemptFailed",
        "MeshTaskResultReceived", "MeshTaskSourceResolved", "MeshTaskCancelled")
    data class Resume(val source: String, val after: Long)

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_fact_publications (
            publication_id INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL,peer TEXT NOT NULL,
            UNIQUE(event_id,peer))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_fact_publication_peer ON mesh_fact_publications(peer,publication_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_fact_scan(id INTEGER PRIMARY KEY CHECK(id=1),after_event_id TEXT NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO mesh_fact_scan VALUES(1,'')")
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_fact_owners(source_id TEXT PRIMARY KEY,peer TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_fact_originals (
            event_id TEXT PRIMARY KEY,source_id TEXT NOT NULL,source_sequence INTEGER NOT NULL,peer TEXT NOT NULL,
            job_id TEXT NOT NULL,type TEXT NOT NULL,logical_clock INTEGER NOT NULL,fingerprint TEXT NOT NULL,envelope TEXT NOT NULL,
            UNIQUE(source_id,source_sequence))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_fact_job ON mesh_fact_originals(job_id,event_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_fact_peer ON mesh_fact_originals(peer)")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_fact_clock ON mesh_fact_originals(logical_clock)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_fact_history (
            event_id TEXT PRIMARY KEY,job_id TEXT NOT NULL,type TEXT NOT NULL,result_sha256 TEXT,outcome TEXT,stage TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS mesh_fact_history_job ON mesh_fact_history(job_id,event_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS mesh_fact_receive_progress (
            peer TEXT NOT NULL,local_device TEXT NOT NULL,source_id TEXT NOT NULL,publication_id INTEGER NOT NULL,
            PRIMARY KEY(peer,local_device))""")
        db.execSQL("CREATE TABLE IF NOT EXISTS mesh_fact_delivery_progress(peer TEXT PRIMARY KEY,publication_id INTEGER NOT NULL)")
    }

    /** Runs after the actual native fact has joined the journal transaction.
     * Dependencies are originals too; no new IDs or synthetic execution facts. */
    fun published(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        val record = BufferEventJournal.localRecord(db, id)
        if (record.envelope.get("provenance") != "native") return
        if (record.event.getString("type") in types) index(db, record)
        val p = record.event.getJSONObject("payload")
        fun original(key: String, type: String): BufferSourceEvent = BufferEventJournal.localRecord(db, p.getString(key)).also {
            check(it.event.get("type") == type)
        }
        fun publish(fact: BufferSourceEvent, peer: String) {
            require(identity.matches(peer))
            validate(fact)
            db.execSQL("INSERT OR IGNORE INTO mesh_fact_publications(event_id,peer) VALUES(?,?)", arrayOf(fact.eventId, peer))
        }
        when (record.event.getString("type")) {
            "MeshTaskScopedDispatchRequested", "MeshTaskDispatchRequested" -> {
                val request = original("request_event_id", "MeshTaskRequested")
                check(request.event.getJSONObject("payload").getJSONObject("contract").get("job_id") == p.get("job_id"))
                publish(request, p.getString("coordinator"))
            }
            "MeshTaskOffered" -> publish(record, p.getString("source_peer"))
            "MeshTaskExecuted", "MeshTaskAttemptFailed" -> {
                publish(record, p.getJSONObject("contract").getString("source_device"))
                publish(record, p.getString("coordinator"))
            }
            "MeshTaskResultReceived", "MeshTaskCancelled" -> {
                val offer = original("offer_event_id", "MeshTaskOffered")
                val contract = offer.event.getJSONObject("payload").getJSONObject("contract")
                check(contract.get("job_id") == p.get("job_id") && contract.get("source_device") == p.get("source_peer"))
                val peers = mutableSetOf(p.getString("source_peer"))
                if (record.event.get("type") == "MeshTaskResultReceived") {
                    check(p.get("contract_sha256") == BufferMeshTaskContract.digest(contract))
                    BufferMeshExecution.validateResult(contract, p.getJSONObject("result"))
                    peers.add(BufferMeshSourceTasks.grant(p.getJSONObject("grant")).ownerDeviceId)
                }
                peers.forEach { publish(offer, it); publish(record, it) }
            }
            "MeshTaskSourceResolved" -> {
                val request = original("request_event_id", "MeshTaskRequested")
                check(request.event.getJSONObject("payload").getJSONObject("contract").get("job_id") == p.get("job_id"))
                val peers = db.rawQuery("SELECT DISTINCT coordinator FROM mesh_task_scoped_dispatch WHERE request_event_id=? " +
                    "UNION SELECT coordinator FROM mesh_task_source_dispatch WHERE request_event_id=?",
                    arrayOf(request.eventId, request.eventId)).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(0)) } }.toMutableSet()
                val evidence = p.getJSONObject("evidence")
                if (evidence.has("receipt") && evidence.getJSONObject("receipt").has("grant")) {
                    peers.add(evidence.getString("coordinator"))
                    peers.add(BufferMeshSourceTasks.grant(evidence.getJSONObject("receipt").getJSONObject("grant")).ownerDeviceId)
                }
                peers.forEach { publish(request, it); publish(record, it) }
            }
        }
    }

    /** Bounded upgrade/recovery scan; publications are idempotent and append
     * after a recipient becomes known, so earlier scan cursors cannot miss it. */
    fun recover(db: SQLiteDatabase) {
        check(db.inTransaction())
        val after = db.rawQuery("SELECT after_event_id FROM mesh_fact_scan WHERE id=1", null).use { check(it.moveToFirst()); it.getString(0) }
        val wanted = types + setOf("MeshTaskScopedDispatchRequested", "MeshTaskDispatchRequested")
        // Use the existing (type,event_id) index instead of decoding private
        // captures and inputs merely to discover historical execution facts.
        // New facts, including IDs below this cursor, publish in their own TX.
        val ids = db.rawQuery("SELECT event_id FROM events WHERE event_id>? AND type IN (${wanted.joinToString(",") { "?" }}) ORDER BY event_id LIMIT 16",
            arrayOf(after, *wanted.toTypedArray())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        ids.forEach { published(db, it) }
        ids.lastOrNull()?.let { db.execSQL("UPDATE mesh_fact_scan SET after_event_id=? WHERE id=1", arrayOf(it)) }
    }

    private fun validate(record: BufferSourceEvent): String {
        require(record.envelope.get("provenance") == "native" && record.event.get("schema_version") == 1 && record.event.isNull("capture_id"))
        val type = record.event.getString("type"); require(type in types)
        val p = record.event.getJSONObject("payload")
        val job = if (p.has("contract")) p.getJSONObject("contract").also(BufferMeshTaskContract::validate).getString("job_id") else p.getString("job_id")
        require(identity.matches(job))
        if (type == "MeshTaskExecuted") {
            BufferMeshExecution.validateResult(p.getJSONObject("contract"), p.getJSONObject("result"), localComputation = true)
            require(p.get("result_sha256") == BufferMeshTaskContract.digest(p.getJSONObject("result")))
        }
        if (type == "MeshTaskResultReceived") {
            require(p.get("result_sha256") == BufferMeshTaskContract.digest(p.getJSONObject("result")))
            require(BufferMeshSourceTasks.grant(p.getJSONObject("grant")).jobId == job)
        }
        return job
    }

    private fun descriptor(record: BufferSourceEvent, publication: Long): JSONObject = JSONObject()
        .put("publication_id", publication).put("event_id", record.eventId).put("source_sequence", record.sequence)
        .put("fingerprint", record.fingerprint).put("bytes", record.raw.toByteArray(Charsets.UTF_8).size)

    fun exchange(db: SQLiteDatabase, peer: String, body: JSONObject): JSONObject {
        check(db.inTransaction()); require(identity.matches(peer))
        val source = BufferEventJournal.source(db)
        return when (body.get("action")) {
            "page" -> {
                require(body.keys().asSequence().toSet() == setOf("action", "source_id", "after"))
                require(body.get("source_id") is String)
                val requested = BufferMeshTaskContract.integer(body, "after", 0L until BufferSourceEvent.MAX_COUNTER)
                val after = if (body.get("source_id") == source) requested else 0L
                val head = db.rawQuery("SELECT COALESCE(MAX(publication_id),0) FROM mesh_fact_publications WHERE peer=?", arrayOf(peer))
                    .use { check(it.moveToFirst()); it.getLong(0) }
                require(after <= head) { "execution fact cursor exceeds publisher history" }
                // The next pull acknowledges only the receiver's durably
                // committed publication prefix, never the source stream prefix.
                db.execSQL("INSERT OR IGNORE INTO mesh_fact_delivery_progress VALUES(?,0)", arrayOf(peer))
                db.execSQL("UPDATE mesh_fact_delivery_progress SET publication_id=MAX(publication_id,?) WHERE peer=?", arrayOf<Any?>(after, peer))
                val entries = JSONArray()
                var through = after
                db.rawQuery("SELECT publication_id,event_id FROM mesh_fact_publications WHERE peer=? AND publication_id>? ORDER BY publication_id LIMIT 2",
                    arrayOf(peer, after.toString())).use { rows -> while (rows.moveToNext()) {
                    val record = BufferEventJournal.localRecord(db, rows.getString(1)); validate(record)
                    through = rows.getLong(0); entries.put(descriptor(record, through))
                } }
                if (entries.length() == 0) through = head
                JSONObject().put("source_id", source).put("after", after).put("through", through).put("head", head).put("entries", entries)
            }
            "read" -> {
                require(body.keys().asSequence().toSet() == setOf("action", "source_id", "publication_id", "event_id", "fingerprint", "offset"))
                require(body.get("source_id") == source)
                val publication = BufferMeshTaskContract.integer(body, "publication_id", 1L until BufferSourceEvent.MAX_COUNTER)
                val id = db.rawQuery("SELECT event_id FROM mesh_fact_publications WHERE peer=? AND publication_id=?", arrayOf(peer, publication.toString()))
                    .use { require(it.moveToFirst()); it.getString(0) }
                val record = BufferEventJournal.localRecord(db, id); validate(record)
                require(body.get("event_id") == id && body.get("fingerprint") == record.fingerprint)
                val bytes = record.raw.toByteArray(Charsets.UTF_8)
                try {
                    val offset = BufferMeshTaskContract.integer(body, "offset", 0L until bytes.size.toLong()).toInt()
                    val part = bytes.copyOfRange(offset, minOf(bytes.size, offset + 16 * 1024))
                    descriptor(record, publication).put("source_id", source).put("offset", offset)
                        .put("data_b64", try { Base64.encodeToString(part, Base64.NO_WRAP) } finally { part.fill(0) })
                } finally { bytes.fill(0) }
            }
            else -> error("unsupported execution fact request")
        }
    }

    fun resume(db: SQLiteDatabase, peer: String, local: String): Resume = db.rawQuery(
        "SELECT source_id,publication_id FROM mesh_fact_receive_progress WHERE peer=? AND local_device=?", arrayOf(peer, local))
        .use { if (it.moveToFirst()) Resume(it.getString(0), it.getLong(1)) else Resume("", 0) }

    fun commit(db: SQLiteDatabase, peer: String, local: String, expected: Resume, page: JSONObject, originals: List<String>) {
        check(db.inTransaction()); require(identity.matches(peer) && identity.matches(local) && peer != local)
        require(resume(db, peer, local) == expected)
        require(page.keys().asSequence().toSet() == setOf("source_id", "after", "through", "head", "entries"))
        val source = page.get("source_id"); require(source is String && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")) && source != BufferEventJournal.source(db))
        val after = BufferMeshTaskContract.integer(page, "after", 0L until BufferSourceEvent.MAX_COUNTER)
        val through = BufferMeshTaskContract.integer(page, "through", after until BufferSourceEvent.MAX_COUNTER)
        val head = BufferMeshTaskContract.integer(page, "head", through until BufferSourceEvent.MAX_COUNTER)
        require(after == if (source == expected.source) expected.after else 0L)
        val entries = page.getJSONArray("entries"); require(entries.length() <= 2 && originals.size == entries.length())
        require(entries.length() > 0 || through == head)
        pin(db, source, peer)
        var previous = after
        for (n in originals.indices) {
            val entry = entries.getJSONObject(n)
            val publication = BufferMeshTaskContract.integer(entry, "publication_id", previous + 1..through)
            val record = BufferSourceEvent.decode(originals[n]); val job = validate(record)
            require(record.source == source && com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt.canonical(entry) ==
                com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt.canonical(descriptor(record, publication)))
            val p = record.event.getJSONObject("payload")
            when (record.event.getString("type")) {
                "MeshTaskRequested" -> require(p.getJSONObject("contract").get("source_device") == peer)
                "MeshTaskExecuted", "MeshTaskAttemptFailed" -> require(p.get("owner_device_id") == peer &&
                    (p.getJSONObject("contract").get("source_device") == local || p.get("coordinator") == local))
                "MeshTaskOffered" -> require(p.get("source_peer") == p.getJSONObject("contract").get("source_device"))
            }
            validateRelations(db, record, peer)
            checkOriginal(db, record)
            // Keep sparse originals separate from the general source stream's
            // visibility/contiguous receive cursors and all execution tables.
            db.rawQuery("SELECT source_id,source_sequence,fingerprint,envelope FROM mesh_fact_originals WHERE event_id=? OR (source_id=? AND source_sequence=?)",
                arrayOf(record.eventId, source, record.sequence.toString())).use { rows ->
                if (rows.moveToFirst()) {
                    require(rows.getString(0) == source && rows.getLong(1) == record.sequence && rows.getString(2) == record.fingerprint && rows.getString(3) == record.raw)
                    require(!rows.moveToNext())
                } else db.insertOrThrow("mesh_fact_originals", null, ContentValues().apply {
                    put("event_id", record.eventId); put("source_id", source); put("source_sequence", record.sequence); put("peer", peer)
                    put("job_id", job); put("type", record.event.getString("type")); put("fingerprint", record.fingerprint); put("envelope", record.raw)
                    put("logical_clock", record.logicalClock)
                })
            }
            index(db, record)
            previous = publication
        }
        require(entries.length() == 0 || previous == through)
        db.execSQL("INSERT OR REPLACE INTO mesh_fact_receive_progress VALUES(?,?,?,?)", arrayOf<Any?>(peer, local, source, through))
    }

    private fun pin(db: SQLiteDatabase, source: String, peer: String) {
        val owners = listOf("mesh_fact_owners", "source_event_receivers")
        for (table in owners) db.rawQuery("SELECT ${if (table == "mesh_fact_owners") "peer" else "owner_peer"} FROM $table WHERE source_id=?", arrayOf(source))
            .use { if (it.moveToFirst()) require(it.getString(0) == peer) { "source belongs to another authenticated device" } }
        db.execSQL("INSERT OR IGNORE INTO mesh_fact_owners VALUES(?,?)", arrayOf(source, peer))
    }

    /** Both replication profiles share origin ownership and collision rules,
     * even though their receive cursors have different meanings. */
    fun requireOwner(db: SQLiteDatabase, source: String, peer: String) {
        db.rawQuery("SELECT peer FROM mesh_fact_owners WHERE source_id=?", arrayOf(source)).use {
            if (it.moveToFirst()) require(it.getString(0) == peer) { "source belongs to another authenticated device" }
        }
    }

    fun checkOriginal(db: SQLiteDatabase, record: BufferSourceEvent) {
        for (table in listOf("source_events", "mesh_fact_originals")) db.rawQuery(
            "SELECT event_id,source_id,source_sequence,fingerprint,envelope FROM $table WHERE event_id=? OR (source_id=? AND source_sequence=?)",
            arrayOf(record.eventId, record.source, record.sequence.toString())).use { rows ->
            while (rows.moveToNext()) require(rows.getString(0) == record.eventId && rows.getString(1) == record.source &&
                rows.getLong(2) == record.sequence && rows.getString(3) == record.fingerprint && rows.getString(4) == record.raw) { "conflicting source original" }
        }
    }

    private fun validateRelations(db: SQLiteDatabase, record: BufferSourceEvent, peer: String) {
        val p = record.event.getJSONObject("payload")
        val type = record.event.getString("type")
        if (type !in setOf("MeshTaskResultReceived", "MeshTaskCancelled", "MeshTaskSourceResolved")) return
        val request = type == "MeshTaskSourceResolved"
        val id = p.getString(if (request) "request_event_id" else "offer_event_id")
        val prior = db.rawQuery("SELECT envelope FROM mesh_fact_originals WHERE event_id=?", arrayOf(id)).use {
            require(it.moveToFirst()) { "task fact predecessor is missing" }; BufferSourceEvent.decode(it.getString(0))
        }
        require(prior.source == record.source && prior.sequence < record.sequence &&
            prior.event.get("type") == if (request) "MeshTaskRequested" else "MeshTaskOffered")
        val contract = prior.event.getJSONObject("payload").getJSONObject("contract"); BufferMeshTaskContract.validate(contract)
        require(contract.get("job_id") == p.get("job_id"))
        if (request) {
            require(contract.get("source_device") == peer && p.get("status") in setOf("applied", "superseded", "failed"))
        } else {
            require(p.get("source_peer") == contract.get("source_device"))
            if (type == "MeshTaskResultReceived") {
                require(p.get("contract_sha256") == BufferMeshTaskContract.digest(contract))
                val grant = BufferMeshSourceTasks.grant(p.getJSONObject("grant"))
                require(grant.captureId == BufferMeshTaskContract.target(contract) && grant.stage == contract.get("stage") &&
                    grant.inputRevision == contract.getLong("input_revision") && grant.skillVersion == contract.get("skill_version"))
                BufferMeshExecution.validateResult(contract, p.getJSONObject("result"))
            }
        }
    }

    private fun index(db: SQLiteDatabase, record: BufferSourceEvent) {
        val job = validate(record); val p = record.event.getJSONObject("payload")
        val type = record.event.getString("type")
        val result = if (type in setOf("MeshTaskExecuted", "MeshTaskResultReceived")) p.getString("result_sha256") else null
        val outcome = if (type == "MeshTaskSourceResolved") p.getString("status") else null
        val stage = if (p.has("contract")) p.getJSONObject("contract").getString("stage")
            else if (p.has("grant")) p.getJSONObject("grant").getString("stage") else null
        db.execSQL("INSERT OR IGNORE INTO mesh_fact_history VALUES(?,?,?,?,?,?)", arrayOf(record.eventId, job, type, result, outcome, stage))
    }

    data class TaskHistory(val job: String, val facts: Int, val resultVariants: Int, val applied: Boolean, val stage: String?)
    fun history(db: SQLiteDatabase): List<TaskHistory> = db.rawQuery("""SELECT job_id,COUNT(*),COUNT(DISTINCT result_sha256),
        MAX(CASE WHEN outcome='applied' THEN 1 ELSE 0 END),MAX(stage) FROM mesh_fact_history
        GROUP BY job_id ORDER BY MAX(rowid) DESC LIMIT 12""", null).use { rows -> buildList {
        while (rows.moveToNext()) add(TaskHistory(rows.getString(0), rows.getInt(1), rows.getInt(2), rows.getInt(3) == 1, rows.getString(4)))
    } }

    fun counts(db: SQLiteDatabase): Map<String, Int> = db.rawQuery("SELECT peer,COUNT(*) FROM mesh_fact_originals GROUP BY peer", null).use { rows ->
        buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getInt(1)) }
    }
}
