package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent

internal data class SourceParentLocation(val source: String, val sequence: Long, val fingerprint: String, val head: Long)

/** Durable discovery hints, never receive cursors or source facts. Ask only
 * the authenticated owner of the child that supplied the missing reference. */
internal object BufferSourceParents {
    private const val MAX_DELAY = 300_000L
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_parent_queries (
            owner_peer TEXT NOT NULL, parent_event_id TEXT NOT NULL, state TEXT NOT NULL,
            retry_at INTEGER NOT NULL, source_id TEXT, source_sequence INTEGER, fingerprint TEXT, source_head INTEGER,
            PRIMARY KEY(owner_peer,parent_event_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_parent_query_due ON source_event_parent_queries(owner_peer,retry_at,parent_event_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_parent_query_id ON source_event_parent_queries(parent_event_id,owner_peer)")
    }

    fun arrived(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        db.delete("source_event_parent_queries", "parent_event_id=?", arrayOf(id))
    }

    fun observe(db: SQLiteDatabase, record: BufferSourceEvent) {
        check(db.inTransaction())
        if (record.envelope.getString("provenance") != "native" || record.event.getLong("schema_version") != 1L) return
        val peer = db.query("source_event_receivers", arrayOf("owner_peer"), "source_id=?", arrayOf(record.source),
            null, null, null).use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        check(peer.matches(Regex("[0-9a-f]{64}")))
        record.parents.forEach { parent ->
            db.execSQL("""INSERT OR IGNORE INTO source_event_parent_queries(owner_peer,parent_event_id,state,retry_at)
                SELECT ?,?,'pending',0 WHERE NOT EXISTS(SELECT 1 FROM source_events WHERE event_id=?)""", arrayOf(peer, parent, parent))
        }
    }

    fun next(db: SQLiteDatabase, peer: String, now: Long, authorize: () -> Boolean): String? {
        require(peer.matches(Regex("[0-9a-f]{64}")) && now in 0L..(Long.MAX_VALUE - MAX_DELAY))
        check(authorize())
        val id = db.rawQuery("""SELECT q.parent_event_id FROM source_event_parent_queries q
            WHERE q.owner_peer=? AND (q.retry_at<=? OR q.retry_at>?)
            AND NOT EXISTS(SELECT 1 FROM source_events p WHERE p.event_id=q.parent_event_id)
            AND EXISTS(SELECT 1 FROM source_event_history_parents d
                JOIN source_events c ON c.event_id=d.event_id
                JOIN source_event_receivers r ON r.source_id=c.source_id
                JOIN source_event_history h ON h.event_id=c.event_id
                WHERE d.parent_event_id=q.parent_event_id AND r.owner_peer=q.owner_peer AND h.state='missing_parent')
            ORDER BY q.retry_at,q.parent_event_id LIMIT 1""",
            arrayOf(peer, now.toString(), (now + MAX_DELAY).toString())).use { if (it.moveToFirst()) it.getString(0) else null }
        if (id != null) require(id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        check(authorize())
        return id
    }

    /** A locator only prioritizes an existing, owner-bound held slot. It
     * cannot create coverage, supply a token, or authorize a different source.
     * Claiming a probe durably throttles repeated hints across reconnects. */
    fun nextFill(db: SQLiteDatabase, peer: String, source: String, before: Long, now: Long,
                 authorize: () -> Boolean): SourceVisibilitySlot? {
        check(db.inTransaction())
        require(before in 1L..BufferSourceEvent.MAX_COUNTER && now in 0L..(Long.MAX_VALUE - MAX_DELAY))
        check(authorize())
        BufferEventVisibility.progress(db, peer, source, authorize)
        val hint = db.rawQuery("""SELECT q.parent_event_id,q.source_sequence FROM source_event_parent_queries q
            JOIN source_event_visibility_slots s ON s.source_id=q.source_id AND s.source_sequence=q.source_sequence
            WHERE q.owner_peer=? AND q.source_id=? AND q.state='available' AND q.source_sequence<?
            AND s.owner_peer=q.owner_peer AND s.state='held'
            AND NOT EXISTS(SELECT 1 FROM source_events p WHERE p.event_id=q.parent_event_id)
            AND EXISTS(SELECT 1 FROM source_event_history_parents d
                JOIN source_events c ON c.event_id=d.event_id
                JOIN source_event_receivers r ON r.source_id=c.source_id
                JOIN source_event_history h ON h.event_id=c.event_id
                WHERE d.parent_event_id=q.parent_event_id AND r.owner_peer=q.owner_peer AND h.state='missing_parent')
            ORDER BY q.retry_at,q.parent_event_id LIMIT 1""",
            arrayOf(peer, source, before.toString())).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        } ?: return null
        check(BufferEventVisibility.held(db, peer, source, hint.second))
        val token = checkNotNull(BufferEventVisibility.token(db, peer, source, hint.second, authorize))
        check(db.update("source_event_parent_queries", ContentValues().apply {
            put("state", "probing"); put("retry_at", now + 60_000)
        },
            "owner_peer=? AND parent_event_id=?", arrayOf(peer, hint.first)) == 1)
        check(authorize())
        return SourceVisibilitySlot(hint.second, token)
    }

    /** A bounded lookup over this child's at-most-32 parent identities. The
     * displayed discovery state never changes the stored causal verdict. */
    fun historyState(db: SQLiteDatabase, peer: String, child: String): String? = db.rawQuery("""
        SELECT q.state FROM source_event_history_parents d JOIN source_event_parent_queries q
        ON q.parent_event_id=d.parent_event_id AND q.owner_peer=? WHERE d.event_id=?
        ORDER BY CASE q.state WHEN 'conflict' THEN 0 WHEN 'history_gap' THEN 1 WHEN 'withheld' THEN 2
            WHEN 'not_found' THEN 3 WHEN 'unsupported' THEN 4 WHEN 'no_response' THEN 5 ELSE 6 END LIMIT 1""",
        arrayOf(peer, child)).use {
        if (!it.moveToFirst()) null else when (it.getString(0)) {
            "conflict" -> "parent_conflict"
            "history_gap" -> "history_gap"
            "withheld" -> "parent_withheld"
            "not_found" -> "parent_not_found"
            "unsupported", "no_response" -> "parent_unavailable"
            "available", "probing" -> "parent_available"
            else -> null
        }
    }

    /** A locator does not import bytes or advance progress. The continuous
     * stream or a negotiated token-bound fill must still archive the original. */
    fun result(db: SQLiteDatabase, peer: String, id: String, state: String, location: SourceParentLocation?,
               now: Long, authorize: () -> Boolean): String {
        require(peer.matches(Regex("[0-9a-f]{64}")) && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        require(now in 0L..(Long.MAX_VALUE - MAX_DELAY) && state in setOf("available", "withheld", "not_found", "unsupported", "no_response", "conflict"))
        require((state == "available") == (location != null))
        if (location != null) require(location.source.matches(Regex("[A-Za-z0-9_.-]{1,96}")) &&
            location.sequence in 1L until BufferSourceEvent.MAX_COUNTER && location.head in location.sequence until BufferSourceEvent.MAX_COUNTER &&
            location.fingerprint.matches(Regex("[0-9a-f]{64}")))
        check(authorize()); db.beginTransaction()
        try {
            check(authorize())
            if (db.rawQuery("SELECT 1 FROM source_events WHERE event_id=?", arrayOf(id)).use { it.moveToFirst() }) {
                arrived(db, id); check(authorize()); db.setTransactionSuccessful(); return "resolved"
            }
            val exists = db.rawQuery("SELECT 1 FROM source_event_parent_queries WHERE owner_peer=? AND parent_event_id=?",
                arrayOf(peer, id)).use { it.moveToFirst() }
            if (!exists) { check(authorize()); db.setTransactionSuccessful(); return "obsolete" }
            var outcome = state
            if (location != null) {
                if (location.source == BufferEventJournal.source(db)) outcome = "conflict"
                db.query("source_event_receivers", arrayOf("owner_peer"), "source_id=?",
                    arrayOf(location.source), null, null, null).use {
                    if (it.moveToFirst()) {
                        if (it.getString(0) != peer) outcome = "conflict"
                        else {
                            val progress = BufferEventVisibility.progress(db, peer, location.source, authorize)
                            // A held slot is a deliberate absence. An available
                            // locator may wake its later fill; other missing
                            // covered bytes remain damage, never a silent repair.
                            if (location.sequence < progress.coveredNext &&
                                !BufferEventVisibility.held(db, peer, location.source, location.sequence)) outcome = "history_gap"
                        }
                    }
                }
                db.query("source_events", arrayOf("event_id"), "source_id=? AND source_sequence=?",
                    arrayOf(location.source, location.sequence.toString()), null, null, null).use {
                    if (it.moveToFirst() && it.getString(0) != id) outcome = "conflict"
                }
            }
            val delay = when (outcome) { "available" -> 10_000L; "no_response" -> 60_000L; else -> MAX_DELAY }
            val saved = if (outcome == "conflict") null else location
            check(db.update("source_event_parent_queries", ContentValues().apply {
                put("state", outcome); put("retry_at", now + delay)
                put("source_id", saved?.source); put("source_sequence", saved?.sequence)
                put("fingerprint", saved?.fingerprint); put("source_head", saved?.head)
            }, "owner_peer=? AND parent_event_id=?", arrayOf(peer, id)) == 1)
            check(authorize()); db.setTransactionSuccessful(); return outcome
        } finally { db.endTransaction() }
    }
}
