package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.BufferSourceBlobs
import com.mocharealm.foundation.fabric.buffer.data.local.SourceBlobTransfer
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Worker-owned, one bounded blob assembly. Jobs and complete files survive
 * reconnect; partial RAM prefixes are never advertised as durable progress. */
internal class VelaSourceBlobPull(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var transfer: SourceBlobTransfer? = null
    private var auditing = false
    private var auditAfter = ""
    private var auditDone = false
    private var assembly: ByteArray? = null
    private var used = 0
    private var wantedOffset = 0
    private var complete = false
    private var confirming = false
    private var storeFailures = 0
    private var pending: ByteArray? = null
    private var exchange = ""
    private var kind = ""
    private var attempts = 0
    private var requestStartedAt: Long? = null
    private var retryAt = 0L
    private var problem: String? = null
    private var closed = false

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }
    private fun number(j: JSONObject, key: String, min: Long, max: Long): Long {
        val n = j.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("invalid blob reply")
    private fun report(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun queue(type: String) {
        val ref = requireNotNull(transfer)
        exchange = UUID.randomUUID().toString().replace("-", "")
        val j = JSONObject().put("type", "buffer.events.blob.$type").put("protocol", 1).put("exchange_id", exchange)
            .put("source_id", ref.source).put("source_sequence", ref.sequence).put("record_sha256", ref.recordHash)
            .put("blob_sha256", ref.blobHash).put("total", ref.total)
        if (type == "get") { wantedOffset = used; j.put("offset", wantedOffset) }
        val bytes = j.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..maximum)
        pending?.fill(0); pending = bytes; kind = type; attempts = 0; retryAt = 0; requestStartedAt = null
    }
    private fun clearAssembly() { assembly?.fill(0); assembly = null; used = 0; wantedOffset = 0; complete = false }
    private fun clear() {
        clearAssembly(); pending?.fill(0); pending = null; transfer = null; exchange = ""; kind = ""; attempts = 0; confirming = false; storeFailures = 0; requestStartedAt = null
    }
    private fun requestExpired(now: Long) = requestStartedAt?.let { now < it || now - it >= 60_000 } == true
    private fun pause(state: String, now: Long) {
        val ref = transfer
        if (ref != null) {
            try { repository.deferSourceBlob(peer, ref, state, System.currentTimeMillis(), authorize) }
            catch (_: Exception) {
                if (!authorize()) close() else { report("local_storage"); attempts = 0; retryAt = now + 5000 }
                return
            }
            if (auditing) auditAfter = ref.eventId
        }
        clear(); retryAt = now + 100; report(state)
    }
    private fun persist(now: Long) {
        val ref = requireNotNull(transfer)
        try { repository.storeSourceBlob(peer, ref, requireNotNull(assembly), authorize) }
        catch (_: IllegalArgumentException) { pause("invalid_blob", now); return }
        catch (_: Exception) {
            if (!authorize()) close() else {
                storeFailures++
                if (storeFailures >= 3) { pause("local_storage", now); return }
                runCatching { repository.deferSourceBlob(peer, ref, "local_storage", System.currentTimeMillis(), authorize) }
                report("local_storage"); retryAt = now + 5000
            }
            return
        }
        if (!authorize()) { close(); return }
        storeFailures = 0; clearAssembly(); queue("accepted")
    }
    private fun confirm(now: Long) {
        val ref = requireNotNull(transfer)
        try { repository.confirmSourceBlob(peer, ref, authorize) }
        catch (_: Exception) {
            if (!authorize()) close() else {
                // A lost local commit or damaged file must be rechecked from
                // disk before another idempotent accepted request is sent.
                confirming = false; pending?.fill(0); pending = null; kind = ""; report("local_storage"); retryAt = now + 5000
                runCatching { repository.deferSourceBlob(peer, ref, "local_storage", System.currentTimeMillis(), authorize) }
            }
            return
        }
        if (!authorize()) { close(); return }
        if (auditing) auditAfter = ref.eventId
        clear(); problem = null; retryAt = now + 100
    }
    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        try {
            if (confirming) { confirm(now); return }
            if (complete) { persist(now); return }
            if (transfer == null) {
                val next = repository.nextSourceBlob(peer, System.currentTimeMillis(), if (auditDone) null else auditAfter, authorize)
                if (next == null) { auditDone = true; retryAt = now + 5000; return }
                transfer = next.transfer; auditing = next.audit
            }
            if (pending == null) {
                val ref = requireNotNull(transfer)
                if (repository.resumeSourceBlob(peer, ref, authorize)) queue("accepted")
                else { clearAssembly(); assembly = ByteArray(ref.total); queue("get") }
            }
            if (requestStartedAt == null) requestStartedAt = now
            if (requestExpired(now) || attempts >= 6) { pause("no_response", now); return }
            if (authorize() && send(requireNotNull(pending))) { attempts++; retryAt = now + 5000 } else retryAt = now + 100
        } catch (_: Exception) {
            if (!authorize()) close() else if (transfer != null) pause("local_storage", now)
            else { report("local_storage"); retryAt = now + 5000 }
        }
    }
    fun receive(frame: ByteArray, now: Long) {
        if (closed || !authorize()) return
        require(frame.size in 1..maximum)
        val raw = frame.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val j = BufferSourceEvent.parseObject(raw, maximum)
        if (pending == null || j.opt("exchange_id") != exchange) return
        if (requestExpired(now)) { pause("no_response", now); return }
        number(j, "protocol", 1, 1)
        val ref = requireNotNull(transfer)
        if (string(j, "type") == "buffer.events.error") {
            require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
            val delay = number(j, "retry_after_ms", 100, 60_000)
            when (val state = string(j, "state")) {
                "busy" -> {
                    attempts = 0
                    retryAt = minOf(now + delay.coerceAtLeast(1000), (requestStartedAt ?: now) + 60_000)
                    report(state)
                }
                "storage_error", "withheld", "unsupported", "conflict" -> pause(state, now)
                else -> error("invalid blob state")
            }
            return
        }
        val common = setOf("type", "protocol", "exchange_id", "source_id", "source_sequence", "record_sha256", "blob_sha256", "total")
        require(string(j, "source_id") == ref.source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == ref.sequence &&
            string(j, "record_sha256") == ref.recordHash && string(j, "blob_sha256") == ref.blobHash &&
            number(j, "total", 1, BufferSourceBlobs.MAX_BYTES.toLong()) == ref.total.toLong())
        when (string(j, "type")) {
            "buffer.events.blob.part" -> {
                require(kind == "get" && j.keys().asSequence().toSet() == common + setOf("offset", "blob_base64"))
                val start = number(j, "offset", 0, ref.total.toLong() - 1).toInt(); require(start == wantedOffset)
                val encoded = string(j, "blob_base64"); require(encoded.length in 1..4096)
                val data = Base64.getDecoder().decode(encoded)
                try {
                    require(data.size in 1..3072 && Base64.getEncoder().encodeToString(data) == encoded && start <= ref.total - data.size)
                    val target = requireNotNull(assembly); require(start <= used && target.size == ref.total)
                    if (start < used) require(start + data.size <= used && target.copyOfRange(start, start + data.size).contentEquals(data))
                    else { data.copyInto(target, start); used += data.size }
                    if (used == ref.total) { complete = true; persist(now) } else queue("get")
                } finally { data.fill(0) }
            }
            "buffer.events.blob.receipt" -> {
                require(kind == "accepted" && j.keys().asSequence().toSet() == common + "state" && string(j, "state") == "stored")
                confirming = true; pending?.fill(0); pending = null; kind = ""; confirm(now)
            }
            else -> error("invalid blob reply")
        }
    }
    override fun close() { closed = true; clear() }
}
