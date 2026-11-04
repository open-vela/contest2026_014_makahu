package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceAudioState
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import java.io.File
import org.json.JSONObject

/** Local-origin export pointers. File observations append a new fact; old
 * CaptureCreated/blob_ref/envelope bytes are never rewritten or re-owned. */
internal object BufferLocalAudio {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_local_audio (
            capture_id TEXT PRIMARY KEY, event_id TEXT, capture_event_id TEXT, state TEXT NOT NULL,
            retry_at INTEGER NOT NULL DEFAULT 0, problem TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_local_audio_due ON source_event_local_audio(state,retry_at,capture_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS source_event_local_audio_event ON source_event_local_audio(event_id,state)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_local_audio_scan (
            id INTEGER PRIMARY KEY CHECK(id=1), after_capture_id TEXT NOT NULL, complete INTEGER NOT NULL)""")
        db.execSQL("INSERT OR IGNORE INTO source_event_local_audio_scan(id,after_capture_id,complete) VALUES(1,'',0)")
        BufferSourceBlobOutgoing.create(db)
    }

    /** Called in appendEvent's transaction after the stable journal record. */
    fun appended(db: SQLiteDatabase, id: String, capture: String, type: String, payload: JSONObject) {
        check(db.inTransaction())
        if (type == "CaptureCreated" && !payload.has("blob_ref")) return
        if (type !in setOf("CaptureCreated", "CaptureBlobDescribed")) return
        if (payload.opt("local_original") != true || !payload.has("blob")) {
            db.execSQL("INSERT OR IGNORE INTO source_event_local_audio(capture_id,capture_event_id,state) VALUES(?,?,'pending')", arrayOf(capture, id))
            return
        }
        val original = BufferEventJournal.localRecord(db, id)
        check(original.event.getString("capture_id") == capture && original.event.getString("type") == type)
        val old = db.query("source_event_local_audio", arrayOf("event_id", "state"), "capture_id=?", arrayOf(capture),
            null, null, null).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
        if (old?.second == "ready") {
            val previous = BufferEventJournal.localRecord(db, checkNotNull(old.first))
            val before = previous.event.getJSONObject("payload").getJSONObject("blob")
            val after = payload.getJSONObject("blob")
            check(before.keys().asSequence().toSet() == after.keys().asSequence().toSet() &&
                listOf("sha256", "content_type", "mode").all { before.getString(it) == after.getString(it) } &&
                listOf("bytes", "created_at", "duration_ms").all { before.getLong(it) == after.getLong(it) })
            BufferSourceBlobOutgoing.ready(db, checkNotNull(old.first))
            return
        }
        db.insertWithOnConflict("source_event_local_audio", null, ContentValues().apply {
            put("capture_id", capture); put("event_id", id)
            put("capture_event_id", if (type == "CaptureCreated") id else payload.getString("capture_event_id"))
            put("state", if (type == "CaptureCreated") "pending" else "ready"); put("retry_at", 0); put("problem", "")
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        if (type == "CaptureBlobDescribed") BufferSourceBlobOutgoing.ready(db, id)
    }

    private fun scan(db: SQLiteDatabase, limit: Int): Boolean {
        val cursor = db.rawQuery("SELECT after_capture_id,complete FROM source_event_local_audio_scan WHERE id=1", null).use {
            check(it.moveToFirst()); it.getString(0) to (it.getInt(1) == 1)
        }
        if (cursor.second) return false
        // Walk distinct IDs by seeking the existing capture index. Do not
        // depend on derived captures rows surviving, or sort the entire log.
        val ids = mutableListOf<String>(); var after = cursor.first
        for (index in 0 until limit) {
            val next = db.rawQuery("""SELECT capture_id FROM source_events INDEXED BY source_events_capture_clock
                WHERE capture_id>? ORDER BY capture_id LIMIT 1""", arrayOf(after)).use { if (it.moveToFirst()) it.getString(0) else null } ?: break
            ids.add(next); after = next
        }
        ids.forEach { db.execSQL("INSERT OR IGNORE INTO source_event_local_audio(capture_id,state) VALUES(?,'pending')", arrayOf(it)) }
        check(db.update("source_event_local_audio_scan", ContentValues().apply {
            put("after_capture_id", ids.lastOrNull() ?: cursor.first); put("complete", if (ids.size < limit) 1 else 0)
        }, "id=1", null) == 1)
        return ids.size == limit
    }

    private fun status(db: SQLiteDatabase, capture: String, state: String, problem: String, retry: Long = 0) {
        check(db.update("source_event_local_audio", ContentValues().apply {
            put("state", state); put("problem", problem); put("retry_at", retry)
        }, "capture_id=?", arrayOf(capture)) == 1)
    }

    private fun exportReady(db: SQLiteDatabase, capture: String, id: String) {
        try { BufferSourceBlobOutgoing.ready(db, id) }
        catch (_: IllegalArgumentException) { status(db, capture, "conflict", "descriptor_conflict") }
        catch (_: IllegalStateException) { status(db, capture, "conflict", "descriptor_conflict") }
        catch (_: org.json.JSONException) { status(db, capture, "conflict", "descriptor_conflict") }
    }

    fun reconcile(db: SQLiteDatabase, files: File, captures: File, now: Long, limit: Int = 4,
                  append: (String, JSONObject) -> Unit): Boolean {
        require(limit in 1..8 && now in 0L..(Long.MAX_VALUE - 60_000))
        db.beginTransaction()
        try {
            val migrating = scan(db, limit)
            val ids = db.rawQuery("""SELECT capture_id FROM source_event_local_audio WHERE state IN ('pending','unavailable')
                AND (retry_at<=? OR retry_at>?) ORDER BY retry_at,capture_id LIMIT ?""",
                arrayOf(now.toString(), (now + 60_000).toString(), limit.toString())).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
            for (capture in ids) {
                val witnessId = db.rawQuery("SELECT event_id FROM events WHERE capture_id=? AND type='CaptureCreated' ORDER BY logical_clock,rowid LIMIT 1",
                    arrayOf(capture)).use { if (it.moveToFirst()) it.getString(0) else null }
                if (witnessId == null) { status(db, capture, "unverified", "missing_capture_fact"); continue }
                check(db.update("source_event_local_audio", ContentValues().apply { put("capture_event_id", witnessId) },
                    "capture_id=?", arrayOf(capture)) == 1)
                val witness = try { BufferEventJournal.localRecord(db, witnessId) }
                catch (_: IllegalArgumentException) { status(db, capture, "conflict", "capture_fact_conflict"); continue }
                catch (_: IllegalStateException) { status(db, capture, "conflict", "capture_fact_conflict"); continue }
                catch (_: org.json.JSONException) { status(db, capture, "conflict", "capture_fact_conflict"); continue }
                val event = witness.event; val payload = event.getJSONObject("payload")
                if (event.getString("type") != "CaptureCreated" || event.optString("capture_id") != capture) {
                    status(db, capture, "conflict", "capture_fact_conflict"); continue
                }
                if (!payload.has("blob_ref")) { status(db, capture, "not_audio", ""); continue }
                // Older UI-only context_local is independent of caller-supplied
                // LAN device names. An explicit newer false never falls back.
                val local = if (payload.has("local_original")) payload.opt("local_original") == true
                    else payload.opt("context_local") == true && payload.opt("device_id") == "android"
                if (!local) { status(db, capture, "unverified", "origin_not_proven"); continue }
                val known = try {
                    val pointer = db.rawQuery("SELECT event_id FROM source_event_local_audio WHERE capture_id=?", arrayOf(capture))
                        .use { check(it.moveToFirst()); it.getString(0) }
                    val record = if (pointer != null) BufferEventJournal.localRecord(db, pointer) else witness.takeIf { payload.has("blob") }
                    if (record != null) {
                        check(record.event.getString("capture_id") == capture && record.event.getJSONObject("payload").opt("local_original") == true)
                        if (record.eventId != witnessId) {
                            val link = record.event.getJSONObject("payload")
                            check(record.event.getString("type") == "CaptureBlobDescribed" && link.getString("capture_event_id") == witnessId &&
                                link.getString("capture_event_sha256") == witness.fingerprint)
                        }
                    }
                    record
                } catch (_: IllegalArgumentException) { status(db, capture, "conflict", "descriptor_conflict"); continue }
                catch (_: IllegalStateException) { status(db, capture, "conflict", "descriptor_conflict"); continue }
                catch (_: org.json.JSONException) { status(db, capture, "conflict", "descriptor_conflict"); continue }
                val blob = try {
                    require(capture.matches(Regex("[A-Za-z0-9._-]{1,80}")) && payload.getString("blob_ref") == "$capture.bin")
                    if (known != null && BufferSourceBlobs.hasOriginalArchive(files, known)) {
                        check(db.update("source_event_local_audio", ContentValues().apply {
                            put("event_id", known.eventId); put("state", "ready"); put("retry_at", 0); put("problem", "")
                        }, "capture_id=?", arrayOf(capture)) == 1)
                        exportReady(db, capture, known.eventId)
                        continue
                    }
                    check(captures.canonicalFile == File(files.canonicalFile, "captures"))
                    val file = File(captures, "$capture.bin")
                    val bytes = BufferSourceBlobs.snapshotOriginal(file, BufferConstants.maxAudioBytes)
                    try {
                        if (known != null) {
                            BufferSourceBlobs.archiveExpectedOriginal(files, known, bytes)
                            check(db.update("source_event_local_audio", ContentValues().apply {
                                put("event_id", known.eventId); put("state", "ready"); put("retry_at", 0); put("problem", "")
                            }, "capture_id=?", arrayOf(capture)) == 1)
                            exportReady(db, capture, known.eventId)
                            continue
                        }
                        BufferSourceBlobs.archiveOriginal(files, bytes, payload.getString("audio_format"), event.getLong("created_at"),
                            payload.getLong("duration_ms"), payload.getString("mode"))
                    } finally { bytes.fill(0) }
                } catch (error: android.database.sqlite.SQLiteException) { throw error }
                catch (_: org.json.JSONException) { status(db, capture, "conflict", "capture_metadata_conflict"); continue }
                catch (_: IllegalArgumentException) { status(db, capture, "conflict", "capture_metadata_conflict"); continue }
                catch (_: Exception) { status(db, capture, "unavailable", "original_file_unavailable", now + 60_000); continue }
                // This is a present file observation, not a recovered historical
                // checksum. No retroactive context collection or ASR request.
                append(capture, JSONObject().put("capture_id", capture).put("local_original", true)
                    .put("capture_event_id", witness.eventId).put("capture_event_sha256", witness.fingerprint)
                    .put("observation", "legacy_file_now").put("blob", blob))
            }
            val pending = db.rawQuery("SELECT 1 FROM source_event_local_audio WHERE state='pending' LIMIT 1", null).use { it.moveToFirst() }
            db.setTransactionSuccessful(); return migrating || pending
        } finally { db.endTransaction() }
    }

    fun state(db: SQLiteDatabase, capture: String, id: String): BufferSourceAudioState? = db.query("source_event_local_audio",
        arrayOf("event_id", "capture_event_id", "state"), "capture_id=?", arrayOf(capture), null, null, null).use {
        if (!it.moveToFirst() || (it.getString(0) != id && it.getString(1) != id)) null
        else when (it.getString(2)) {
            "ready" -> BufferSourceBlobOutgoing.state(db, checkNotNull(it.getString(0)))
            "pending" -> BufferSourceAudioState("local_pending", "")
            "unavailable" -> BufferSourceAudioState("local_unavailable", "")
            "unverified" -> BufferSourceAudioState("local_unverified", "")
            "conflict" -> BufferSourceAudioState("local_conflict", "")
            else -> null
        }
    }

    fun requestCheck(db: SQLiteDatabase, capture: String) {
        require(capture.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        db.beginTransaction()
        try {
            check(db.update("source_event_local_audio", ContentValues().apply { put("state", "pending"); put("problem", ""); put("retry_at", 0) },
                "capture_id=? AND state IN ('pending','unavailable','ready','conflict')", arrayOf(capture)) == 1)
            BufferSourceBlobOutgoing.requestCheck(db, capture)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
