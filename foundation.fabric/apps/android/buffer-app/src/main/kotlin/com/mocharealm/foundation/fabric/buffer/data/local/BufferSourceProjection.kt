package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCapture
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureInput
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.ClassificationReview
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Data-only remote projections. Never write legacy execution events, generate
 * local source facts, open payload paths or enqueue model/external action jobs.
 * The repository monitor and one SQLite transaction protect each batch. */
internal object BufferSourceProjection {
    private val types = setOf("CaptureCreated", "CaptureRecordUpdated", "CaptureTranscribed", "ClassificationProposed",
        "AssistantResultRecorded", "CardRecordChanged", "LegacyCaptureSnapshot")
    private val contentFields = listOf("kind", "title", "summary", "actions", "health_category", "related_card_ids")
    private data class Snapshot(val id: String, val created: Long, val mode: String, val transcript: String?,
        val processed: VelaProcessedCapture?)
    private data class Binding(val peer: String, val source: String, val sequence: Long, val baseline: JSONObject)

    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_projection_work ON source_event_projections(state,event_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_projection_details (
            event_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, outcome TEXT NOT NULL, capture_id TEXT, document_id TEXT)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_captures (
            capture_id TEXT PRIMARY KEY, owner_peer TEXT NOT NULL, source_id TEXT NOT NULL,
            source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990),
            baseline TEXT NOT NULL, binding_sha256 TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_cards (
            card_id TEXT PRIMARY KEY, capture_id TEXT NOT NULL, owner_peer TEXT NOT NULL, source_id TEXT NOT NULL,
            source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990),
            baseline TEXT NOT NULL, binding_sha256 TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_card_capture ON source_projection_cards(capture_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_processed (
            owner_peer TEXT NOT NULL, capture_id TEXT NOT NULL, record_sha256 TEXT NOT NULL, event_id TEXT NOT NULL,
            PRIMARY KEY(owner_peer,capture_id,record_sha256))""")
        createCrdt(db)
        BufferSourceObservations.create(db)
        BufferSourceEffects.create(db)
        BufferSourceLegacyMigration.create(db)
    }

    fun createCrdt(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS source_projection_capture_history ON source_event_projection_details(capture_id,event_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS source_projection_local_tombstones(card_id TEXT PRIMARY KEY)")
        db.execSQL("CREATE INDEX IF NOT EXISTS events_type_identity ON events(type,event_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_scan (
            kind TEXT PRIMARY KEY, after_event_id TEXT NOT NULL, complete INTEGER NOT NULL CHECK(complete IN (0,1)))""")
        listOf("local_deletions", "replay").forEach {
            db.execSQL("INSERT OR IGNORE INTO source_projection_scan(kind,after_event_id,complete) VALUES(?,'',0)", arrayOf(it))
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_projection_documents (
            owner_peer TEXT NOT NULL,source_id TEXT NOT NULL,document_id TEXT NOT NULL,event_id TEXT NOT NULL,
            PRIMARY KEY(owner_peer,source_id,document_id))""")
    }

    fun localFact(db: SQLiteDatabase, type: String, payload: JSONObject) {
        check(db.inTransaction())
        if (type != "CardDeleted") return
        val id = payload.get("card_id"); require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        db.execSQL("INSERT OR IGNORE INTO source_projection_local_tombstones(card_id) VALUES(?)", arrayOf(id))
    }
    fun localHistoryReady(db: SQLiteDatabase): Boolean = db.rawQuery("SELECT complete FROM source_projection_scan WHERE kind='local_deletions'", null)
        .use { check(it.moveToFirst()); it.getInt(0) == 1 }
    fun locallyDeleted(db: SQLiteDatabase, id: String): Boolean = db.rawQuery("SELECT 1 FROM source_projection_local_tombstones WHERE card_id=?", arrayOf(id))
        .use { it.moveToFirst() }

    private fun scan(db: SQLiteDatabase, kind: String): Boolean {
        val cursor = db.rawQuery("SELECT after_event_id,complete FROM source_projection_scan WHERE kind=?", arrayOf(kind))
            .use { check(it.moveToFirst()); it.getString(0) to (it.getInt(1) == 1) }
        if (cursor.second) return false
        val query = if (kind == "local_deletions") "SELECT event_id,payload FROM events WHERE type='CardDeleted' AND event_id>? ORDER BY event_id LIMIT 32"
            else "SELECT event_id FROM source_event_projections WHERE event_id>? ORDER BY event_id LIMIT 32"
        val ids = db.rawQuery(query, arrayOf(cursor.first)).use { rows -> buildList {
            while (rows.moveToNext()) {
                add(rows.getString(0))
                if (kind == "local_deletions") localFact(db, "CardDeleted", JSONObject(rows.getString(1)))
                else db.execSQL("UPDATE source_event_projections SET state='pending' WHERE event_id=?", arrayOf(rows.getString(0)))
            }
        } }
        check(db.update("source_projection_scan", ContentValues().apply {
            put("after_event_id", ids.lastOrNull() ?: cursor.first); put("complete", if (ids.size < 32) 1 else 0)
        }, "kind=?", arrayOf(kind)) == 1)
        return ids.size == 32
    }

    /** Compare source-created SQL views with their original baseline, so
     * inherited source values do not receive fabricated local actor stamps. */
    fun localChanges(db: SQLiteDatabase, id: String, values: JSONObject): JSONObject {
        val old = binding(db, "source_projection_cards", "card_id", id) ?: return values
        val expected = JSONObject(old.baseline.toString())
        listOf("actions", "related_card_ids").forEach { expected.put(it, JSONArray(expected.getString(it))) }
        expected.put("text", expected.get("task_source"))
        expected.put("presentation", if (expected.isNull("presentation")) JSONObject.NULL else JSONObject(expected.getString("presentation")))
        expected.put("classification_confirmed", false).put("answer", JSONObject.NULL).put("research_notes", JSONObject.NULL)
            .put("tags", JSONArray()).put("external_links", JSONArray()).put("conversation", JSONArray())
            .put("bedtime_review", JSONObject.NULL).put("bedtime_user_review", JSONObject.NULL)
            .put("capture_context", JSONObject.NULL).put("context_summary", "")
            .put("display_return", JSONObject().put("hidden_until", 0).put("returned_at", 0))
        // A cached schedule may be a read-only text inference. Explicit user
        // schedule changes already export a CRDT in their own transaction;
        // do not invent a local edit from this cache on first source replay.
        return JSONObject().also { changes -> expected.keys().forEach { field ->
            if (values.has(field) && BufferCrdt.canonical(values.get(field)) != BufferCrdt.canonical(expected.get(field))) changes.put(field, values.get(field))
        } }
    }

    fun documentApplied(db: SQLiteDatabase, peer: String, record: BufferSourceEvent, document: JSONObject) {
        check(db.inTransaction())
        val old = db.rawQuery("""SELECT e.source_sequence FROM source_projection_documents d
            LEFT JOIN source_events e ON e.event_id=d.event_id AND e.source_id=d.source_id
            WHERE d.owner_peer=? AND d.source_id=? AND d.document_id=?""", arrayOf(peer, record.source, document.getString("id"))).use {
            if (!it.moveToFirst()) null else { check(!it.isNull(0)); it.getLong(0) }
        }
        if (old != null && old > record.sequence) return
        db.execSQL("""INSERT OR REPLACE INTO source_projection_documents(owner_peer,source_id,document_id,event_id) VALUES(?,?,?,?)""",
            arrayOf(peer, record.source, document.getString("id"), record.eventId))
    }

    /** A lost queue row is derived state, not a reason to lose an accepted
     * original. Recreate only owner-bound foreign work; never executor jobs. */
    fun ensureQueued(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        db.execSQL("""INSERT OR IGNORE INTO source_event_projections(event_id,state)
            SELECT e.event_id,'pending' FROM source_events e JOIN source_event_receivers r ON r.source_id=e.source_id
            WHERE e.event_id=?""",arrayOf(id))
        db.execSQL("""UPDATE source_event_projections SET state='pending' WHERE event_id=? AND
            (typeof(state)<>'text' OR state NOT IN ('pending','waiting_local_history','corrupt_original','waiting_history',
                'unsupported_schema','blocked_parent','invalid_payload','conflict','observed_only','partial','applied','unsupported_type','unsupported_capacity'))
            AND EXISTS(SELECT 1 FROM source_events e JOIN source_event_receivers r ON r.source_id=e.source_id WHERE e.event_id=source_event_projections.event_id)""",arrayOf(id))
    }

    /** Metadata reconciliation wakes partial projections when an ancestor
     * changes. Fan-out remains bounded by BufferEventHistory's existing queue. */
    fun historyChanged(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        db.execSQL("""UPDATE source_event_projections SET state='pending' WHERE event_id=?
            AND state IN ('waiting_history','partial','blocked_parent','applied','corrupt_original')""", arrayOf(id))
        BufferSourceEffects.invalidated(db, id)
    }

    fun represented(db: SQLiteDatabase, peer: String, capture: String, hash: String): Boolean {
        val location = db.rawQuery("""SELECT e.source_id,e.source_sequence,p.event_id FROM source_projection_processed p JOIN source_event_projection_details d ON d.event_id=p.event_id
            JOIN source_events e ON e.event_id=p.event_id JOIN source_event_receivers r ON r.source_id=e.source_id
            JOIN source_event_history h ON h.event_id=e.event_id AND h.fingerprint=e.fingerprint
            WHERE h.state IN ('linked','legacy','missing_parent','waiting_parent') AND p.owner_peer=? AND p.capture_id=? AND p.record_sha256=? AND r.owner_peer=p.owner_peer
            AND e.fingerprint=d.fingerprint AND d.outcome IN ('applied','local_override','legacy_preserved','superseded') LIMIT 1""",
            arrayOf(peer, capture, hash)).use { if (it.moveToFirst()) Triple(it.getString(0), it.getLong(1), it.getString(2)) else null } ?: return false
        val record = BufferEventJournal.received(db, peer, location.first, location.second) { true }
        check(record.eventId == location.third && record.event.getString("capture_id") == capture)
        check(VelaProcessedCaptureProtocol.fingerprint(record.event.getJSONObject("payload").getString("record_json")) == hash)
        return true
    }

    /** Legacy snapshots can arrive between the source's pending and ready
     * facts. Complete only our own untouched capture row; the caller still
     * commits its separate legacy receipt and reconstructed local facts. */
    fun completeLegacyCapture(db: SQLiteDatabase, peer: String, value: VelaProcessedCapture): Boolean {
        check(db.inTransaction())
        val bound = binding(db, "source_projection_captures", "capture_id", value.captureId) ?: return false
        if (bound.peer != peer || bound.sequence == 0L) return false
        val record = BufferEventJournal.received(db, peer, bound.source, bound.sequence) { true }
        check(record.event.getString("capture_id") == value.captureId)
        val current = fields(db, "captures", "capture_id", value.captureId,
            listOf("source_device", "created_at", "mode", "transcript", "classification")) ?: return false
        if (current.getString("source_device") != peer || current.getString("created_at") != value.createdAt.toString() || current.getString("mode") != value.mode) return false
        val next = JSONObject().put("transcript", value.transcript).put("classification", value.segments.joinToString("|") { it.proposal.kind })
        if (listOf("transcript", "classification").any { !same(current, bound.baseline, it) && !same(current, next, it) }) return false
        check(db.update("captures", sqlValues(next, listOf("transcript", "classification")), "capture_id=?", arrayOf(value.captureId)) == 1)
        return true
    }

    private fun bindingHash(key: String, id: String, peer: String, source: String, sequence: Long, baseline: JSONObject): String =
        VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(JSONObject().put("key", key).put("id", id).put("peer", peer)
            .put("source", source).put("sequence", sequence).put("baseline", baseline)))
    fun inspectBinding(db: SQLiteDatabase, table: String, id: String) {
        val key = when (table) {
            "source_projection_captures" -> "capture_id"
            "source_projection_cards" -> "card_id"
            else -> error("unsupported binding table")
        }
        binding(db, table, key, id)
    }
    private fun binding(db: SQLiteDatabase, table: String, key: String, id: String): Binding? {
        val identity = JSONObject().put(key, id)
        BufferSourceMetadata.checkRows(db, table, "$key=?", arrayOf(id))
        try {
            val live = db.query(table, arrayOf("owner_peer", "source_id", "source_sequence", "baseline", "binding_sha256"), "$key=?", arrayOf(id), null, null, null).use {
                if (!it.moveToFirst()) null else JSONObject().put("owner_peer", it.getString(0)).put("source_id", it.getString(1))
                    .put("source_sequence", it.getLong(2)).put("baseline", it.getString(3)).put("binding_sha256", it.getString(4))
            }
            val saved = live ?: BufferSourceEffects.retainedRow(db, table, JSONObject().put(key, id)) ?: return null
            val peer = saved.getString("owner_peer"); val source = saved.getString("source_id"); val sequence = saved.getLong("source_sequence")
            val baseline = BufferSourceEvent.parseObject(saved.getString("baseline"), BufferSourceEvent.MAX_BYTES)
            check(peer.matches(Regex("[0-9a-f]{64}")) && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")) && sequence in 1L until BufferSourceEvent.MAX_COUNTER)
            check(saved.getString("binding_sha256") == bindingHash(key, id, peer, source, sequence, baseline))
            val adjusted = if (live != null) baseline else BufferSourceEffects.comparisonBaseline(db,
                if (table == "source_projection_cards") "cards" else "captures", JSONObject().put(key, id), baseline)
            return Binding(peer, source, if (live == null) 0L else sequence, adjusted)
        } catch (error: BufferSourceMetadata.Damaged) { throw error }
          catch (_: IllegalArgumentException) { throw BufferSourceMetadata.Damaged(table, identity) }
          catch (_: IllegalStateException) { throw BufferSourceMetadata.Damaged(table, identity) }
          catch (_: JSONException) { throw BufferSourceMetadata.Damaged(table, identity) }
    }
    private fun setting(db: SQLiteDatabase, key: String): String? = db.rawQuery("SELECT value FROM runtime_settings WHERE key=?", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0) else null }
    private fun setting(db: SQLiteDatabase, key: String, value: String?) {
        if (value == null) db.delete("runtime_settings", "key=?", arrayOf(key))
        else db.insertWithOnConflict("runtime_settings", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    private fun integer(j: JSONObject, name: String): Long {
        val n = j.get(name); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in 0..BufferSourceEvent.MAX_COUNTER)
        return n.toLong()
    }
    private fun snapshot(db: SQLiteDatabase, record: BufferSourceEvent): Snapshot? {
        val event = record.event; val payload = event.getJSONObject("payload")
        if (event.getString("type") !in types) return null
        if (!payload.has("record_json")) return androidSnapshot(db,record)
        require(payload.keys().asSequence().toSet() == setOf("record_json"))
        val raw = payload.get("record_json"); require(raw is String)
        val value = BufferSourceEvent.parseObject(raw, 65536)
        val id = value.get("capture_id"); require(id is String && id.matches(Regex("[A-Za-z0-9_-]{1,79}")) && event.get("capture_id") == id)
        val mode = value.get("mode"); require(mode is String && mode in setOf("normal", "focus", "bedtime", "rabbit_hole"))
        val created = integer(value, "created_at")
        val transcript = if (value.has("transcript") && !value.isNull("transcript")) value.get("transcript").also {
            require(it is String && '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size <= 8192)
        } as String else null
        // Assistant snapshots may contain timer/focus/followup instructions.
        // They need their own typed, data-only adapters; never reuse an executor.
        if (value.has("target")) require(value.get("target") in setOf("buffer", "auto", "assistant"))
        if (value.optString("target") == "assistant" || value.optString("state") == "assistant_complete") return null
        val complete = value.get("state") == "ready"
        if (!complete) require(value.get("state") in setOf("pending", "pending_transcription", "pending_classification", "transcribing", "transcribed", "classifying", "failed", "error"))
        val processed = if (complete) VelaProcessedCaptureProtocol.decode(VelaProcessedCaptureProtocol.encode(raw)) else null
        require(processed == null || (processed.followup == null && processed.focusSession == null))
        return Snapshot(id, created, mode, transcript, processed)
    }

    /** Android captures use native fields rather than Vela's record_json.
     * Materialize only data; source paths, transcription requests and inferred
     * classifications never become local files or runnable work. */
    private fun androidSnapshot(db: SQLiteDatabase, record: BufferSourceEvent): Snapshot? {
        val event=record.event;val p=event.getJSONObject("payload");val type=event.getString("type")
        if(type !in setOf("CaptureCreated","CaptureTranscribed") || !p.has("capture_id"))return null
        val id=p.get("capture_id");require(id is String && id.matches(Regex("[A-Za-z0-9_-]{1,79}")) && event.get("capture_id")==id)
        if(type=="CaptureTranscribed") {
            val sequence=db.rawQuery("""SELECT source_sequence FROM source_events WHERE source_id=? AND capture_id=? AND source_sequence<?
                AND CASE WHEN json_valid(envelope) THEN json_extract(envelope,'$.event.type') END='CaptureCreated'
                ORDER BY source_sequence DESC LIMIT 1""",arrayOf(record.source,id,record.sequence.toString())).use {if(it.moveToFirst())it.getLong(0)else null} ?: return null
            val peer=db.rawQuery("SELECT owner_peer FROM source_event_receivers WHERE source_id=?",arrayOf(record.source)).use {check(it.moveToFirst());it.getString(0)}
            val original=BufferEventJournal.received(db,peer,record.source,sequence){true}
            val baseline=androidSnapshot(db,original) ?: return null
            val transcript=p.get("transcript");require(transcript is String && '\u0000' !in transcript && transcript.toByteArray().size<=32768)
            return baseline.copy(transcript=transcript)
        }
        if(p.opt("source")!="android" && !p.has("audio_format"))return null
        val mode=p.get("mode");require(mode is String && mode in setOf("normal","focus","bedtime","rabbit_hole"))
        val transcript=if(p.has("text"))p.get("text").also {require(it is String && '\u0000' !in it && it.toByteArray().size<=32768)} as String else null
        return Snapshot(id,event.getLong("created_at"),mode,transcript,null)
    }

    private fun fields(db: SQLiteDatabase, table: String, key: String, id: String, names: List<String>): JSONObject? =
        db.query(table, names.toTypedArray(), "$key=?", arrayOf(id), null, null, null).use { row ->
            if (!row.moveToFirst()) null else JSONObject().also { j -> names.forEachIndexed { i, name ->
                j.put(name, if (row.isNull(i)) JSONObject.NULL else row.getString(i))
            } }
        }
    private fun same(left: JSONObject, right: JSONObject, name: String) = left.has(name) && right.has(name) && left.get(name) == right.get(name)
    private fun sqlValues(value: JSONObject, names: List<String>) = ContentValues().apply {
        names.forEach { key -> if (value.isNull(key)) putNull(key) else put(key, value.getString(key)) }
    }
    private fun saveBinding(db: SQLiteDatabase, table: String, key: String, id: String, peer: String,
                            record: BufferSourceEvent, baseline: JSONObject, capture: String? = null) {
        val values = ContentValues().apply {
            put(key, id); put("owner_peer", peer); put("source_id", record.source); put("source_sequence", record.sequence); put("baseline", baseline.toString())
            put("binding_sha256", bindingHash(key, id, peer, record.source, record.sequence, baseline))
            if (capture != null) put("capture_id", capture)
        }
        check(db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    private fun deleted(db: SQLiteDatabase, id: String): Boolean =
        locallyDeleted(db, id) || BufferSourceEffects.currentDocument(db, id)?.getBoolean("deleted") == true

    /** Only a currently authorized live input caller may adopt a disposable
     * history view. Originals and projection bindings stay intact; an actual
     * native fact, card, job or intervening edit forbids this handover. */
    data class PendingAdoption(val sourceEvent: String, val fingerprint: String, val transcript: String?)
    fun releasePendingCapture(db: SQLiteDatabase, peer: String, input: VelaPendingCaptureInput): PendingAdoption? {
        check(db.inTransaction())
        if (!localHistoryReady(db) || BufferSourceEffects.undoing(db) || BufferSourceLegacyMigration.active(db)) return null
        val cardId = com.mocharealm.foundation.fabric.buffer.data.device.VelaPendingCaptureProtocol.deterministicCardId(input.captureId, 0)
        if (deleted(db, cardId)) return null
        val bound = binding(db, "source_projection_captures", "capture_id", input.captureId) ?: return null
        if (bound.peer != peer || bound.sequence <= 0) return null
        val actual = fields(db, "captures", "capture_id", input.captureId,
            listOf("source_device", "created_at", "mode", "transcript", "classification", "audio_format", "duration_ms", "blob_path")) ?: return null
        if (actual.getString("source_device") != peer || actual.getString("created_at") != input.createdAt.toString() ||
            actual.getString("mode") != input.mode || !actual.isNull("audio_format") || !actual.isNull("blob_path") || actual.getString("duration_ms") != "0" ||
            !same(actual, bound.baseline, "transcript") || !same(actual, bound.baseline, "classification")) return null
        if (db.rawQuery("""SELECT 1 FROM cards WHERE capture_id=?
            UNION ALL SELECT 1 FROM classification_jobs WHERE capture_id=?
            UNION ALL SELECT 1 FROM events WHERE capture_id=? AND type IN
                ('CaptureCreated','CaptureTranscribed','ClassificationProposed','VelaPendingCaptureImported','VelaProcessedCaptureImported') LIMIT 1""",
                arrayOf(input.captureId,input.captureId,input.captureId)).use { it.moveToFirst() }) return null
        val record = BufferEventJournal.received(db, peer, bound.source, bound.sequence) { true }
        if (!record.event.getJSONObject("payload").has("record_json")) return null
        val view = snapshot(db,record) ?: return null
        if (view.id != input.captureId || view.created != input.createdAt || view.mode != input.mode || view.processed != null) return null
        val raw = BufferSourceEvent.parseObject(record.event.getJSONObject("payload").getString("record_json"), 65536)
        if (raw.optString("target", "buffer") != input.target) return null
        if (input.contentType == "audio/wav" && view.transcript != null) {
            if (view.transcript.isBlank() || !db.rawQuery(
                "SELECT 1 FROM source_event_history WHERE event_id=? AND fingerprint=? AND state IN ('linked','legacy')",
                arrayOf(record.eventId,record.fingerprint)).use { it.moveToFirst() }) return null
            // The transcript snapshot and the recording must refer to the
            // same actual source Capture, not just a matching cache row.
            val location = db.rawQuery("""SELECT e.source_sequence FROM source_event_blob_receivers b
                JOIN source_events e ON e.event_id=b.event_id AND e.fingerprint=b.record_sha256
                JOIN source_event_history h ON h.event_id=e.event_id AND h.fingerprint=e.fingerprint AND h.state IN ('linked','legacy')
                WHERE b.owner_peer=? AND e.source_id=? AND e.capture_id=? AND e.source_sequence<=?
                    AND b.blob_sha256=? AND b.total=? AND b.content_type=? ORDER BY e.source_sequence DESC LIMIT 1""",
                arrayOf(peer,bound.source,input.captureId,bound.sequence.toString(),input.recordSha256,
                    input.recordBytes.size.toString(),input.contentType)).use { if(it.moveToFirst())it.getLong(0)else null } ?: return null
            val audio = BufferEventJournal.received(db,peer,bound.source,location) { true }
            val ref = BufferSourceBlobs.originalTransfer(audio)
            val blob = audio.event.getJSONObject("payload").getJSONObject("blob")
            if (audio.event.optString("capture_id") != input.captureId || ref.blobHash != input.recordSha256 ||
                ref.total != input.recordBytes.size || ref.contentType != input.contentType ||
                blob.getLong("created_at") != input.createdAt || blob.getLong("duration_ms") != input.durationMs || blob.getString("mode") != input.mode) return null
        } else if (input.contentType != "audio/wav" && view.transcript?.trim() != input.recordBytes.toString(Charsets.UTF_8).trim()) return null
        check(db.delete("captures", "capture_id=?", arrayOf(input.captureId)) == 1)
        return PendingAdoption(record.eventId, record.fingerprint, if (input.contentType == "audio/wav") view.transcript else null)
    }

    private fun apply(db: SQLiteDatabase, peer: String, record: BufferSourceEvent, value: Snapshot): String {
        val previous = binding(db, "source_projection_captures", "capture_id", value.id)
        if (previous != null && (previous.peer != peer || previous.source != record.source)) return "conflict"
        val captureFields = listOf("source_device", "created_at", "mode", "transcript", "classification")
        val capture = fields(db, "captures", "capture_id", value.id, captureFields)
        if (capture != null && (capture.getString("source_device") != peer || capture.getString("created_at") != value.created.toString() ||
                capture.getString("mode") != value.mode)) return "conflict"
        val processed = value.processed
        // Preflight every entity before any business write. A collision must
        // not leave half a capture committed while reporting a conflict.
        processed?.segments?.forEach { segment ->
            val old = fields(db, "cards", "card_id", segment.cardId, listOf("capture_id"))
            val bound = binding(db, "source_projection_cards", "card_id", segment.cardId)
            if (old != null && old.opt("capture_id") != value.id) return "conflict"
            if (bound != null && (bound.peer != peer || bound.source != record.source)) return "conflict"
        }
        if (previous != null && record.sequence < previous.sequence) return "superseded"
        var overridden = false
        var legacy = capture != null && previous == null
        val baseline = JSONObject().put("transcript", value.transcript ?: JSONObject.NULL)
            .put("classification", processed?.segments?.joinToString("|") { it.proposal.kind } ?: JSONObject.NULL)
        if (capture == null) {
            db.insertOrThrow("captures", null, sqlValues(baseline, listOf("transcript", "classification")).apply {
                put("capture_id", value.id); put("source_device", peer); put("created_at", value.created); put("mode", value.mode)
                putNull("audio_format"); put("duration_ms", 0L); putNull("blob_path")
            })
        } else if (previous != null) {
            val updates = ContentValues()
            listOf("transcript", "classification").forEach { name ->
                if (same(capture, previous.baseline, name)) updates.putAll(sqlValues(baseline, listOf(name))) else overridden = true
            }
            if (updates.size() > 0) check(db.update("captures", updates, "capture_id=?", arrayOf(value.id)) == 1)
        }
        processed?.segments?.forEach { segment ->
            val p = segment.proposal
            val next = JSONObject().put("kind", p.kind).put("title", p.title).put("summary", p.summary)
                .put("state", when (segment.state) { "active" -> ClassificationReview.initialState(p.kind)
                    "reminder_pending" -> "reminder_requested"; "research_requested" -> "rabbit_hole_candidate"; else -> segment.state })
                .put("actions", JSONArray(ClassificationReview.proposalActions(p.kind, p.healthCategory)).toString())
                .put("health_category", p.healthCategory ?: JSONObject.NULL).put("related_card_ids", JSONArray(p.relatedCardIds).toString())
                .put("task_source", p.text).put("presentation", p.presentation ?: JSONObject.NULL)
            val old = fields(db, "cards", "card_id", segment.cardId, contentFields + listOf("state", "classification_confirmed"))
            val bound = binding(db, "source_projection_cards", "card_id", segment.cardId)
            val tombstone = deleted(db, segment.cardId)
            if (setting(db, "crdt:${segment.cardId}") != null) {
                // A register document is the authority for this card once it
                // exists. Raw capture snapshots remain history and must not
                // overwrite merged registers through a second projection path.
                overridden = true
            } else if (old == null && !tombstone) {
                db.insertOrThrow("cards", null, sqlValues(next, contentFields + "state").apply {
                    put("card_id", segment.cardId); put("capture_id", value.id); put("created_at", value.created); put("classification_confirmed", 0)
                })
                setting(db, "task_source:${segment.cardId}", p.text); setting(db, "card_presentation:${segment.cardId}", p.presentation)
            } else if (old != null && bound != null) {
                val updates = ContentValues()
                val unchanged = contentFields.all { same(old, bound.baseline, it) } && old.getString("classification_confirmed") == "0"
                if (old.getString("classification_confirmed") == "0" || old.getString("state") == "source_unavailable") {
                    for (field in contentFields) if (same(old, bound.baseline, field)) updates.putAll(sqlValues(next, listOf(field))) else overridden = true
                } else overridden = true
                if (old.getString("state") == "deleted" || tombstone) updates.put("state", "deleted")
                else if (same(old, bound.baseline, "state")) updates.put("state", next.getString("state")) else overridden = true
                if (updates.size() > 0) check(db.update("cards", updates, "card_id=?", arrayOf(segment.cardId)) == 1)
                listOf("task_source" to "task_source:", "presentation" to "card_presentation:").forEach { (field, prefix) ->
                    val current = setting(db, "$prefix${segment.cardId}") ?: JSONObject.NULL
                    if (unchanged && bound.baseline.has(field) && current == bound.baseline.get(field))
                        setting(db, "$prefix${segment.cardId}", if (next.isNull(field)) null else next.getString(field))
                    else overridden = true
                }
            } else if (old != null) legacy = true
            else if (tombstone) overridden = true
            if (old != null && (segment.state == "deleted" || tombstone || old.getString("state") == "deleted")) {
                setting(db, "crdt:${segment.cardId}")?.let { encoded ->
                    val doc = BufferSourceEvent.parseObject(encoded, 65536); BufferCrdt.validate(doc)
                    doc.put("deleted", true); setting(db, "crdt:${segment.cardId}", BufferCrdt.canonical(doc))
                }
                check(db.update("cards", ContentValues().apply { put("state", "deleted") }, "card_id=?", arrayOf(segment.cardId)) == 1)
                db.delete("card_returns", "card_id=?", arrayOf(segment.cardId))
                db.execSQL("UPDATE card_reminders SET cancelled=1 WHERE card_id=?", arrayOf(segment.cardId))
            }
            // A source or local tombstone is never converted back to active.
            // No reminder/return job is created by these state projections.
            saveBinding(db, "source_projection_cards", "card_id", segment.cardId, peer, record, next, value.id)
            if (old?.optString("state") == "source_unavailable")
                BufferSourceEffects.restoreQuarantine(db, "cards", JSONObject().put("card_id", segment.cardId), next)
            BufferSourceEffects.applyNativeOverrides(db,segment.cardId)
        }
        saveBinding(db, "source_projection_captures", "capture_id", value.id, peer, record, baseline)
        BufferSourceEffects.restoreQuarantine(db, "captures", JSONObject().put("capture_id", value.id), baseline)
        return if (legacy) "legacy_preserved" else if (overridden) "local_override" else "applied"
    }

    private fun legacyCheckpoint(db: SQLiteDatabase, table: String, key: String, id: String): Binding? {
        val live=db.rawQuery("SELECT 1 FROM $table WHERE $key=?",arrayOf(id)).use { it.moveToFirst() }
        if(!live)return null
        val bound=requireNotNull(binding(db,table,key,id));check(bound.sequence>0)
        val corrupt=db.rawQuery("""SELECT 1 FROM source_events e JOIN source_event_history h ON h.event_id=e.event_id
            WHERE e.source_id=? AND e.source_sequence=? AND h.state='corrupt' LIMIT 1""",arrayOf(bound.source,bound.sequence.toString())).use { it.moveToFirst() }
        // A corrupt original cannot restore a view. Its hash-checked old
        // binding is still usable to remove the old derived contribution.
        if(!corrupt)BufferEventJournal.received(db,bound.peer,bound.source,bound.sequence){true}
        return bound
    }
    fun legacyScalarFields(db: SQLiteDatabase,id: String): JSONObject? {
        val baseline=legacyCheckpoint(db,"source_projection_cards","card_id",id)?.baseline?:return null
        return JSONObject(baseline.toString()).also { fields ->
            for(name in listOf("actions","related_card_ids"))fields.put(name,JSONArray(fields.getString(name)))
            fields.put("text",fields.get("task_source"))
            fields.put("presentation",if(fields.isNull("presentation"))JSONObject.NULL else JSONObject(fields.getString("presentation")))
            fields.put("classification_confirmed",false).put("answer",JSONObject.NULL).put("research_notes",JSONObject.NULL)
                .put("tags",JSONArray()).put("external_links",JSONArray()).put("conversation",JSONArray())
                .put("capture_context",JSONObject.NULL).put("context_summary","")
        }
    }
    fun resetLegacyDocument(db: SQLiteDatabase,id: String,native: JSONObject?,applyNative:(JSONObject)->Unit): Boolean {
        val identity=JSONObject().put("card_id",id)
        val bound=legacyCheckpoint(db,"source_projection_cards","card_id",id)
        val damaged=bound==null&&BufferSourceMetadata.pending(db,"document",id)
        val current=BufferSourceEffects.currentDocument(db,id)
        val deleted=current?.getBoolean("deleted")==true
        BufferSourceEffects.archiveLegacyRow(db,"cards",identity)
        BufferSourceEffects.archiveLegacyRow(db,"source_projection_cards",identity)
        if(current!=null)BufferSourceEffects.archiveLegacyRow(db,"runtime_settings",JSONObject().put("key","crdt:$id"))
        val base=native?.let { JSONObject(it.toString()) }
        if(base!=null&&locallyDeleted(db,id))base.put("deleted",true)
        val complete=base!=null&&runCatching { com.mocharealm.foundation.fabric.buffer.domain.BufferCrdtView.validate(base) }.isSuccess
        setting(db,"crdt:$id",null)
        if(base!=null)BufferSourceEffects.retainLegacyDocument(db,base)
        val expected=bound?.baseline?.let { JSONObject(it.toString()) }?:JSONObject()
        val sourceFields=current?.getJSONObject("fields")
        val sqlFields=setOf("kind","title","summary","state","actions","answer","research_notes","health_category","classification_confirmed","external_links","tags","related_card_ids")
        val actual=fields(db,"cards","card_id",id,sqlFields.toList())
        if(sourceFields!=null&&actual!=null)for(field in sourceFields.keys()){
            if(field !in sqlFields)continue
            val register=sourceFields.getJSONObject(field)
            val foreign=db.rawQuery("SELECT 1 FROM source_projection_legacy_foreign_fields WHERE document_id=? AND field_name=? AND register_sha256=?",
                arrayOf(id,field,VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(register)))).use { it.moveToFirst() }
            if(foreign)expected.put(field,actual.get(field))
        }
        val prefs=mapOf("text" to "task_source:","schedule" to "task_schedule:","presentation" to "card_presentation:",
            "conversation" to "card_conversation:","bedtime_review" to "bedtime_review:","bedtime_user_review" to "bedtime_user_review:","capture_context" to "shared_context:")
        for((field,prefix)in prefs){
            val key=prefix+id;val value=setting(db,key)?:continue
            val register=sourceFields?.optJSONObject(field)
            val foreign=register!=null&&db.rawQuery("SELECT 1 FROM source_projection_legacy_foreign_fields WHERE document_id=? AND field_name=? AND register_sha256=?",
                arrayOf(id,field,VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(register)))).use { it.moveToFirst() }
            val scalar=when(field){"text"->bound?.baseline?.opt("task_source");"presentation"->bound?.baseline?.opt("presentation");else->null}
            val matches=register?.let { BufferCrdt.canonical(it.get("value"))==runCatching { BufferCrdt.canonical(org.json.JSONTokener(value).nextValue()) }.getOrDefault(BufferCrdt.canonical(value)) }==true
            if(damaged||(foreign&&matches)||(scalar!=null&&scalar!=JSONObject.NULL&&scalar.toString()==value)){
                BufferSourceEffects.archiveLegacyRow(db,"runtime_settings",JSONObject().put("key",key));setting(db,key,null)
            }
        }
        if(complete){
            applyNative(requireNotNull(base));BufferSourceEffects.applyNativeOverrides(db,id)
            BufferSourceEffects.restoreLegacyCardChanges(db,id)
        }
        // With no verifiable binding, current SQL is only a private archive;
        // it cannot establish a native override, even if marked confirmed.
        else if(actual!=null&&!damaged)BufferSourceEffects.quarantineLegacyCard(db,id,expected)
        if(damaged&&!complete&&!locallyDeleted(db,id)) {
            // An unverifiable old row remains in private recovery storage.
            // The actual native register evidence above and source replay,
            // rather than a guessed before-image, rebuild the live card.
            db.execSQL("UPDATE card_reminders SET cancelled=1 WHERE card_id=?",arrayOf(id))
            db.delete("card_returns","card_id=?",arrayOf(id))
            if(deleted) {
                setting(db,"crdt:$id",BufferCrdt.canonical(requireNotNull(current)))
                db.update("cards",ContentValues().apply { put("state","deleted");put("actions","[]") },"card_id=?",arrayOf(id))
            } else db.delete("cards","card_id=?",arrayOf(id))
        }
        db.delete("source_projection_cards","card_id=?",arrayOf(id))
        db.execSQL("DELETE FROM source_projection_documents WHERE rowid IN (SELECT rowid FROM source_projection_documents WHERE document_id=? LIMIT 32)",arrayOf(id))
        return db.rawQuery("SELECT 1 FROM source_projection_documents WHERE document_id=? LIMIT 1",arrayOf(id)).use { it.moveToFirst() }
    }
    fun resetLegacyCapture(db: SQLiteDatabase,id: String) {
        val bound=legacyCheckpoint(db,"source_projection_captures","capture_id",id)
        if(bound==null) {
            if(!BufferSourceMetadata.pending(db,"capture",id))return
            val identity=JSONObject().put("capture_id",id)
            val owner=db.rawQuery("SELECT source_device FROM captures WHERE capture_id=?",arrayOf(id))
                .use { if(it.moveToFirst())it.getString(0)else null }?:return
            // A broken projection binding cannot turn a native Capture into
            // a disposable remote view. Only an actual pinned peer is reset.
            if(!owner.matches(Regex("[0-9a-f]{64}"))||!db.rawQuery(
                "SELECT 1 FROM source_event_receivers WHERE owner_peer=? LIMIT 1",arrayOf(owner)).use { it.moveToFirst() })return
            BufferSourceEffects.archiveLegacyRow(db,"captures",identity)
            val native=db.rawQuery("""SELECT e.event_id FROM source_events e JOIN source_event_history h
                ON h.event_id=e.event_id AND h.fingerprint=e.fingerprint AND h.state IN ('linked','legacy')
                WHERE e.source_id=? AND e.capture_id=? AND EXISTS(
                    SELECT 1 FROM events n WHERE n.event_id=e.event_id AND n.type='CaptureTranscribed')
                ORDER BY e.source_sequence DESC LIMIT 1""",arrayOf(BufferEventJournal.source(db),id)).use { if(it.moveToFirst())it.getString(0)else null }
            val changes=JSONObject()
            native?.let { changes.put("transcript",BufferEventJournal.localRecord(db,it).event.getJSONObject("payload").getString("transcript")) }
            BufferSourceEffects.retainLegacyCaptureBaseline(db,id,
                JSONObject().put("transcript",JSONObject.NULL).put("classification",JSONObject.NULL),changes)
            check(db.delete("captures","capture_id=? AND source_device=?",arrayOf(id,owner))==1)
            return
        }
        val identity=JSONObject().put("capture_id",id)
        BufferSourceEffects.archiveLegacyRow(db,"captures",identity)
        BufferSourceEffects.archiveLegacyRow(db,"source_projection_captures",identity)
        val current=fields(db,"captures","capture_id",id,listOf("transcript","classification"))
        val update=ContentValues()
        if(current!=null){
            val native=db.rawQuery("""SELECT e.event_id FROM source_events e JOIN source_event_history h
                ON h.event_id=e.event_id AND h.fingerprint=e.fingerprint AND h.state IN ('linked','legacy') WHERE e.source_id=? AND e.capture_id=?
                AND EXISTS(SELECT 1 FROM events n WHERE n.event_id=e.event_id AND n.type='CaptureTranscribed')
                ORDER BY e.source_sequence DESC LIMIT 1""",arrayOf(BufferEventJournal.source(db),id)).use { if(it.moveToFirst())it.getString(0)else null }
            val transcript=native?.let { BufferEventJournal.localRecord(db,it).event.getJSONObject("payload").getString("transcript") }
            if(same(current,bound.baseline,"transcript")){if(transcript==null)update.putNull("transcript")else update.put("transcript",transcript)}
            if(same(current,bound.baseline,"classification"))update.putNull("classification")
            if(update.size()>0)db.update("captures",update,"capture_id=?",arrayOf(id))
            val reset=requireNotNull(fields(db,"captures","capture_id",id,listOf("transcript","classification")))
            val changes=JSONObject()
            for(field in listOf("transcript","classification"))if(!same(current,bound.baseline,field))changes.put(field,current.get(field))
            if(transcript!=null&&!changes.has("transcript"))changes.put("transcript",transcript)
            BufferSourceEffects.retainLegacyCaptureBaseline(db,id,reset,changes)
        }
        db.delete("source_projection_captures","capture_id=?",arrayOf(id))
    }

    fun reconcile(db: SQLiteDatabase, limit: Int = 8, projectCrdt: (BufferSourceEvent, String, JSONObject) -> String,
                  nativeActor: String, applyNative: (JSONObject) -> Unit): Boolean {
        require(limit in 1..32); db.beginTransaction()
        try {
            val localScan = scan(db, "local_deletions")
            if ((BufferSourceEffects.undoing(db)||BufferSourceLegacyMigration.active(db)) && !localHistoryReady(db)) { db.setTransactionSuccessful(); return true }
            if (BufferSourceLegacyMigration.step(db,nativeActor,applyNative)) { db.setTransactionSuccessful();return true }
            if (BufferSourceEffects.recover(db)) { db.setTransactionSuccessful(); return true }
            val replayScan = scan(db, "replay")
            val eligible = if (localHistoryReady(db)) "state IN ('pending','waiting_local_history')" else "state='pending'"
            val ids = db.rawQuery("SELECT event_id FROM source_event_projections WHERE $eligible ORDER BY event_id LIMIT ?",
                arrayOf(limit.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            var bytes = 0
            for (id in ids) {
                if (bytes >= 2 * BufferSourceEvent.MAX_BYTES) break
                // History has independently checked the original. Do not
                // repeatedly decode a known corrupt envelope and roll back
                // every unrelated projection in this batch with it.
                val corrupt=db.rawQuery("SELECT state FROM source_event_history WHERE event_id=?",arrayOf(id))
                    .use { it.moveToFirst() && it.getString(0)=="corrupt" }
                if(corrupt) {
                    db.execSQL("UPDATE source_event_projections SET state='corrupt_original' WHERE event_id=?",arrayOf(id))
                    continue
                }
                val location = db.rawQuery("""SELECT e.source_id,e.source_sequence,r.owner_peer FROM source_events e
                    JOIN source_event_receivers r ON r.source_id=e.source_id WHERE e.event_id=?""", arrayOf(id)).use {
                    if (it.moveToFirst()) Triple(it.getString(0), it.getLong(1), it.getString(2)) else null
                }
                if (location == null) {
                    // Let the independent history checker establish damage.
                    // Do not synthesize an original or repeatedly roll back
                    // unrelated projections behind this orphaned queue row.
                    BufferEventHistory.enqueue(db,id)
                    db.execSQL("UPDATE source_event_projections SET state='waiting_history' WHERE event_id=?",arrayOf(id))
                    continue
                }
                // Stored history is usable offline under its durable owner
                // binding. This callback grants no network or executor access.
                val record = BufferEventJournal.received(db, location.third, location.first, location.second) { true }
                check(record.eventId == id); bytes += record.raw.toByteArray(Charsets.UTF_8).size
                val history = db.rawQuery("SELECT fingerprint,state FROM source_event_history WHERE event_id=?", arrayOf(id))
                    .use { if (it.moveToFirst() && it.getString(0) == record.fingerprint) it.getString(1) else null }
                var outcome = "none"
                var capture: String? = record.event.opt("capture_id")?.takeUnless { it == JSONObject.NULL } as String?
                var documentId: String? = null
                val state = try { when {
                    history == null -> "waiting_history"
                    record.event.getLong("schema_version") != 1L -> "unsupported_schema"
                    history !in setOf("linked", "legacy", "missing_parent", "waiting_parent") -> "blocked_parent"
                    else -> {
                        var malformed = false
                        var health = false
                        var crdt: BufferSourceCrdtProjection.View? = null
                        var observations: List<SourceObservation>? = null
                        val value = try {
                            health = BufferHealthQuantFacts.observes(record)
                            if (!health) crdt = BufferSourceCrdtProjection.parse(record)
                            if (!health && crdt == null) observations = BufferSourceObservations.parse(record)
                            if (!health && crdt == null && observations == null) snapshot(db,record) else null
                        }
                        catch (_: IllegalArgumentException) { malformed = true; null }
                        catch (_: IllegalStateException) { malformed = true; null }
                        catch (_: JSONException) { malformed = true; null }
                        if (malformed) "invalid_payload"
                        else if (health) { outcome="observed_only"; "observed_only" }
                        else if (crdt != null) {
                            val view = requireNotNull(crdt); capture = view.capture
                            documentId = view.document?.getString("id")
                            outcome = view.document?.let { document ->
                                BufferSourceEffects.record(db, record, BufferSourceEffects.Scope(view.capture, document = document.getString("id"))) {
                                    projectCrdt(record, location.third, document)
                                }
                            } ?: "observed_only"
                            when {
                                outcome in setOf("conflict", "waiting_local_history") -> outcome
                                outcome == "observed_only" -> "observed_only"
                                history in setOf("missing_parent", "waiting_parent") -> "partial"
                                else -> "applied"
                            }
                        }
                        else if (observations != null) {
                            val views = requireNotNull(observations)
                            outcome = BufferSourceEffects.record(db, record, BufferSourceEffects.Scope(capture, observations = views)) {
                                BufferSourceObservations.apply(db, location.third, record, views)
                            }
                            val card = views.mapNotNull { it.card }.distinct().singleOrNull()
                            documentId = card
                            if (card != null) capture = db.rawQuery("SELECT capture_id FROM cards WHERE card_id=?", arrayOf(card)).use {
                                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else capture
                            }
                            when {
                                outcome == "conflict" -> "conflict"
                                outcome == "observed_only" -> "observed_only"
                                history in setOf("missing_parent", "waiting_parent") -> "partial"
                                else -> "applied"
                            }
                        }
                        else if (value == null && record.event.getString("type")=="CaptureTranscribed" && record.event.getJSONObject("payload").has("transcript")) "waiting_history"
                        else if (value == null) "unsupported_type"
                        else if (!localHistoryReady(db)) "waiting_local_history"
                        else {
                            outcome = BufferSourceEffects.record(db, record, BufferSourceEffects.Scope(value.id,
                                value.processed?.segments?.map { it.cardId }?.toSet().orEmpty())) {
                                val result = apply(db, location.third, record, value)
                                if (value.processed != null && result in setOf("applied", "local_override", "legacy_preserved", "superseded"))
                                    db.execSQL("""INSERT OR IGNORE INTO source_projection_processed(owner_peer,capture_id,record_sha256,event_id)
                                        VALUES(?,?,?,?)""", arrayOf(location.third, value.id, value.processed.fingerprint, id))
                                result
                            }
                            when {
                                outcome == "conflict" -> "conflict"
                                history in setOf("missing_parent", "waiting_parent") -> "partial"
                                else -> "applied"
                            }
                        }
                    }
                } } catch (_: BufferSourceEffects.CapacityExceeded) {
                    outcome = "capacity"; "unsupported_capacity"
                }
                check(db.update("source_event_projections", ContentValues().apply { put("state", state) }, "event_id=?", arrayOf(id)) == 1)
                check(db.insertWithOnConflict("source_event_projection_details", null, ContentValues().apply {
                    put("event_id", id); put("fingerprint", record.fingerprint); put("outcome", outcome); put("capture_id", capture); put("document_id", documentId)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            val pending = db.rawQuery("SELECT 1 FROM source_event_projections WHERE $eligible LIMIT 1", null).use { it.moveToFirst() }
            if (!localScan && !replayScan && !pending) BufferSourceEffects.replayComplete(db)
            db.setTransactionSuccessful(); return localScan || replayScan || pending || BufferSourceLegacyMigration.cleanupPending(db)
        } finally { db.endTransaction() }
    }
}
