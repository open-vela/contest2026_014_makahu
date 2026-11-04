package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SourceEventImport(val state: String, val nextSequence: Long)

/** Durable original envelopes, independent of cards and external action jobs.
 * Methods run under BufferRepository's monitor. Every mutation is in the same
 * SQLite transaction as its event or receive progress. Imported history only
 * enters the projection queue; it never calls appendEvent or an executor. */
internal object BufferEventJournal {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_local (
            id INTEGER PRIMARY KEY CHECK(id=1), source_id TEXT NOT NULL, last_sequence INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_event_local(id,source_id,last_sequence) VALUES(1,?,0)",
            arrayOf("src-${UUID.randomUUID()}"))
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_events (
            event_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, source_sequence INTEGER NOT NULL,
            logical_clock INTEGER NOT NULL, capture_id TEXT, fingerprint TEXT NOT NULL, envelope TEXT NOT NULL,
            UNIQUE(source_id,source_sequence))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_events_clock ON source_events(logical_clock)")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_events_capture_clock ON source_events(capture_id,logical_clock)")
        db.execSQL("CREATE INDEX IF NOT EXISTS events_clock ON events(logical_clock)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_receivers (
            source_id TEXT PRIMARY KEY, owner_peer TEXT NOT NULL, committed_sequence INTEGER NOT NULL)""")
        // committed_sequence is always the contiguous ORIGINAL prefix.
        // visibility-v1 keeps reservation coverage in a separate table.
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_projections (
            event_id TEXT PRIMARY KEY, state TEXT NOT NULL DEFAULT 'pending')""")
        createDeliveries(db)
        BufferEventVisibility.create(db)
        BufferEventHistory.create(db)
        BufferSourceProjection.create(db)
        BufferDocumentSource.create(db)
    }

    fun createDeliveries(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_delivery_receipts (
            owner_peer TEXT NOT NULL, source_id TEXT NOT NULL, source_sequence INTEGER NOT NULL,
            event_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
            PRIMARY KEY(owner_peer,source_id,source_sequence))""")
    }

    /** Transport metadata, deliberately not another source event. A receipt
     * never deletes the original or substitutes for the peer's live progress. */
    fun delivered(db: SQLiteDatabase, peer: String, raw: String, authorize: () -> Boolean) {
        check(authorize()) { "event send authorization required" }
        require(peer.matches(Regex("[0-9a-f]{64}")))
        val record = BufferSourceEvent.decode(raw)
        val hash = record.fingerprint
        db.beginTransaction()
        try {
            check(authorize()) { "event send authorization required" }
            require(record.source == source(db) && record.sequence <= head(db))
            check(readableOriginal(db, "event_id=?", arrayOf(record.eventId))) { "local source original needs review" }
            db.query("source_events", arrayOf("source_id", "source_sequence", "fingerprint", "envelope"), "event_id=?",
                arrayOf(record.eventId), null, null, null).use { rows ->
                check(rows.moveToFirst() && rows.getString(0) == record.source && rows.getLong(1) == record.sequence &&
                    rows.getString(2) == hash && rows.getString(3) == raw) { "receipt does not match local original" }
            }
            val where = "owner_peer=? AND source_id=? AND source_sequence=?"
            val args = arrayOf(peer, record.source, record.sequence.toString())
            val exists = db.query("source_event_delivery_receipts", arrayOf("event_id", "fingerprint"), where, args, null, null, null).use {
                if (!it.moveToFirst()) false else {
                    check(it.getString(0) == record.eventId && it.getString(1) == hash) { "conflicting source delivery receipt" }
                    true
                }
            }
            if (!exists) db.insertOrThrow("source_event_delivery_receipts", null, ContentValues().apply {
                put("owner_peer", peer); put("source_id", record.source); put("source_sequence", record.sequence)
                put("event_id", record.eventId); put("fingerprint", hash)
            })
            BufferSourceVisibilityOutgoing.originalDelivered(db, peer, record)
            check(authorize()) { "event send authorization revoked before receipt commit" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun source(db: SQLiteDatabase): String = db.rawQuery("SELECT source_id FROM source_event_local WHERE id=1", null)
        .use { check(it.moveToFirst()); it.getString(0) }

    fun head(db: SQLiteDatabase): Long = db.rawQuery("SELECT last_sequence FROM source_event_local WHERE id=1", null)
        .use { check(it.moveToFirst()); it.getLong(0) }

    /** Query only scalar metadata before a cursor can load a damaged body.
     * Callers hold a read/write transaction across this check and the read. */
    internal fun readableOriginal(db: SQLiteDatabase, selection: String, args: Array<String>): Boolean = db.rawQuery(
        """SELECT CASE WHEN typeof(envelope)='text' AND LENGTH(CAST(envelope AS BLOB)) BETWEEN 1 AND ${BufferSourceEvent.MAX_BYTES}
            AND typeof(event_id)='text' AND LENGTH(event_id) BETWEEN 1 AND 160
            AND typeof(fingerprint)='text' AND LENGTH(fingerprint)=64
            AND typeof(source_id)='text' AND LENGTH(source_id) BETWEEN 1 AND 96
            AND typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND ${BufferSourceEvent.MAX_COUNTER - 1}
            AND typeof(logical_clock)='integer' AND logical_clock BETWEEN 0 AND ${BufferSourceEvent.MAX_COUNTER}
            AND (typeof(capture_id)='null' OR (typeof(capture_id)='text' AND LENGTH(capture_id)<=160))
            THEN 1 ELSE 0 END FROM source_events WHERE $selection""", args).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun localRecord(db: SQLiteDatabase, id: String): BufferSourceEvent {
        val transaction = !db.inTransaction()
        if (transaction) db.beginTransactionNonExclusive()
        try {
            check(readableOriginal(db, "event_id=?", arrayOf(id))) { "local source original needs review" }
            val record = db.query("source_events", arrayOf("envelope", "fingerprint", "source_id", "source_sequence", "logical_clock", "capture_id"),
                "event_id=?", arrayOf(id), null, null, null).use {
                check(it.moveToFirst()) { "local source original is missing" }
                BufferSourceEvent.decode(it.getString(0)).also { record ->
                    check(record.eventId == id && record.fingerprint == it.getString(1) && record.source == it.getString(2) &&
                        record.sequence == it.getLong(3) && record.logicalClock == it.getLong(4) &&
                        (if (record.event.isNull("capture_id")) null else record.event.getString("capture_id")) == it.getString(5))
                }
            }
            check(record.source == source(db) && record.sequence <= head(db))
            if (transaction) db.setTransactionSuccessful()
            return record
        } finally { if (transaction) db.endTransaction() }
    }

    /** Older events were generated locally, including earlier reconstructed
     * remote captures. Preserve them honestly as legacy_local, never claim they
     * are recovered remote originals. Upgrade itself supplies the transaction. */
    fun backfill(db: SQLiteDatabase) {
        db.rawQuery("SELECT * FROM events ORDER BY rowid", null).use { rows ->
            while (rows.moveToNext()) {
                val value = event(rows)
                val exists = db.rawQuery("SELECT 1 FROM source_events WHERE event_id=?", arrayOf(value.getString("event_id")))
                    .use { it.moveToFirst() }
                if (!exists) append(db, value, legacy = true)
                else check(BufferCrdt.canonical(localRecord(db, value.getString("event_id")).event) == BufferCrdt.canonical(value)) {
                    "existing local original differs from legacy event"
                }
            }
        }
    }

    fun append(db: SQLiteDatabase, event: JSONObject, legacy: Boolean = false) {
        check(db.inTransaction()) { "source event must join its fact transaction" }
        val sequence = db.rawQuery("SELECT last_sequence FROM source_event_local WHERE id=1", null).use {
            check(it.moveToFirst()); Math.addExact(it.getLong(0), 1L)
        }
        val record = BufferSourceEvent.encode(source(db), sequence, event, legacy)
        insert(db, record)
        BufferEventHistory.enqueue(db, record.eventId)
        check(db.update("source_event_local", ContentValues().apply { put("last_sequence", sequence) }, "id=1", null) == 1)
    }

    fun page(db: SQLiteDatabase, after: Long, limit: Int = 8): List<String> {
        require(after in 0L until BufferSourceEvent.MAX_COUNTER && limit in 1..32)
        db.beginTransactionNonExclusive()
        try {
            val ids = db.query("source_events", arrayOf("event_id"), "source_id=? AND source_sequence>?",
                arrayOf(source(db), after.toString()), null, null, "source_sequence ASC", limit.toString()).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            val originals = ids.map { localRecord(db, it).raw }
            db.setTransactionSuccessful(); return originals
        } finally { db.endTransaction() }
    }

    fun nextUndelivered(db: SQLiteDatabase, peer: String, after: Long): Long? {
        require(peer.matches(Regex("[0-9a-f]{64}")) && after in 0L until BufferSourceEvent.MAX_COUNTER)
        return db.rawQuery("""SELECT e.source_sequence FROM source_events e
            WHERE e.source_id=? AND e.source_sequence>? AND NOT EXISTS (
                SELECT 1 FROM source_event_delivery_receipts d WHERE d.owner_peer=?
                AND d.source_id=e.source_id AND d.source_sequence=e.source_sequence
                AND d.event_id=e.event_id AND d.fingerprint=e.fingerprint)
            ORDER BY e.source_sequence LIMIT 1""", arrayOf(source(db), after.toString(), peer)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    fun progress(db: SQLiteDatabase, peer: String, stream: String, authorize: () -> Boolean): Long =
        BufferEventVisibility.progress(db, peer, stream, authorize).originalNext

    /** Recover a receiver's durable proof after a lost sender receipt. This
     * reads the original under its owner cursor, never reconstructs an event. */
    fun received(db: SQLiteDatabase, peer: String, stream: String, sequence: Long,
                 authorize: () -> Boolean): BufferSourceEvent {
        check(authorize()) { "event receive authorization required" }
        require(peer.matches(Regex("[0-9a-f]{64}")) && stream.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        require(sequence in 1L until BufferSourceEvent.MAX_COUNTER && stream != source(db))
        db.beginTransactionNonExclusive()
        try {
            BufferEventVisibility.readableHead(db, peer, stream, sequence)
            check(readableOriginal(db, "source_id=? AND source_sequence=?", arrayOf(stream, sequence.toString()))) {
                "committed source original needs review"
            }
            val record = db.query("source_events", arrayOf("envelope", "fingerprint", "event_id", "logical_clock"),
                "source_id=? AND source_sequence=?", arrayOf(stream, sequence.toString()), null, null, null).use {
                check(it.moveToFirst()) { "committed source original is missing" }
                BufferSourceEvent.decode(it.getString(0)).also { record ->
                    check(record.source == stream && record.sequence == sequence && record.fingerprint == it.getString(1) &&
                        record.eventId == it.getString(2) && record.logicalClock == it.getLong(3))
                }
            }
            check(authorize()) { "event receive authorization revoked" }
            db.setTransactionSuccessful()
            return record
        } finally { db.endTransaction() }
    }

    /** Only a currently authorized logical source may publish a stream. Its
     * first committed event pins the owner. Forwarding via another peer is not
     * authorized by this API; a channel is not a transferable source signature. */
    fun receive(db: SQLiteDatabase, peer: String, raw: String, authorize: () -> Boolean): SourceEventImport {
        check(authorize()) { "event receive authorization required" }
        require(peer.matches(Regex("[0-9a-f]{64}")))
        val record = BufferSourceEvent.decode(raw)
        db.beginTransaction()
        try {
            check(authorize()) { "event receive authorization required" }
            require(record.source != source(db)) { "cannot import our own source stream" }
            check(!BufferEventVisibility.hasProfile(db, record.source)) { "visibility-v1 receive profile required for this source" }
            // A missing profile must not turn surviving reservations or
            // originals above the old cursor into an apparently fresh stream.
            BufferEventVisibility.progress(db, peer, record.source, authorize)
            val owner = db.query("source_event_receivers", arrayOf("owner_peer", "committed_sequence"), "source_id=?",
                arrayOf(record.source), null, null, null).use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
            require(owner == null || owner.first == peer) { "source stream belongs to another authenticated peer" }
            val head = owner?.second ?: 0L
            val previousClock = if (head == 0L) -1L else db.query("source_events", arrayOf("logical_clock"),
                "source_id=? AND source_sequence=?", arrayOf(record.source, head.toString()), null, null, null).use {
                check(it.moveToFirst()) { "event receive cursor has no committed record" }; it.getLong(0)
            }
            val existing = db.rawQuery("SELECT 1 FROM source_events WHERE event_id=?", arrayOf(record.eventId)).use { it.moveToFirst() }
            if (existing) check(readableOriginal(db, "event_id=?", arrayOf(record.eventId))) { "stored source original needs review" }
            val old = db.query("source_events", arrayOf("source_id", "source_sequence", "fingerprint", "envelope"), "event_id=?",
                arrayOf(record.eventId), null, null, null).use {
                if (!it.moveToFirst()) null else listOf(it.getString(0), it.getLong(1).toString(), it.getString(2), it.getString(3))
            }
            val state = when {
                old != null -> if (record.sequence <= head && old == listOf(record.source, record.sequence.toString(), record.fingerprint, raw)) "duplicate" else "conflict"
                record.sequence <= head -> "conflict"
                record.sequence != head + 1 -> "gap"
                record.envelope.getString("provenance") == "native" && record.logicalClock <= previousClock -> "conflict"
                else -> {
                    // No insert/update of the legacy events table: its existing
                    // readers include local calendar/health execution receipts.
                    archiveReceived(db, record)
                    if (owner == null) db.insertOrThrow("source_event_receivers", null, ContentValues().apply {
                        put("source_id", record.source); put("owner_peer", peer); put("committed_sequence", record.sequence)
                    }) else check(db.update("source_event_receivers", ContentValues().apply {
                        put("committed_sequence", record.sequence)
                    }, "source_id=?", arrayOf(record.source)) == 1)
                    "stored"
                }
            }
            check(authorize()) { "event authorization revoked before commit" }
            db.setTransactionSuccessful()
            return SourceEventImport(state, if (state == "stored") record.sequence + 1 else head + 1)
        } finally { db.endTransaction() }
    }

    fun missingParents(db: SQLiteDatabase, raw: String): List<String> = BufferSourceEvent.decode(raw).parents.filter { id ->
        db.query("source_events", arrayOf("event_id"), "event_id=?", arrayOf(id), null, null, null, "1")
            .use { !it.moveToFirst() }
    }

    internal fun archiveReceived(db: SQLiteDatabase, record: BufferSourceEvent) {
        check(db.inTransaction())
        BufferMeshFacts.checkOriginal(db, record)
        insert(db, record)
        BufferEventHistory.enqueue(db, record.eventId)
        db.insertOrThrow("source_event_projections", null, ContentValues().apply { put("event_id", record.eventId) })
    }

    private fun insert(db: SQLiteDatabase, record: BufferSourceEvent) {
        db.insertOrThrow("source_events", null, ContentValues().apply {
            put("event_id", record.eventId); put("source_id", record.source); put("source_sequence", record.sequence)
            if (record.event.isNull("capture_id")) putNull("capture_id") else put("capture_id", record.event.getString("capture_id"))
            put("logical_clock", record.logicalClock); put("fingerprint", record.fingerprint); put("envelope", record.raw)
        })
    }

    fun event(row: Cursor): JSONObject {
        fun text(key: String) = row.getString(row.getColumnIndexOrThrow(key))
        fun number(key: String) = row.getLong(row.getColumnIndexOrThrow(key))
        return JSONObject().put("event_id", text("event_id")).put("app_namespace", text("app_namespace"))
            .put("actor_id", text("actor_id")).put("schema_version", number("schema_version"))
            .put("parent_event_ids", JSONArray(text("parent_event_ids"))).put("logical_clock", number("logical_clock"))
            .put("type", text("type")).put("capture_id", text("capture_id") ?: JSONObject.NULL)
            .put("created_at", number("created_at")).put("payload", JSONObject(text("payload")))
    }
}
