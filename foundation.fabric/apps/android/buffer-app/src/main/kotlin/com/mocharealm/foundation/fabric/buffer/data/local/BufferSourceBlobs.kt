package com.mocharealm.foundation.fabric.buffer.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.RecoveryFiles
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceAudioState
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal data class SourceBlobTransfer(val eventId: String, val source: String, val sequence: Long,
    val recordHash: String, val blobHash: String, val total: Int, val contentType: String = "audio/wav")
internal data class SourceBlobCandidate(val transfer: SourceBlobTransfer, val audit: Boolean)

/** Immutable archive and mutable delivery metadata. This never creates a
 * capture/card, appends a source event or schedules transcription/execution. */
internal object BufferSourceBlobs {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_DELAY = 300_000L
    private val hashPattern = Regex("[0-9a-f]{64}")

    private val receiverColumns = listOf("event_id", "owner_peer", "source_id", "source_sequence", "record_sha256", "blob_sha256",
        "total", "content_type", "state", "retry_at", "problem")
    fun createDamageStorage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS source_event_blob_receiver_damage (damage_id INTEGER PRIMARY KEY AUTOINCREMENT,${receiverColumns.joinToString(",")})")
    }
    fun create(db: SQLiteDatabase) {
        createDamageStorage(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS source_event_blob_receivers (
            event_id TEXT PRIMARY KEY, owner_peer TEXT NOT NULL, source_id TEXT NOT NULL, source_sequence INTEGER NOT NULL,
            record_sha256 TEXT NOT NULL, blob_sha256 TEXT NOT NULL, total INTEGER NOT NULL, content_type TEXT NOT NULL DEFAULT 'audio/wav',
            state TEXT NOT NULL, retry_at INTEGER NOT NULL DEFAULT 0, problem TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_blob_pending ON source_event_blob_receivers(owner_peer,retry_at,event_id)
            WHERE state IN ('pending','stored') OR (state='confirmed' AND problem<>'')""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS source_event_blob_audit ON source_event_blob_receivers(owner_peer,event_id)
            WHERE state='confirmed' AND problem=''""")
    }

    private fun integer(j: JSONObject, key: String, min: Long, max: Long): Long {
        val n = j.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
        return n.toLong()
    }
    private fun descriptor(record: BufferSourceEvent): SourceBlobTransfer {
        require(record.event.getLong("schema_version") == 1L && record.event.getString("type") in setOf("CaptureCreated", "LegacyAudioSnapshot", "CaptureBlobDescribed"))
        val blob = record.event.getJSONObject("payload").getJSONObject("blob")
        require(blob.keys().asSequence().toSet() == setOf("sha256", "bytes", "content_type", "created_at", "duration_ms", "mode"))
        val hash = blob.get("sha256"); require(hash is String && hashPattern.matches(hash))
        val contentType = blob.get("content_type")
        require(contentType is String && contentType in setOf("audio/wav", "audio/mp4", "application/octet-stream") &&
            blob.get("mode") in setOf("normal", "focus", "bedtime", "rabbit_hole"))
        integer(blob, "created_at", 0, BufferSourceEvent.MAX_COUNTER)
        integer(blob, "duration_ms", 0, 4_294_967_295L)
        val total = integer(blob, "bytes", 1, MAX_BYTES.toLong()).toInt()
        return SourceBlobTransfer(record.eventId, record.source, record.sequence, record.fingerprint, hash, total, contentType)
    }

    fun observe(db: SQLiteDatabase, record: BufferSourceEvent) {
        check(db.inTransaction())
        if (!record.event.getJSONObject("payload").has("blob")) return
        val peer = db.query("source_event_receivers", arrayOf("owner_peer"), "source_id=?", arrayOf(record.source),
            null, null, null).use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        check(peer.matches(Regex("[0-9a-f]{64}")))
        val ref = try { descriptor(record) }
        catch (_: IllegalArgumentException) { null }
        catch (_: org.json.JSONException) { null }
        // Only the verified original and its durable source owner establish
        // this descriptor. A damaged delivery cache cannot claim a receipt.
        val expected = linkedMapOf<String, Any>("owner_peer" to peer, "source_id" to record.source,
            "source_sequence" to record.sequence, "record_sha256" to record.fingerprint,
            "blob_sha256" to ref?.blobHash.orEmpty(), "total" to (ref?.total ?: 0),
            "content_type" to (ref?.contentType ?: "application/octet-stream"))
        val mismatch = expected.entries.joinToString(" OR ") { (name, value) ->
            "typeof($name)<>'${if (value is Number) "integer" else "text"}' OR $name IS NOT ?"
        } + " OR typeof(state)<>'text' OR state NOT IN ('pending','stored','confirmed','unsupported','conflict')" +
            " OR typeof(retry_at)<>'integer' OR retry_at<0 OR typeof(problem)<>'text'" +
            " OR problem NOT IN ('','withheld','unsupported','conflict','no_response','storage_error','local_storage','invalid_blob','descriptor_conflict')" +
            (if (ref != null) " OR state='conflict'" else " OR state<>'unsupported'")
        val damaged = db.rawQuery("SELECT 1 FROM source_event_blob_receivers WHERE event_id=? AND ($mismatch)",
            (listOf(record.eventId) + expected.values.map { it.toString() }).toTypedArray()).use { it.moveToFirst() }
        if (damaged) {
            db.execSQL("INSERT INTO source_event_blob_receiver_damage(${receiverColumns.joinToString(",")}) SELECT ${receiverColumns.joinToString(",")} FROM source_event_blob_receivers WHERE event_id=?",
                arrayOf(record.eventId))
            check(db.delete("source_event_blob_receivers", "event_id=?", arrayOf(record.eventId)) == 1)
        }
        db.execSQL("""INSERT OR IGNORE INTO source_event_blob_receivers(
            event_id,owner_peer,source_id,source_sequence,record_sha256,blob_sha256,total,content_type,state)
            VALUES(?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(record.eventId, peer, record.source, record.sequence,
            record.fingerprint, ref?.blobHash.orEmpty(), ref?.total ?: 0,
            ref?.contentType ?: "application/octet-stream", if (ref == null) "unsupported" else "pending"))
        if (ref != null) db.update("source_event_blob_receivers", ContentValues().apply {
            put("blob_sha256", ref.blobHash); put("total", ref.total); put("content_type", ref.contentType)
            put("state", "pending"); put("retry_at", 0); put("problem", "")
        }, "event_id=? AND owner_peer=? AND source_id=? AND source_sequence=? AND record_sha256=? AND state='unsupported'",
            arrayOf(record.eventId, peer, record.source, record.sequence.toString(), ref.recordHash))
    }

    /** Recheck derived descriptors even when no new history arrives. The
     * cursor only schedules bounded work; it grants no receive progress. */
    fun reconcileDescriptors(db: SQLiteDatabase, after: String): String {
        check(db.inTransaction())
        val ids = db.rawQuery("""SELECT e.event_id FROM source_events e JOIN source_event_receivers r ON r.source_id=e.source_id
            JOIN source_event_history h ON h.event_id=e.event_id AND h.fingerprint=e.fingerprint
            WHERE e.event_id>? AND h.state<>'corrupt'
            AND h.event_type IN ('CaptureCreated','LegacyAudioSnapshot','CaptureBlobDescribed')
            ORDER BY e.event_id LIMIT 4""", arrayOf(after)).use { rows ->
            buildList { while(rows.moveToNext()) add(rows.getString(0)) }
        }
        for (id in ids) {
            val original = BufferEventHistory.checkedOriginal(db, id)
            if (original == null) BufferEventHistory.enqueue(db, id) else observe(db, original)
        }
        return if (ids.size < 4) "" else ids.last()
    }

    private fun validate(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) {
        check(authorize())
        val record = BufferEventJournal.received(db, peer, ref.source, ref.sequence, authorize)
        check(descriptor(record) == ref) { "blob descriptor no longer matches committed original" }
        db.query("source_event_blob_receivers", arrayOf("owner_peer", "record_sha256", "blob_sha256", "total", "source_id", "source_sequence", "content_type"),
            "event_id=?", arrayOf(ref.eventId), null, null, null).use {
            check(it.moveToFirst() && it.getString(0) == peer && it.getString(1) == ref.recordHash &&
                it.getString(2) == ref.blobHash && it.getInt(3) == ref.total && it.getString(4) == ref.source && it.getLong(5) == ref.sequence &&
                it.getString(6) == ref.contentType)
        }
        check(authorize())
    }

    fun next(db: SQLiteDatabase, peer: String, now: Long, auditAfter: String?, authorize: () -> Boolean): SourceBlobCandidate? {
        require(peer.matches(Regex("[0-9a-f]{64}")) && now in 0L..(Long.MAX_VALUE - MAX_DELAY))
        check(authorize())
        fun select(audit: Boolean): SourceBlobCandidate? {
            val condition = if (audit) "state='confirmed' AND problem='' AND event_id>?" else "(state IN ('pending','stored') OR (state='confirmed' AND problem<>''))"
            val args = mutableListOf(peer, now.toString(), (now + MAX_DELAY).toString())
            if (audit) args.add(requireNotNull(auditAfter))
            repeat(8) {
                val ref = db.rawQuery("""SELECT event_id,source_id,source_sequence,record_sha256,blob_sha256,total,content_type
                    FROM source_event_blob_receivers WHERE owner_peer=? AND (retry_at<=? OR retry_at>?) AND """ + condition +
                    (if (audit) " ORDER BY event_id" else " ORDER BY retry_at,event_id") + " LIMIT 1", args.toTypedArray()).use {
                    if (it.moveToFirst()) SourceBlobTransfer(it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getString(4), it.getInt(5), it.getString(6)) else null
                } ?: return null
                val valid = try { validate(db, peer, ref, authorize); true }
                catch (_: IllegalArgumentException) { false }
                catch (_: IllegalStateException) { false }
                catch (_: org.json.JSONException) { false }
                check(authorize())
                if (valid) return SourceBlobCandidate(ref, audit)
                db.beginTransaction()
                try {
                    check(authorize())
                    check(db.update("source_event_blob_receivers", ContentValues().apply {
                        put("state", "conflict"); put("problem", "descriptor_conflict")
                    }, "event_id=? AND owner_peer=?", arrayOf(ref.eventId, peer)) == 1)
                    check(authorize()); db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            error("blob index requires review")
        }
        return (select(false) ?: if (auditAfter != null) select(true) else null).also { check(authorize()) }
    }

    private fun syncDirectory(directory: File) {
        check(java.nio.file.Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }
    private fun root(files: File): File {
        val root = File(files, "source-blobs")
        if (!root.exists()) check(root.mkdir() || root.isDirectory) { "cannot create source blob archive" }
        check(OsConstants.S_ISDIR(Os.lstat(root.absolutePath).st_mode))
        Os.chmod(root.absolutePath, 448) // 0700
        syncDirectory(files)
        return root
    }
    private fun path(files: File, ref: SourceBlobTransfer): File {
        require(hashPattern.matches(ref.blobHash) && ref.total in 1..MAX_BYTES)
        val suffix = when (ref.contentType) { "audio/wav" -> "wav"; "audio/mp4" -> "mp4"; "application/octet-stream" -> "blob"; else -> error("unsupported blob type") }
        return File(root(files), "${ref.blobHash}.$suffix")
    }
    private fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun originalDigest(bytes: ByteArray): String = fingerprint(bytes)
    fun originalTransfer(record: BufferSourceEvent): SourceBlobTransfer = descriptor(record)
    fun readOriginalArchive(files: File, record: BufferSourceEvent): ByteArray {
        val ref = descriptor(record)
        return requireNotNull(read(path(files, ref), ref)) { "original archive is missing" }
    }
    fun verifyOriginalBytes(record: BufferSourceEvent, bytes: ByteArray) = verify(bytes, descriptor(record))
    private fun verify(bytes: ByteArray, ref: SourceBlobTransfer) {
        require(bytes.size == ref.total && fingerprint(bytes) == ref.blobHash)
        // Archive original bytes, including undecodable recordings. Media
        // validation belongs to the existing ASR/player, never to raw copying.
    }
    private class ArchiveMismatch : IllegalStateException()
    private fun readDescriptor(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
        while (true) {
            try { return Os.read(fd, bytes, offset, count) }
            catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error }
        }
    }
    /** Reads through the same no-follow fd whose type/size is checked. */
    @Synchronized private fun read(file: File, ref: SourceBlobTransfer): ByteArray? {
        val fd = try { Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
        try {
            val st = Os.fstat(fd)
            if (!OsConstants.S_ISREG(st.st_mode) || st.st_size != ref.total.toLong() || (st.st_mode and 63) != 0) throw ArchiveMismatch()
            val bytes = ByteArray(ref.total)
            try {
                var used = 0
                while (used < bytes.size) {
                    val count = readDescriptor(fd, bytes, used, bytes.size - used)
                    if (count <= 0) throw ArchiveMismatch(); used += count
                }
                val extra = ByteArray(1)
                try { if (readDescriptor(fd, extra, 0, 1) != 0) throw ArchiveMismatch() } finally { extra.fill(0) }
                try { verify(bytes, ref) } catch (_: IllegalArgumentException) { throw ArchiveMismatch() }
                Os.fsync(fd)
                return bytes
            } catch (error: Throwable) { bytes.fill(0); throw error }
        }
        finally { Os.close(fd) }
    }
    @Synchronized private fun persist(files: File, ref: SourceBlobTransfer, bytes: ByteArray) {
        verify(bytes, ref)
        val destination = path(files, ref)
        // lstat rejects symlinks, including dangling ones; RecoveryFiles never
        // replaces an existing original with different bytes.
        try { check(OsConstants.S_ISREG(Os.lstat(destination.absolutePath).st_mode)) }
        catch (error: ErrnoException) { if (error.errno != OsConstants.ENOENT) throw error }
        try { read(destination, ref)?.fill(0) }
        catch (_: ArchiveMismatch) {
            // Only after a complete replacement matches the original's SHA:
            // retain corrupt bytes separately before restoring the hash path.
            val quarantine = File(destination.parentFile, "${ref.blobHash}.bad-${UUID.randomUUID()}")
            check(!quarantine.exists() && destination.renameTo(quarantine))
            Os.chmod(quarantine.absolutePath, 384); syncDirectory(destination.parentFile!!)
        }
        RecoveryFiles.save(destination, bytes)
        Os.chmod(destination.absolutePath, 384) // 0600
        val check = requireNotNull(read(destination, ref)); check.fill(0)
        syncDirectory(destination.parentFile!!)
    }

    fun describeOriginal(bytes: ByteArray, format: String, created: Long, duration: Long, mode: String): JSONObject {
        require(bytes.size in 1..MAX_BYTES && created in 0L..BufferSourceEvent.MAX_COUNTER && duration in 0L..4_294_967_295L)
        require(mode in setOf("normal", "focus", "bedtime", "rabbit_hole"))
        val mime = when (format.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)) {
            "audio/wav", "audio/x-wav", "pcm16/wav" -> "audio/wav"
            "audio/mp4" -> "audio/mp4"
            else -> "application/octet-stream"
        }
        val hash = fingerprint(bytes)
        return JSONObject().put("sha256", hash).put("bytes", bytes.size).put("content_type", mime)
            .put("created_at", created).put("duration_ms", duration).put("mode", mode)
    }

    fun archiveOriginal(files: File, bytes: ByteArray, format: String, created: Long, duration: Long, mode: String): JSONObject {
        val blob = describeOriginal(bytes, format, created, duration, mode)
        persist(files, SourceBlobTransfer("", "", 0, "", blob.getString("sha256"), bytes.size, blob.getString("content_type")), bytes)
        return blob
    }
    fun hasOriginalArchive(files: File, record: BufferSourceEvent): Boolean {
        val ref = descriptor(record)
        val destination = path(files, ref)
        val bytes = try { read(destination, ref) } catch (_: ArchiveMismatch) { null } ?: return false
        bytes.fill(0); syncDirectory(destination.parentFile!!); return true
    }
    fun archiveExpectedOriginal(files: File, record: BufferSourceEvent, bytes: ByteArray) = persist(files, descriptor(record), bytes)

    /** Local capture file snapshot before a historical descriptor exists. */
    @Synchronized fun snapshotOriginal(file: File, maximum: Int): ByteArray {
        require(maximum in 1..MAX_BYTES)
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            val st = Os.fstat(fd)
            check(OsConstants.S_ISREG(st.st_mode) && st.st_size in 1L..maximum.toLong() && (st.st_mode and 63) == 0) { "invalid original audio metadata: mode=${st.st_mode.toString(8)}, size=${st.st_size}" }
            val bytes = ByteArray(st.st_size.toInt())
            try {
                var used = 0
                while (used < bytes.size) { val n = readDescriptor(fd, bytes, used, bytes.size - used); check(n > 0); used += n }
                val extra = ByteArray(1)
                try { check(readDescriptor(fd, extra, 0, 1) == 0) } finally { extra.fill(0) }
                Os.fsync(fd); return bytes
            } catch (error: Throwable) { bytes.fill(0); throw error }
        } finally { Os.close(fd) }
    }

    fun syncCaptureDirectory(directory: File) = syncDirectory(directory)

    private fun mark(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, state: String, authorize: () -> Boolean) {
        db.beginTransaction()
        try {
            validate(db, peer, ref, authorize)
            check(db.update("source_event_blob_receivers", ContentValues().apply {
                put("state", state); put("retry_at", 0); put("problem", "")
            }, "event_id=? AND owner_peer=?", arrayOf(ref.eventId, peer)) == 1)
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Recover a file committed before its DB state; optionally reuse the old
     * raw-input archive only after matching the trusted capture and full SHA. */
    fun resume(db: SQLiteDatabase, files: File, captures: File, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean): Boolean {
        validate(db, peer, ref, authorize)
        val destination = path(files, ref)
        var data = try { read(destination, ref) } catch (_: ArchiveMismatch) { null }
        try {
            if (data == null) {
                val original = db.rawQuery("""SELECT c.blob_path FROM captures c JOIN source_events e ON e.capture_id=c.capture_id
                    WHERE e.event_id=? AND c.source_device=?""", arrayOf(ref.eventId, peer)).use { if (it.moveToFirst()) it.getString(0) else null }
                if (original != null) {
                    val file = File(original)
                    if (file.parentFile?.canonicalFile == captures.canonicalFile && file.name.endsWith(".bin")) {
                        data = try { read(file, ref) } catch (_: Exception) { null }
                        if (data != null) persist(files, ref, data)
                    }
                }
            }
            if (data == null) { mark(db, peer, ref, "pending", authorize); return false }
            syncDirectory(destination.parentFile!!)
            mark(db, peer, ref, "stored", authorize)
            return true
        } finally { data?.fill(0) }
    }

    fun store(db: SQLiteDatabase, files: File, peer: String, ref: SourceBlobTransfer, bytes: ByteArray, authorize: () -> Boolean) {
        validate(db, peer, ref, authorize); persist(files, ref, bytes)
        mark(db, peer, ref, "stored", authorize)
    }
    fun confirm(db: SQLiteDatabase, files: File, peer: String, ref: SourceBlobTransfer, authorize: () -> Boolean) {
        validate(db, peer, ref, authorize)
        val destination = path(files, ref); val bytes = requireNotNull(read(destination, ref)); bytes.fill(0)
        syncDirectory(destination.parentFile!!); mark(db, peer, ref, "confirmed", authorize)
    }
    fun defer(db: SQLiteDatabase, peer: String, ref: SourceBlobTransfer, reason: String, now: Long, authorize: () -> Boolean) {
        require(reason in setOf("withheld", "unsupported", "conflict", "no_response", "storage_error", "local_storage", "invalid_blob"))
        require(now in 0L..(Long.MAX_VALUE - MAX_DELAY)); check(authorize())
        db.beginTransaction()
        try {
            validate(db, peer, ref, authorize)
            val delay = when (reason) { "local_storage", "storage_error" -> 10_000L; "no_response" -> 60_000L; else -> MAX_DELAY }
            check(db.update("source_event_blob_receivers", ContentValues().apply { put("problem", reason); put("retry_at", now + delay) },
                "event_id=? AND owner_peer=?", arrayOf(ref.eventId, peer)) == 1)
            check(authorize()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun state(db: SQLiteDatabase, id: String): BufferSourceAudioState? = db.query("source_event_blob_receivers", arrayOf("state", "problem"),
        "event_id=?", arrayOf(id), null, null, null).use {
        if (!it.moveToFirst()) null else BufferSourceAudioState(it.getString(0), it.getString(1))
    }
    /** Explicit local recheck request. This grants no channel permission and
     * touches no files; the authorized controller revalidates the original. */
    fun requestCheck(db: SQLiteDatabase, id: String) {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        db.beginTransaction()
        try {
            check(db.update("source_event_blob_receivers", ContentValues().apply {
                put("state", "pending"); put("retry_at", 0); put("problem", "")
            }, "event_id=? AND state IN ('pending','stored','confirmed')", arrayOf(id)) == 1)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
