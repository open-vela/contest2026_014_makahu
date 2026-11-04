package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceAudioState
import org.json.JSONException

/** Derived export order and independent per-peer binary receipts. Source
 * facts and archives remain immutable; no execution or retention decisions. */
internal object BufferSourceBlobOutgoing {
    private const val MAX_DELAY = 300_000L

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_blob_exports (
            export_id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE,
            source_id TEXT NOT NULL, source_sequence INTEGER NOT NULL, record_sha256 TEXT NOT NULL,
            blob_sha256 TEXT NOT NULL, total INTEGER NOT NULL, content_type TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_blob_export_scan (
            id INTEGER PRIMARY KEY CHECK(id=1), after_capture_id TEXT NOT NULL, complete INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_event_blob_export_scan VALUES(1,'',0)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_blob_senders (
            owner_peer TEXT PRIMARY KEY, after_export_id INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_blob_deliveries (
            owner_peer TEXT NOT NULL, event_id TEXT NOT NULL, source_id TEXT NOT NULL,
            source_sequence INTEGER NOT NULL, record_sha256 TEXT NOT NULL, blob_sha256 TEXT NOT NULL,
            total INTEGER NOT NULL, content_type TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending',
            retry_at INTEGER NOT NULL DEFAULT 0, problem TEXT NOT NULL DEFAULT '', acknowledged INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(owner_peer,event_id))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_blob_outgoing_due
            ON source_event_blob_deliveries(owner_peer,retry_at,event_id)
            WHERE state='pending' OR (state='confirmed' AND problem<>'')""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_blob_outgoing_audit
            ON source_event_blob_deliveries(owner_peer,event_id) WHERE state='confirmed' AND problem=''""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_blob_outgoing_event ON source_event_blob_deliveries(event_id,acknowledged)")
    }

    private fun original(db: SQLiteDatabase, id: String): BufferSourceEvent {
        val record = BufferEventJournal.localRecord(db, id)
        check(record.event.getString("type") in setOf("CaptureCreated", "CaptureBlobDescribed") &&
            record.event.getJSONObject("payload").opt("local_original") == true)
        val capture = record.event.getString("capture_id")
        val witnessId = db.query("source_event_local_audio", arrayOf("event_id", "state", "capture_event_id"), "capture_id=?", arrayOf(capture), null, null, null).use {
            check(it.moveToFirst() && it.getString(0) == id && it.getString(1) == "ready") { "local original is not ready" }
            checkNotNull(it.getString(2))
        }
        val witness = if (witnessId == id) record else BufferEventJournal.localRecord(db, witnessId)
        val payload = witness.event.getJSONObject("payload")
        check(witness.event.getString("type") == "CaptureCreated" && witness.event.getString("capture_id") == capture &&
            capture.matches(Regex("[A-Za-z0-9._-]{1,80}")) && payload.opt("blob_ref") == "$capture.bin")
        check(if (payload.has("local_original")) payload.opt("local_original") == true
            else payload.opt("context_local") == true && payload.opt("device_id") == "android")
        if (record.event.getString("type") == "CaptureCreated") check(witnessId == id)
        else {
            val link = record.event.getJSONObject("payload")
            check(witnessId != id && link.opt("capture_event_id") == witnessId && link.opt("capture_event_sha256") == witness.fingerprint &&
                link.opt("observation") == "legacy_file_now" && witnessId in record.parents)
        }
        return record
    }
    private fun values(ref: SourceBlobTransfer) = ContentValues().apply {
        put("event_id", ref.eventId); put("source_id", ref.source); put("source_sequence", ref.sequence)
        put("record_sha256", ref.recordHash); put("blob_sha256", ref.blobHash); put("total", ref.total); put("content_type", ref.contentType)
    }
    private val columns = arrayOf("event_id", "source_id", "source_sequence", "record_sha256", "blob_sha256", "total", "content_type")
    private fun transfer(row: android.database.Cursor) = SourceBlobTransfer(row.getString(0), row.getString(1), row.getLong(2),
        row.getString(3), row.getString(4), row.getInt(5), row.getString(6))

    /** Called after the durable archive, in the fact/pointer transaction. A
     * newly archived old sequence gets a new export_id, so no peer misses it. */
    fun ready(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        val ref = BufferSourceBlobs.originalTransfer(original(db, id))
        val old = db.query("source_event_blob_exports", columns, "event_id=?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) transfer(it) else null
        }
        if (old != null) check(old == ref) else db.insertOrThrow("source_event_blob_exports", null, values(ref))
    }

    /** Bounded migration of phase-44 ready pointers; new ready transitions
     * enqueue directly, including capture IDs below this migration cursor. */
    fun migrate(db: SQLiteDatabase, limit: Int = 4): Boolean {
        require(limit in 1..8); db.beginTransaction()
        try {
            val cursor = db.rawQuery("SELECT after_capture_id,complete FROM source_event_blob_export_scan WHERE id=1", null).use {
                check(it.moveToFirst()); it.getString(0) to (it.getInt(1) == 1)
            }
            if (cursor.second) { db.setTransactionSuccessful(); return false }
            val rows = db.rawQuery("SELECT capture_id,event_id,state FROM source_event_local_audio WHERE capture_id>? ORDER BY capture_id LIMIT ?",
                arrayOf(cursor.first, limit.toString())).use { row -> buildList {
                    while (row.moveToNext()) add(Triple(row.getString(0), row.getString(1), row.getString(2)))
                } }
            for ((capture, id, state) in rows) if (state == "ready") {
                try { ready(db, checkNotNull(id)) }
                catch (_: IllegalArgumentException) { conflict(db, capture) }
                catch (_: IllegalStateException) { conflict(db, capture) }
                catch (_: JSONException) { conflict(db, capture) }
            }
            check(db.update("source_event_blob_export_scan", ContentValues().apply {
                put("after_capture_id", rows.lastOrNull()?.first ?: cursor.first); put("complete", if (rows.size < limit) 1 else 0)
            }, "id=1", null) == 1)
            db.setTransactionSuccessful(); return rows.size == limit
        } finally { db.endTransaction() }
    }
    private fun conflict(db: SQLiteDatabase, capture: String) {
        check(db.update("source_event_local_audio", ContentValues().apply { put("state", "conflict"); put("problem", "descriptor_conflict") },
            "capture_id=?", arrayOf(capture)) == 1)
    }
    private fun validatePeer(peer: String) { require(peer.matches(Regex("[0-9a-f]{64}"))) }
    private fun seed(db: SQLiteDatabase, peer: String, authorize: () -> Boolean) {
        db.beginTransaction()
        try {
            check(authorize()); db.execSQL("INSERT OR IGNORE INTO source_event_blob_senders(owner_peer) VALUES(?)", arrayOf(peer))
            val after = db.rawQuery("SELECT after_export_id FROM source_event_blob_senders WHERE owner_peer=?", arrayOf(peer)).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            var last = after
            db.rawQuery("SELECT export_id,${columns.joinToString(",")} FROM source_event_blob_exports WHERE export_id>? ORDER BY export_id LIMIT 8",
                arrayOf(after.toString())).use { row -> while (row.moveToNext()) {
                last = row.getLong(0)
                db.execSQL("""INSERT OR IGNORE INTO source_event_blob_deliveries
                    (owner_peer,event_id,source_id,source_sequence,record_sha256,blob_sha256,total,content_type)
                    VALUES(?,?,?,?,?,?,?,?)""", arrayOf(peer, row.getString(1), row.getString(2), row.getLong(3),
                    row.getString(4), row.getString(5), row.getInt(6), row.getString(7)))
            } }
            if (last != after) check(db.update("source_event_blob_senders", ContentValues().apply { put("after_export_id", last) },
                "owner_peer=?", arrayOf(peer)) == 1)
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** A pull may reach an original before the bounded export scan. It still
     * requires this peer's exact original receipt, never merely coverage. */
    fun prepare(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) {
        check(db.inTransaction()); validatePeer(peer); check(authorize())
        val record = original(db, ref.eventId)
        check(BufferSourceBlobs.originalTransfer(record) == ref)
        check(db.rawQuery("""SELECT 1 FROM source_event_delivery_receipts WHERE owner_peer=? AND source_id=?
            AND source_sequence=? AND event_id=? AND fingerprint=?""",
            arrayOf(peer, ref.source, ref.sequence.toString(), ref.eventId, ref.recordHash)).use { it.moveToFirst() })
        db.insertWithOnConflict("source_event_blob_deliveries", null, values(ref).apply { put("owner_peer", peer) },
            SQLiteDatabase.CONFLICT_IGNORE).also { check(it != -1L || db.rawQuery(
                "SELECT 1 FROM source_event_blob_deliveries WHERE owner_peer=? AND event_id=?", arrayOf(peer, ref.eventId)).use { row -> row.moveToFirst() }) }
        validate(db, peer, ref, authorize)
    }
    fun validate(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean): BufferSourceEvent {
        validatePeer(peer); check(authorize())
        val record = original(db, ref.eventId)
        check(BufferSourceBlobs.originalTransfer(record) == ref)
        db.query("source_event_blob_deliveries", columns + "state", "owner_peer=? AND event_id=?", arrayOf(peer, ref.eventId), null, null, null).use {
            check(it.moveToFirst() && transfer(it) == ref && it.getString(7) in setOf("pending", "confirmed"))
        }
        check(db.rawQuery("""SELECT 1 FROM source_event_delivery_receipts WHERE owner_peer=? AND source_id=?
            AND source_sequence=? AND event_id=? AND fingerprint=?""", arrayOf(peer, ref.source, ref.sequence.toString(), ref.eventId, ref.recordHash)).use { it.moveToFirst() }) {
            "source original has not been acknowledged by this peer"
        }
        check(authorize()); return record
    }
    fun next(db: SQLiteDatabase, peer: String, now: Long, auditAfter: String?, authorize: () -> Boolean): SourceBlobCandidate? {
        validatePeer(peer); require(now in 0L..(Long.MAX_VALUE - MAX_DELAY)); check(authorize()); seed(db, peer, authorize)
        fun select(audit: Boolean): SourceBlobCandidate? {
            val condition = if (audit) "b.state='confirmed' AND b.problem='' AND b.event_id>?"
                else "(b.state='pending' OR (b.state='confirmed' AND b.problem<>''))"
            val args = mutableListOf(peer, now.toString(), (now + MAX_DELAY).toString()); if (audit) args.add(requireNotNull(auditAfter))
            repeat(8) {
                val ref = db.rawQuery("""SELECT ${columns.joinToString(",") { "b.$it" }} FROM source_event_blob_deliveries b
                    WHERE b.owner_peer=? AND (b.retry_at<=? OR b.retry_at>?) AND $condition AND EXISTS (
                    SELECT 1 FROM source_event_local_audio a WHERE a.event_id=b.event_id AND a.state='ready') AND EXISTS (
                    SELECT 1 FROM source_event_delivery_receipts d WHERE d.owner_peer=b.owner_peer AND d.source_id=b.source_id
                    AND d.source_sequence=b.source_sequence AND d.event_id=b.event_id AND d.fingerprint=b.record_sha256)
                    ORDER BY ${if (audit) "b.event_id" else "b.retry_at,b.event_id"} LIMIT 1""", args.toTypedArray()).use {
                    if (it.moveToFirst()) transfer(it) else null
                } ?: return null
                try { validate(db, peer, ref, authorize); return SourceBlobCandidate(ref, audit) }
                catch (_: IllegalArgumentException) { check(authorize()); invalid(db, peer, ref) }
                catch (_: IllegalStateException) { check(authorize()); invalid(db, peer, ref) }
                catch (_: JSONException) { check(authorize()); invalid(db, peer, ref) }
            }
            return null
        }
        return (select(false) ?: if (auditAfter != null) select(true) else null).also { check(authorize()) }
    }
    private fun invalid(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer) {
        check(db.update("source_event_blob_deliveries", ContentValues().apply { put("state", "conflict"); put("problem", "descriptor_conflict") },
            "owner_peer=? AND event_id=?", arrayOf(peer, ref.eventId)) == 1)
    }
    fun auditPending(db: SQLiteDatabase, peer: String, after: String, authorize: () -> Boolean): Boolean {
        validatePeer(peer); check(authorize())
        return db.rawQuery("""SELECT 1 FROM source_event_blob_deliveries WHERE owner_peer=?
            AND state='confirmed' AND problem='' AND event_id>? LIMIT 1""", arrayOf(peer, after)).use { it.moveToFirst() }
            .also { check(authorize()) }
    }
    fun confirm(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) {
        db.beginTransaction()
        try {
            validate(db, peer, ref, authorize)
            check(db.update("source_event_blob_deliveries", ContentValues().apply {
                put("state", "confirmed"); put("acknowledged", 1); put("retry_at", 0); put("problem", "")
            },
                "owner_peer=? AND event_id=?", arrayOf(peer, ref.eventId)) == 1)
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun defer(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, reason: String, now: Long, authorize: () -> Boolean) {
        require(reason in setOf("withheld", "unsupported", "conflict", "no_response", "storage_error", "local_storage", "event_missing"))
        require(now in 0L..(Long.MAX_VALUE - MAX_DELAY)); db.beginTransaction()
        try {
            validate(db, peer, ref, authorize)
            val delay = when (reason) { "local_storage", "storage_error", "event_missing" -> 10_000L; "no_response" -> 60_000L; else -> MAX_DELAY }
            check(db.update("source_event_blob_deliveries", ContentValues().apply { put("problem", reason); put("retry_at", now + delay) },
                "owner_peer=? AND event_id=?", arrayOf(peer, ref.eventId)) == 1)
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun state(db: SQLiteDatabase, id: String): BufferSourceAudioState {
        val delivered = db.rawQuery("SELECT 1 FROM source_event_blob_deliveries WHERE event_id=? AND acknowledged=1 LIMIT 1",
            arrayOf(id)).use { it.moveToFirst() }
        val problem = db.rawQuery("SELECT problem FROM source_event_blob_deliveries WHERE event_id=? AND problem<>'' ORDER BY owner_peer LIMIT 1",
            arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else "" }
        if (delivered) return BufferSourceAudioState("local_delivered", problem)
        if (problem.isNotEmpty()) return BufferSourceAudioState("local_archived", problem)
        val waiting = db.rawQuery("""SELECT 1 FROM source_event_blob_deliveries b WHERE b.event_id=? AND NOT EXISTS (
            SELECT 1 FROM source_event_delivery_receipts d WHERE d.owner_peer=b.owner_peer AND d.source_id=b.source_id
            AND d.source_sequence=b.source_sequence AND d.event_id=b.event_id AND d.fingerprint=b.record_sha256) LIMIT 1""",
            arrayOf(id)).use { it.moveToFirst() }
        return BufferSourceAudioState(if (waiting) "local_waiting_event" else "local_archived", "")
    }
    fun requestCheck(db: SQLiteDatabase, capture: String) {
        check(db.inTransaction())
        db.execSQL("""UPDATE source_event_blob_deliveries SET retry_at=0,problem='local_storage'
            WHERE event_id=(SELECT event_id FROM source_event_local_audio WHERE capture_id=?) AND state IN ('pending','confirmed','conflict')""", arrayOf(capture))
        // A semantic conflict is retried only after explicit local recheck.
        db.execSQL("""UPDATE source_event_blob_deliveries SET state='pending' WHERE state='conflict'
            AND event_id=(SELECT event_id FROM source_event_local_audio WHERE capture_id=?)""", arrayOf(capture))
    }
}
