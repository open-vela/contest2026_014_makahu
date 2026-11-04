package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent

internal data class SourceVisibilityProgress(val coveredNext: Long, val originalNext: Long)
internal data class SourceVisibilityImport(val state: String, val progress: SourceVisibilityProgress)
internal data class SourceVisibilitySlot(val sequence: Long, val token: String)

/** visibility-v1 storage only. A reservation contains no original identity,
 * time, type, clock or hash. Coverage is never a receipt for original bytes.
 * Call from a negotiated owner channel; forwarding remains unauthorized. */
internal object BufferEventVisibility {
    private const val MAX = BufferSourceEvent.MAX_COUNTER
    private val tokenPattern = Regex("[0-9a-f]{32}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private data class Head(val peer: String, val covered: Long, val original: Long, val profile: Boolean, val owner: Boolean) {
        fun progress() = SourceVisibilityProgress(covered + 1, original + 1)
    }
    private data class Slot(val peer: String, val token: String, val state: String, val eventId: String?, val hash: String?)

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_visibility_receivers (
            source_id TEXT PRIMARY KEY, owner_peer TEXT NOT NULL, visibility_version INTEGER NOT NULL CHECK(visibility_version=1),
            covered_sequence INTEGER NOT NULL CHECK(typeof(covered_sequence)='integer' AND covered_sequence BETWEEN 0 AND 9007199254740990))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_visibility_slots (
            source_id TEXT NOT NULL, source_sequence INTEGER NOT NULL CHECK(typeof(source_sequence)='integer' AND source_sequence BETWEEN 1 AND 9007199254740990), owner_peer TEXT NOT NULL,
            slot_token TEXT NOT NULL CHECK(length(slot_token)=32), state TEXT NOT NULL CHECK(state IN ('held','filled')), event_id TEXT, fingerprint TEXT,
            CHECK((state='held' AND event_id IS NULL AND fingerprint IS NULL) OR
                (state='filled' AND event_id IS NOT NULL AND fingerprint IS NOT NULL AND length(fingerprint)=64)),
            PRIMARY KEY(source_id,source_sequence))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_visibility_held
            ON source_event_visibility_slots(source_id,source_sequence) WHERE state='held'""")
        BufferSourceVisibilityOutgoing.create(db)
    }
    private fun identity(db: SQLiteDatabase, peer: String, source: String) {
        require(peer.matches(Regex("[0-9a-f]{64}")) && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        require(source != BufferEventJournal.source(db)) { "cannot reserve or import our own stream" }
        BufferMeshFacts.requireOwner(db, source, peer)
    }
    fun hasProfile(db: SQLiteDatabase, source: String): Boolean = db.rawQuery(
        "SELECT 1 FROM source_event_visibility_receivers WHERE source_id=?", arrayOf(source)).use { it.moveToFirst() }

    /** Exact stored bytes; this deliberately does not consult the contiguous
     * original cursor, since a real original can follow an unfilled slot. */
    private fun record(db: SQLiteDatabase, source: String, sequence: Long): BufferSourceEvent? {
        val args = arrayOf(source, sequence.toString())
        if (!db.rawQuery("SELECT 1 FROM source_events WHERE source_id=? AND source_sequence=?", args).use { it.moveToFirst() }) return null
        check(BufferEventJournal.readableOriginal(db, "source_id=? AND source_sequence=?", args)) { "source original needs review" }
        return db.query("source_events",
        arrayOf("envelope", "fingerprint", "event_id", "logical_clock", "capture_id"), "source_id=? AND source_sequence=?",
        arrayOf(source, sequence.toString()), null, null, null).use { row ->
        if (!row.moveToFirst()) null else BufferSourceEvent.decode(row.getString(0)).also {
            check(it.source == source && it.sequence == sequence && it.fingerprint == row.getString(1) &&
                it.eventId == row.getString(2) && it.logicalClock == row.getLong(3) &&
                (if (it.event.isNull("capture_id")) null else it.event.getString("capture_id")) == row.getString(4))
        }
    }
    }
    private fun slot(db: SQLiteDatabase, source: String, sequence: Long): Slot? = db.query("source_event_visibility_slots",
        arrayOf("owner_peer", "slot_token", "state", "event_id", "fingerprint"), "source_id=? AND source_sequence=?",
        arrayOf(source, sequence.toString()), null, null, null).use {
        if (!it.moveToFirst()) null else Slot(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4)).also { value ->
            check(value.peer.matches(Regex("[0-9a-f]{64}")) && tokenPattern.matches(value.token))
            check(when (value.state) {
                "held" -> value.eventId == null && value.hash == null
                "filled" -> value.eventId?.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) == true && value.hash?.matches(hashPattern) == true
                else -> false
            }) { "invalid source reservation metadata" }
        }
    }
    private fun firstHeld(db: SQLiteDatabase, source: String): Long? = db.rawQuery(
        "SELECT source_sequence FROM source_event_visibility_slots WHERE source_id=? AND state='held' ORDER BY source_sequence LIMIT 1",
        arrayOf(source)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun boundary(db: SQLiteDatabase, peer: String, source: String): Head {
        check(db.inTransaction()); identity(db, peer, source)
        val owner = db.query("source_event_receivers", arrayOf("owner_peer", "committed_sequence"), "source_id=?", arrayOf(source), null, null, null).use {
            if (it.moveToFirst()) { check(it.getType(1) == Cursor.FIELD_TYPE_INTEGER); it.getString(0) to it.getLong(1) } else null
        }
        val profile = db.query("source_event_visibility_receivers", arrayOf("owner_peer", "visibility_version", "covered_sequence"),
            "source_id=?", arrayOf(source), null, null, null).use {
            if (!it.moveToFirst()) null else {
                check(it.getType(1) == Cursor.FIELD_TYPE_INTEGER && it.getLong(1) == 1L && it.getType(2) == Cursor.FIELD_TYPE_INTEGER)
                it.getString(0) to it.getLong(2)
            }
        }
        if (owner == null) {
            check(profile == null && !db.rawQuery("SELECT 1 FROM source_events WHERE source_id=? LIMIT 1", arrayOf(source)).use { it.moveToFirst() } &&
                !db.rawQuery("SELECT 1 FROM source_event_visibility_slots WHERE source_id=? LIMIT 1", arrayOf(source)).use { it.moveToFirst() }) {
                "source history has lost its owner cursor"
            }
            return Head(peer, 0, 0, false, false)
        }
        require(owner.first == peer) { "source stream belongs to another authenticated peer" }
        check(owner.second in 0L until MAX)
        val covered = profile?.second ?: owner.second
        check(covered in owner.second until MAX && (profile == null || profile.first == peer))
        check(!db.rawQuery("SELECT 1 FROM source_events WHERE source_id=? AND source_sequence>? LIMIT 1",
            arrayOf(source, covered.toString())).use { it.moveToFirst() }) { "source originals exceed receive coverage" }
        if (profile == null) {
            check(owner.second > 0 && !db.rawQuery("SELECT 1 FROM source_event_visibility_slots WHERE source_id=? LIMIT 1",
                arrayOf(source)).use { it.moveToFirst() }) { "source reservations have lost their coverage cursor" }
        } else {
            check(!db.rawQuery("SELECT 1 FROM source_event_visibility_slots WHERE source_id=? AND source_sequence>? LIMIT 1",
                arrayOf(source, covered.toString())).use { it.moveToFirst() })
            val first = firstHeld(db, source)
            if (first != null) {
                check(first in 1L..covered && owner.second == first - 1)
                check(slot(db, source, first)?.peer == peer && record(db, source, first) == null)
            } else check(owner.second == covered)
        }
        if (owner.second > 0) check(record(db, source, owner.second) != null) { "original cursor has no committed record" }
        if (covered > 0) {
            val tail = record(db, source, covered); val marker = slot(db, source, covered)
            if (marker == null) check(tail != null) { "coverage cursor has no original or reservation" }
            else {
                check(marker.peer == peer)
                if (marker.state == "held") check(tail == null)
                else check(tail != null && tail.eventId == marker.eventId && tail.fingerprint == marker.hash)
            }
        }
        return Head(peer, covered, owner.second, profile != null, true)
    }
    private inline fun <T> read(db: SQLiteDatabase, block: () -> T): T {
        val own = !db.inTransaction(); if (own) db.beginTransactionNonExclusive()
        try { val result = block(); if (own) db.setTransactionSuccessful(); return result }
        finally { if (own) db.endTransaction() }
    }
    fun progress(db: SQLiteDatabase, peer: String, source: String, authorize: () -> Boolean): SourceVisibilityProgress = read(db) {
        check(authorize()); boundary(db, peer, source).progress().also { check(authorize()) }
    }
    /** Check archived rows under coverage without interpreting a held slot as
     * a row, event ID or permission to replay an external action. */
    fun readableHead(db: SQLiteDatabase, peer: String, source: String, sequence: Long): Long = read(db) {
        require(sequence in 1L until MAX)
        val head = boundary(db, peer, source)
        check(sequence <= head.covered)
        val marker = slot(db, source, sequence)
        if (marker != null) {
            check(marker.peer == peer && marker.state == "filled") { "original is withheld" }
            val original = checkNotNull(record(db, source, sequence))
            check(original.eventId == marker.eventId && original.fingerprint == marker.hash)
        }
        head.covered
    }
    fun held(db: SQLiteDatabase, peer: String, source: String, sequence: Long): Boolean = read(db) {
        require(sequence in 1L until MAX); val head = boundary(db, peer, source)
        slot(db, source, sequence)?.also { check(sequence <= head.covered && it.peer == peer) }?.state == "held"
    }
    fun token(db: SQLiteDatabase, peer: String, source: String, sequence: Long, authorize: () -> Boolean): String? = read(db) {
        require(sequence in 1L until MAX); check(authorize()); val head = boundary(db, peer, source)
        check(sequence <= head.covered)
        val value = slot(db, source, sequence)?.also {
            check(it.peer == peer)
            val original = record(db, source, sequence)
            if (it.state == "held") check(original == null)
            else check(original != null && original.eventId == it.eventId && original.fingerprint == it.hash)
        }?.token
        check(authorize()); value
    }
    fun enabled(db: SQLiteDatabase, peer: String, source: String, authorize: () -> Boolean): Boolean = read(db) {
        check(authorize()); identity(db, peer, source)
        val enabled = hasProfile(db, source)
        if (enabled) boundary(db, peer, source)
        check(authorize()); enabled
    }
    fun nextHeld(db: SQLiteDatabase, peer: String, source: String, after: Long, authorize: () -> Boolean): SourceVisibilitySlot? = read(db) {
        require(after in 0L until MAX); check(authorize()); val head = boundary(db, peer, source)
        val sequence = db.rawQuery("""SELECT source_sequence FROM source_event_visibility_slots
            WHERE source_id=? AND state='held' AND source_sequence>? ORDER BY source_sequence LIMIT 1""",
            arrayOf(source, after.toString())).use { if (it.moveToFirst()) it.getLong(0) else null }
        val result = sequence?.let {
            check(it in 1L..head.covered); val marker = checkNotNull(slot(db, source, it))
            check(marker.peer == peer && record(db, source, it) == null); SourceVisibilitySlot(it, marker.token)
        }
        check(authorize()); result
    }
    private fun activate(db: SQLiteDatabase, source: String, head: Head) {
        if (!head.owner) db.insertOrThrow("source_event_receivers", null, ContentValues().apply {
            put("source_id", source); put("owner_peer", head.peer); put("committed_sequence", 0)
        })
        if (!head.profile) db.insertOrThrow("source_event_visibility_receivers", null, ContentValues().apply {
            put("source_id", source); put("owner_peer", head.peer); put("visibility_version", 1); put("covered_sequence", head.covered)
        })
    }
    private fun advance(db: SQLiteDatabase, source: String, covered: Long) {
        val held = firstHeld(db, source); val complete = held?.minus(1) ?: covered
        check(complete in 0L..covered)
        if (complete > 0) check(record(db, source, complete) != null)
        check(db.update("source_event_visibility_receivers", ContentValues().apply { put("covered_sequence", covered) }, "source_id=?", arrayOf(source)) == 1)
        check(db.update("source_event_receivers", ContentValues().apply { put("committed_sequence", complete) }, "source_id=?", arrayOf(source)) == 1)
    }
    fun reserve(db: SQLiteDatabase, peer: String, source: String, sequence: Long, token: String, authorize: () -> Boolean): SourceVisibilityImport {
        require(sequence in 1L until MAX && tokenPattern.matches(token)); check(authorize()); db.beginTransaction()
        try {
            check(authorize()); val head = boundary(db, peer, source); val marker = slot(db, source, sequence)
            val existing = record(db, source, sequence)
            val state = when {
                marker != null && (marker.peer != peer || marker.token != token) -> "conflict"
                marker != null && marker.state == "filled" -> {
                    check(existing != null && existing.eventId == marker.eventId && existing.fingerprint == marker.hash); "original_present"
                }
                marker != null -> { check(existing == null && sequence <= head.covered); "held_duplicate" }
                existing != null -> { check(sequence <= head.covered); "original_present" }
                sequence <= head.covered -> error("covered original and reservation are both missing")
                sequence != head.covered + 1 -> "gap"
                else -> {
                    activate(db, source, head)
                    db.insertOrThrow("source_event_visibility_slots", null, ContentValues().apply {
                        put("source_id", source); put("source_sequence", sequence); put("owner_peer", peer); put("slot_token", token); put("state", "held")
                    })
                    advance(db, source, sequence); "held"
                }
            }
            val progress = if (state == "held") SourceVisibilityProgress(sequence + 1, head.original + 1) else head.progress()
            check(authorize()); db.setTransactionSuccessful(); return SourceVisibilityImport(state, progress)
        } finally { db.endTransaction() }
    }
    private fun clocks(db: SQLiteDatabase, incoming: BufferSourceEvent): Boolean {
        // A hidden legacy event may legitimately have an older clock. Do not
        // invent a clock for its reservation or compare past an unknown row.
        // Filling each slot checks both now-known immediate adjacencies.
        val previous = if (incoming.sequence > 1) record(db, incoming.source, incoming.sequence - 1) else null
        val next = record(db, incoming.source, incoming.sequence + 1)
        return (incoming.envelope.getString("provenance") != "native" || previous == null || incoming.logicalClock > previous.logicalClock) &&
            (next == null || next.envelope.getString("provenance") != "native" || next.logicalClock > incoming.logicalClock)
    }
    fun receive(db: SQLiteDatabase, peer: String, raw: String, token: String, authorize: () -> Boolean): SourceVisibilityImport {
        require(token.isEmpty() || tokenPattern.matches(token)); check(authorize())
        val incoming = BufferSourceEvent.decode(raw); db.beginTransaction()
        try {
            check(authorize()); val head = boundary(db, peer, incoming.source)
            val marker = slot(db, incoming.source, incoming.sequence)
            val existing = record(db, incoming.source, incoming.sequence)
            val reusedId = db.rawQuery("SELECT 1 FROM source_events WHERE event_id=? LIMIT 1", arrayOf(incoming.eventId)).use { it.moveToFirst() }
            val state = when {
                existing != null -> {
                    check(incoming.sequence <= head.covered)
                    if (marker != null) check(marker.peer == peer && marker.state == "filled" && marker.eventId == existing.eventId && marker.hash == existing.fingerprint)
                    if (existing.raw == raw && (token.isEmpty() || marker?.token == token)) "duplicate" else "conflict"
                }
                reusedId -> "conflict"
                marker != null && (marker.peer != peer || marker.state != "held" || marker.token != token) -> "conflict"
                incoming.sequence <= head.covered && marker == null -> error("covered original and reservation are both missing")
                incoming.sequence > head.covered + 1 -> "gap"
                marker == null && token.isNotEmpty() -> "conflict"
                !clocks(db, incoming) -> "conflict"
                else -> {
                    activate(db, incoming.source, head)
                    BufferEventJournal.archiveReceived(db, incoming)
                    if (marker != null) check(db.update("source_event_visibility_slots", ContentValues().apply {
                        put("state", "filled"); put("event_id", incoming.eventId); put("fingerprint", incoming.fingerprint)
                    }, "source_id=? AND source_sequence=? AND state='held' AND slot_token=?",
                        arrayOf(incoming.source, incoming.sequence.toString(), token)) == 1)
                    advance(db, incoming.source, maxOf(head.covered, incoming.sequence)); "stored"
                }
            }
            val progress = if (state == "stored") boundary(db, peer, incoming.source).progress() else head.progress()
            check(authorize()); db.setTransactionSuccessful(); return SourceVisibilityImport(state, progress)
        } finally { db.endTransaction() }
    }
}
