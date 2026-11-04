package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import java.util.UUID

/** Stable random reservations are separate from original-byte receipts. Local
 * bindings include the SHA/ID for validation; wire slot values do not. */
internal object BufferSourceVisibilityOutgoing {
    private val tokenPattern = Regex("[0-9a-f]{32}")
    private data class Reservation(val eventId: String, val hash: String, val token: String, val state: String)

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_visibility_outgoing (
            owner_peer TEXT NOT NULL, source_id TEXT NOT NULL,
            source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990),
            event_id TEXT NOT NULL, fingerprint TEXT NOT NULL CHECK(length(fingerprint)=64), slot_token TEXT NOT NULL CHECK(length(slot_token)=32),
            state TEXT NOT NULL CHECK(state IN ('prepared','held','filled','conflict')),
            PRIMARY KEY(owner_peer,source_id,source_sequence))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_visibility_outgoing_held
            ON source_event_visibility_outgoing(owner_peer,source_id,source_sequence) WHERE state='held'""")
    }
    private fun saved(db: SQLiteDatabase, peer: String, source: String, sequence: Long): Reservation? =
        db.query("source_event_visibility_outgoing", arrayOf("event_id", "fingerprint", "slot_token", "state"),
            "owner_peer=? AND source_id=? AND source_sequence=?", arrayOf(peer, source, sequence.toString()), null, null, null).use {
            if (it.moveToFirst()) Reservation(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) else null
        }
    private fun validate(db: SQLiteDatabase, source: String, sequence: Long, value: Reservation): BufferSourceEvent {
        check(tokenPattern.matches(value.token) && value.state in setOf("prepared", "held", "filled")) { "source reservation needs review" }
        val record = BufferEventJournal.localRecord(db, value.eventId)
        check(record.source == source && record.sequence == sequence && record.fingerprint == value.hash)
        return record
    }
    fun prepare(db: SQLiteDatabase, peer: String, raw: String, authorize: () -> Boolean): SourceVisibilitySlot {
        require(peer.matches(Regex("[0-9a-f]{64}"))); check(authorize())
        val incoming = BufferSourceEvent.decode(raw); db.beginTransaction()
        try {
            check(authorize()); val original = BufferEventJournal.localRecord(db, incoming.eventId)
            check(original.raw == raw)
            val old = saved(db, peer, original.source, original.sequence)
            val token = if (old != null) {
                check(validate(db, original.source, original.sequence, old).raw == raw); old.token
            } else {
                // A peer cannot infer an event's payload from this token. Keep
                // it per peer/source/sequence, never derive it from an ID/SHA.
                val generated = UUID.randomUUID().toString().replace("-", "")
                db.insertOrThrow("source_event_visibility_outgoing", null, ContentValues().apply {
                    put("owner_peer", peer); put("source_id", original.source); put("source_sequence", original.sequence)
                    put("event_id", original.eventId); put("fingerprint", original.fingerprint); put("slot_token", generated); put("state", "prepared")
                }); generated
            }
            check(authorize()); db.setTransactionSuccessful(); return SourceVisibilitySlot(original.sequence, token)
        } finally { db.endTransaction() }
    }
    fun reserved(db: SQLiteDatabase, peer: String, source: String, sequence: Long, token: String, authorize: () -> Boolean) {
        require(peer.matches(Regex("[0-9a-f]{64}")) && sequence in 1L until BufferSourceEvent.MAX_COUNTER && tokenPattern.matches(token))
        check(authorize()); db.beginTransaction()
        try {
            check(authorize()); val old = checkNotNull(saved(db, peer, source, sequence))
            validate(db, source, sequence, old); check(old.token == token) { "source reservation token conflict" }
            check(db.update("source_event_visibility_outgoing", ContentValues().apply { put("state", if (old.state == "filled") "filled" else "held") },
                "owner_peer=? AND source_id=? AND source_sequence=?", arrayOf(peer, source, sequence.toString())) == 1)
            // This commits only reservation delivery. It neither inserts nor
            // removes source_event_delivery_receipts or any blob receipt.
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun enabled(db: SQLiteDatabase, peer: String, authorize: () -> Boolean): Boolean {
        require(peer.matches(Regex("[0-9a-f]{64}"))); check(authorize())
        val active = db.rawQuery("SELECT 1 FROM source_event_visibility_outgoing WHERE owner_peer=? AND source_id=? LIMIT 1",
            arrayOf(peer, BufferEventJournal.source(db))).use { it.moveToFirst() }
        check(authorize()); return active
    }
    fun nextHeld(db: SQLiteDatabase, peer: String, after: Long, authorize: () -> Boolean): SourceVisibilitySlot? {
        require(peer.matches(Regex("[0-9a-f]{64}")) && after in 0L until BufferSourceEvent.MAX_COUNTER); check(authorize())
        val own = !db.inTransaction(); if (own) db.beginTransactionNonExclusive()
        try {
            val source = BufferEventJournal.source(db)
            val sequence = db.rawQuery("""SELECT source_sequence FROM source_event_visibility_outgoing
                WHERE owner_peer=? AND source_id=? AND state='held' AND source_sequence>? ORDER BY source_sequence LIMIT 1""",
                arrayOf(peer, source, after.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
            val value = sequence?.let { val old = checkNotNull(saved(db, peer, source, it)); validate(db, source, it, old); SourceVisibilitySlot(it, old.token) }
            check(authorize()); if (own) db.setTransactionSuccessful(); return value
        } finally { if (own) db.endTransaction() }
    }
    /** Called only inside the existing exact-original delivery transaction.
     * A broken reservation is isolated without losing a genuine full receipt. */
    fun originalDelivered(db: SQLiteDatabase, peer: String, record: BufferSourceEvent) {
        check(db.inTransaction()); val old = saved(db, peer, record.source, record.sequence) ?: return
        val same = old.eventId == record.eventId && old.hash == record.fingerprint && tokenPattern.matches(old.token) &&
            old.state in setOf("prepared", "held", "filled")
        check(db.update("source_event_visibility_outgoing", ContentValues().apply { put("state", if (same) "filled" else "conflict") },
            "owner_peer=? AND source_id=? AND source_sequence=?", arrayOf(peer, record.source, record.sequence.toString())) == 1)
    }
}
