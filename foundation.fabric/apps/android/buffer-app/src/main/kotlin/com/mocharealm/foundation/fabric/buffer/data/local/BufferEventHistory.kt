package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryEntry
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryCursor
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceHistoryPage
import org.json.JSONException

/** Derived causal metadata only. No writes to cards, captures, legacy events,
 * source envelopes or execution queues. All methods use the repository monitor.
 * Business projection has a separate data-only worker stage. */
internal object BufferEventHistory {
    fun create(db: SQLiteDatabase) {
        createHistoryIndex(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_history (
            event_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, event_type TEXT NOT NULL,
            created_at INTEGER NOT NULL, logical_clock INTEGER NOT NULL, state TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_history_parents (
            event_id TEXT NOT NULL, parent_event_id TEXT NOT NULL,
            PRIMARY KEY(event_id,parent_event_id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_history_parent ON source_event_history_parents(parent_event_id,event_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS source_event_history_work (event_id TEXT PRIMARY KEY)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_history_changes (
            parent_event_id TEXT PRIMARY KEY, after_event_id TEXT NOT NULL)""")
        // Event IDs form a stable key across VACUUM; implicit rowids do not.
        // New facts also enqueue directly, including IDs below this cursor.
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_history_scan (
            id INTEGER PRIMARY KEY CHECK(id=1), after_event_id TEXT NOT NULL, complete INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_event_history_scan(id,after_event_id,complete) VALUES(1,'',0)")
        BufferSourceParents.create(db)
        BufferSourceBlobs.create(db)
        BufferLocalAudio.create(db)
    }
    fun createHistoryIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS source_events_history_page ON source_events(logical_clock,source_id,source_sequence)")
    }

    fun enqueue(db: SQLiteDatabase, id: String) {
        check(db.inTransaction())
        BufferSourceParents.arrived(db, id)
        BufferSourceLegacyMigration.enqueue(db,id)
        db.execSQL("INSERT OR IGNORE INTO source_event_history_work(event_id) VALUES(?)", arrayOf(id))
        wakeChildren(db, id)
    }

    private fun wakeChildren(db: SQLiteDatabase, id: String) {
        // A single parent can have arbitrarily many children. Do not fan them
        // all out in the fact's import transaction or lose an unfinished page.
        db.execSQL("""INSERT OR REPLACE INTO source_event_history_changes(parent_event_id,after_event_id)
            SELECT ?,'' WHERE EXISTS(SELECT 1 FROM source_event_history_parents WHERE parent_event_id=? LIMIT 1)""", arrayOf(id, id))
    }

    private fun fanOut(db: SQLiteDatabase) {
        val change = db.rawQuery("SELECT parent_event_id,after_event_id FROM source_event_history_changes ORDER BY rowid LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: return
        val children = db.rawQuery("""SELECT event_id FROM source_event_history_parents
            WHERE parent_event_id=? AND event_id>? ORDER BY event_id LIMIT 32""", arrayOf(change.first, change.second)).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        children.forEach { db.execSQL("INSERT OR IGNORE INTO source_event_history_work(event_id) VALUES(?)", arrayOf(it)) }
        if (children.size < 32) db.delete("source_event_history_changes", "parent_event_id=?", arrayOf(change.first))
        else check(db.update("source_event_history_changes", ContentValues().apply { put("after_event_id", children.last()) },
            "parent_event_id=?", arrayOf(change.first)) == 1)
    }

    /** Bounded metadata migration, not an unbounded first-open replay. */
    private fun scan(db: SQLiteDatabase, limit: Int): Boolean {
        val cursor = db.rawQuery("SELECT after_event_id,complete FROM source_event_history_scan WHERE id=1", null).use {
            check(it.moveToFirst()); it.getString(0) to (it.getInt(1) == 1)
        }
        if (cursor.second) return false
        val ids = db.rawQuery("SELECT event_id FROM source_events WHERE event_id>? ORDER BY event_id LIMIT ?",
            arrayOf(cursor.first, limit.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
        ids.forEach { enqueue(db, it) }
        check(db.update("source_event_history_scan", ContentValues().apply {
            put("after_event_id", ids.lastOrNull() ?: cursor.first); put("complete", if (ids.size < limit) 1 else 0)
        }, "id=1", null) == 1)
        return ids.size == limit
    }

    private class Corrupt : RuntimeException()

    /** Rebuild only derived metadata in the normal bounded worker. Existing
     * originals and completed external actions are never replayed or deleted. */
    fun requestRebuild(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            create(db)
            check(db.update("source_event_history_scan", ContentValues().apply {
                put("after_event_id", ""); put("complete", 0)
            }, "id=1", null) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun checkedOriginal(db: SQLiteDatabase, id: String): BufferSourceEvent? {
        check(db.inTransaction())
        return try { original(db, id) } catch (_: Corrupt) { null }
    }
    private fun original(db: SQLiteDatabase, id: String): BufferSourceEvent {
        if (!BufferEventJournal.readableOriginal(db, "event_id=?", arrayOf(id))) throw Corrupt()
        return db.query("source_events",
        arrayOf("envelope", "fingerprint", "source_id", "source_sequence", "logical_clock", "capture_id"),
        "event_id=?", arrayOf(id), null, null, null).use { rows ->
        if (!rows.moveToFirst()) throw Corrupt()
        val record = try { BufferSourceEvent.decode(rows.getString(0)) }
        catch (_: IllegalArgumentException) { throw Corrupt() }
        catch (_: IllegalStateException) { throw Corrupt() }
        catch (_: JSONException) { throw Corrupt() }
        val capture = record.event.optString("capture_id").takeUnless { record.event.isNull("capture_id") }
        if (record.eventId != id || record.fingerprint != rows.getString(1) || record.source != rows.getString(2) ||
            record.sequence != rows.getLong(3) || record.logicalClock != rows.getLong(4) || capture != rows.getString(5)) throw Corrupt()
        val committed = if (record.source == BufferEventJournal.source(db)) BufferEventJournal.head(db)
        else db.query("source_event_receivers", arrayOf("owner_peer"), "source_id=?",
            arrayOf(record.source), null, null, null).use {
            if (!it.moveToFirst() || !it.getString(0).matches(Regex("[0-9a-f]{64}"))) throw Corrupt()
            try { BufferEventVisibility.readableHead(db, it.getString(0), record.source, record.sequence) }
            catch (_: IllegalArgumentException) { throw Corrupt() }
            catch (_: IllegalStateException) { throw Corrupt() }
            catch (_: JSONException) { throw Corrupt() }
        }
        if (committed !in record.sequence until BufferSourceEvent.MAX_COUNTER) throw Corrupt()
        record
        }
    }

    /** Returns whether more work remains. Byte limit is a soft boundary: one
     * valid envelope can be 1 MiB, and each batch processes at least one. */
    fun reconcile(db: SQLiteDatabase, limit: Int = 8): Boolean {
        require(limit in 1..32)
        db.beginTransaction()
        try {
            val migrating = scan(db, limit)
            fanOut(db)
            val ids = db.rawQuery("SELECT event_id FROM source_event_history_work ORDER BY rowid LIMIT ?",
                arrayOf(limit.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            var bytes = 0
            for (id in ids) {
                if (bytes >= 2 * BufferSourceEvent.MAX_BYTES) break
                val old = db.query("source_event_history", arrayOf("state", "fingerprint", "logical_clock"), "event_id=?", arrayOf(id),
                    null, null, null).use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getLong(2)) else null }
                val record = checkedOriginal(db, id)
                var state = "corrupt"
                if (record != null) {
                    bytes += record.raw.toByteArray(Charsets.UTF_8).size
                    db.delete("source_event_history_parents", "event_id=?", arrayOf(id))
                    record.parents.forEach { parent ->
                        db.insertOrThrow("source_event_history_parents", null, ContentValues().apply {
                            put("event_id", id); put("parent_event_id", parent)
                        })
                    }
                    state = when {
                        record.event.getLong("schema_version") != 1L -> "unsupported_schema"
                        record.envelope.getString("provenance") == "legacy_local" -> "legacy"
                        else -> linkedState(db, record)
                    }
                    BufferSourceParents.observe(db, record)
                    BufferSourceBlobs.observe(db, record)
                }
                val fingerprint = record?.fingerprint.orEmpty()
                db.insertWithOnConflict("source_event_history", null, ContentValues().apply {
                    put("event_id", id); put("fingerprint", fingerprint); put("event_type", record?.event?.getString("type").orEmpty())
                    put("created_at", record?.event?.getLong("created_at") ?: 0L)
                    put("logical_clock", record?.logicalClock ?: 0L); put("state", state)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                db.delete("source_event_history_work", "event_id=?", arrayOf(id))
                if (record != null) BufferSourceProjection.ensureQueued(db,id)
                if (old != Triple(state, fingerprint, record?.logicalClock ?: 0L)) {
                    wakeChildren(db, id)
                    BufferSourceProjection.historyChanged(db, id)
                }
            }
            val pending = db.rawQuery("SELECT 1 FROM source_event_history_work LIMIT 1", null).use { it.moveToFirst() }
            val changes = db.rawQuery("SELECT 1 FROM source_event_history_changes LIMIT 1", null).use { it.moveToFirst() }
            db.setTransactionSuccessful()
            return migrating || pending || changes
        } finally { db.endTransaction() }
    }

    private fun linkedState(db: SQLiteDatabase, record: BufferSourceEvent): String {
        var missing = false; var waiting = false; var invalid = false; var blocked = false
        for (parent in record.parents) {
            db.rawQuery("""SELECT e.logical_clock,e.fingerprint,h.fingerprint,h.state,h.logical_clock,e.source_id,e.source_sequence
                FROM source_events e LEFT JOIN source_event_history h ON h.event_id=e.event_id WHERE e.event_id=?""",
                arrayOf(parent)).use { rows ->
                if (!rows.moveToFirst()) missing = true
                else {
                    if (!rows.isNull(3) && rows.getString(3) == "corrupt") blocked = true
                    else if (rows.isNull(3) || rows.getString(1) != rows.getString(2) || rows.getLong(0) != rows.getLong(4)) {
                        waiting = true
                        db.execSQL("INSERT OR IGNORE INTO source_event_history_work(event_id) VALUES(?)", arrayOf(parent))
                    } else {
                        if (rows.getLong(4) >= record.logicalClock ||
                            (rows.getString(5) == record.source && rows.getLong(6) >= record.sequence)) invalid = true
                        when (rows.getString(3)) {
                            "linked" -> Unit
                            "waiting_parent", "missing_parent" -> waiting = true
                            else -> blocked = true
                        }
                    }
                }
            }
        }
        return when {
            invalid -> "invalid_clock"
            missing -> "missing_parent"
            blocked -> "blocked_parent"
            waiting -> "waiting_parent"
            else -> "linked"
        }
    }

    /** UI only. Stable keyset pages avoid skipping records when new history
     * arrives, without loading all envelopes or interpreting their payloads. */
    fun page(db: SQLiteDatabase, capture: String?, before: BufferSourceHistoryCursor? = null, limit: Int = 32): BufferSourceHistoryPage {
        require((capture==null || capture.isNotBlank() && capture.length<=160) && limit in 1..64)
        if (before != null) require(before.clock in 0L..BufferSourceEvent.MAX_COUNTER &&
            before.sequence in 1L until BufferSourceEvent.MAX_COUNTER && before.source.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val local = BufferEventJournal.source(db)
        val pageSources = mutableSetOf<String>()
        val range = if (before == null) "" else """ AND (e.logical_clock<? OR (e.logical_clock=? AND
            (e.source_id<? OR (e.source_id=? AND e.source_sequence<?))))"""
        val args = if(capture==null)mutableListOf(local) else mutableListOf(capture, capture, local)
        if (before != null) args.addAll(listOf(before.clock.toString(), before.clock.toString(), before.source, before.source, before.sequence.toString()))
        args.add((limit + 1).toString())
        var older: BufferSourceHistoryCursor? = null
        val scope=if(capture==null)"(e.source_id=? OR r.owner_peer IS NOT NULL)" else """e.event_id IN (SELECT event_id FROM source_events WHERE capture_id=?
                UNION SELECT event_id FROM source_event_projection_details WHERE capture_id=?)
            AND (e.source_id=? OR r.owner_peer=c.source_device OR EXISTS (
                SELECT 1 FROM source_projection_documents x WHERE x.owner_peer=r.owner_peer AND x.source_id=e.source_id
                AND x.document_id=d.document_id AND d.fingerprint=e.fingerprint AND d.outcome='applied') OR EXISTS (
                SELECT 1 FROM source_projection_observations x WHERE x.owner_peer=r.owner_peer AND x.source_id=e.source_id
                AND x.card_id=d.document_id AND d.fingerprint=e.fingerprint AND d.outcome='applied'))"""
        val entries = db.rawQuery("""SELECT e.event_id,e.source_id,e.fingerprint,h.fingerprint,h.event_type,h.created_at,h.state,
            e.logical_clock,e.source_sequence,r.owner_peer,p.state,d.fingerprint,d.outcome,e.capture_id
            FROM source_events e LEFT JOIN source_event_history h ON h.event_id=e.event_id
            LEFT JOIN source_event_receivers r ON r.source_id=e.source_id
            LEFT JOIN source_event_projections p ON p.event_id=e.event_id
            LEFT JOIN source_event_projection_details d ON d.event_id=e.event_id
            LEFT JOIN captures c ON c.capture_id=COALESCE(e.capture_id,d.capture_id)
            WHERE $scope""" + range +
            " ORDER BY e.logical_clock DESC,e.source_id DESC,e.source_sequence DESC LIMIT ?", args.toTypedArray()).use { rows -> buildList {
                while (rows.moveToNext()) {
                    if (size == limit) break
                    if (rows.getString(1) != local) pageSources.add(rows.getString(1))
                    val checked = !rows.isNull(6) && rows.getString(2) == rows.getString(3)
                    val state = if (checked) rows.getString(6) else if (rows.isNull(6)) "pending" else "corrupt"
                    val discovery = if (state == "missing_parent" && !rows.isNull(9))
                        BufferSourceParents.historyState(db, rows.getString(9), rows.getString(0)) else null
                    add(BufferSourceHistoryEntry(rows.getString(0), if (checked) rows.getString(4) else "",
                        if (checked) rows.getLong(5) else 0L, rows.getString(1) == local,
                        discovery ?: state, BufferSourceBlobs.state(db, rows.getString(0)) ?:
                            if (rows.getString(1) == local && !rows.isNull(13)) BufferLocalAudio.state(db, rows.getString(13), rows.getString(0)) else null,
                        if (rows.isNull(10)) null else if (rows.isNull(11) || rows.getString(11) != rows.getString(2)) "pending"
                        else if (rows.getString(10) == "applied" && rows.getString(12) in setOf("local_override", "legacy_preserved")) "local_override"
                        else if (rows.getString(10) == "applied" && rows.getString(12) == "superseded") "superseded"
                        else rows.getString(10),rows.getString(1),if(rows.isNull(13))null else rows.getString(13)))
                    older = BufferSourceHistoryCursor(rows.getLong(7), rows.getString(1), rows.getLong(8))
                }
                if (rows.isAfterLast) older = null
            } }
        val held = if(capture==null)db.rawQuery("""SELECT 1 FROM source_event_visibility_slots s
            JOIN source_event_visibility_receivers r ON r.source_id=s.source_id AND r.owner_peer=s.owner_peer
            WHERE s.state='held' LIMIT 1""",null).use { it.moveToFirst() }
        else pageSources.any { source -> db.rawQuery("""SELECT 1 FROM source_event_visibility_slots
            WHERE source_id=? AND state='held' LIMIT 1""", arrayOf(source)).use { it.moveToFirst() } }
        return BufferSourceHistoryPage(entries.reversed(), older, held)
    }

    /** Verify the saved original again for each bounded display page. No
     * regenerated JSON, Capture materialization or history execution. */
    fun originalPage(db: SQLiteDatabase,id: String,offset: Int = 0): com.mocharealm.foundation.fabric.buffer.domain.BufferSourceOriginalPage {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val record = run {
            db.beginTransactionNonExclusive()
            try {
                val checked = checkedOriginal(db,id) ?: error("source original cannot be verified")
                db.setTransactionSuccessful(); checked
            } finally { db.endTransaction() }
        }
        val raw=record.raw;require(offset in raw.indices && (offset==0 || !raw[offset].isLowSurrogate()))
        var end=minOf(raw.length,offset+2048)
        if(end<raw.length && raw[end-1].isHighSurrogate())end--
        var previous=maxOf(0,offset-2048)
        if(previous>0 && raw[previous].isLowSurrogate())previous--
        return com.mocharealm.foundation.fabric.buffer.domain.BufferSourceOriginalPage(id,record.source,record.sequence,
            raw.substring(offset,end),offset,raw.length,if(offset==0)null else previous,if(end==raw.length)null else end)
    }
}
