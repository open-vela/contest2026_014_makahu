package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.BufferSourceBlobs
import com.mocharealm.foundation.fabric.buffer.data.local.SourceBlobTransfer
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Worker-owned own-source originals. A carrier ACK or source-event receipt
 * cannot confirm binary delivery. Complete-file proofs are rechecked once
 * per connection; partial prefixes restart through an offer after reconnect. */
internal class VelaSourceBlobPush(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var transfer: SourceBlobTransfer? = null
    private var original: ByteArray? = null
    private var pending: ByteArray? = null
    private var exchange = ""
    private var kind = ""
    private var offset = 0
    private var end = 0
    private var attempts = 0
    private var requestStartedAt: Long? = null
    private var restarts = 0
    private var retryAt = 0L
    private var confirming = false
    private var commitFailures = 0
    private var auditing = false
    private var auditAfter = ""
    private var auditDone = false
    private var problem: String? = null
    private var closed = false

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }
    private fun number(j: JSONObject, key: String, min: Long, max: Long): Long {
        val n = j.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("invalid blob reply")
    private fun report(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun clear() {
        pending?.fill(0); pending = null; original?.fill(0); original = null; transfer = null
        exchange = ""; kind = ""; offset = 0; end = 0; attempts = 0; restarts = 0; confirming = false; commitFailures = 0; requestStartedAt = null
    }
    private fun requestExpired(now: Long) = requestStartedAt?.let { now < it || now - it >= 60_000 } == true
    private fun pause(state: String, now: Long) {
        val ref = transfer; var saved = false
        if (ref != null) try {
            repository.deferOutgoingSourceBlob(peer, ref, state, System.currentTimeMillis(), authorize); saved = true
        } catch (_: Exception) { if (!authorize()) { close(); return } }
        if (auditing && saved) auditAfter = requireNotNull(ref).eventId
        clear(); report(if (ref != null && !saved) "local_storage" else state); retryAt = now + if (saved) 100 else 5000
    }
    private fun queue(type: String) {
        val ref = requireNotNull(transfer)
        val j = JSONObject().put("type", "buffer.events.blob.$type").put("protocol", 1)
            .put("source_id", ref.source).put("source_sequence", ref.sequence).put("record_sha256", ref.recordHash)
            .put("blob_sha256", ref.blobHash).put("total", ref.total)
        exchange = UUID.randomUUID().toString().replace("-", ""); j.put("exchange_id", exchange)
        if (type == "put") {
            val bytes = requireNotNull(original)
            val capacity = minOf(3072, ((maximum - 640) / 4) * 3)
            check(capacity > 0 && offset in 0 until bytes.size)
            end = minOf(offset + capacity, bytes.size)
            val part = bytes.copyOfRange(offset, end)
            val encoded = try { Base64.getEncoder().encodeToString(part) } finally { part.fill(0) }
            j.put("offset", offset).put("blob_base64", encoded)
        }
        val bytes = j.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..maximum)
        pending?.fill(0); pending = bytes; kind = type; attempts = 0; retryAt = 0; requestStartedAt = null
    }
    private fun confirm(now: Long) {
        val ref = requireNotNull(transfer)
        try { repository.outgoingSourceBlobDelivered(peer, ref, authorize) }
        catch (_: Exception) {
            if (!authorize()) close() else {
                commitFailures++
                if (commitFailures >= 3) { pause("local_storage", now); return }
                report("local_storage"); retryAt = now + 5000
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
            if (transfer == null) {
                val next = repository.nextOutgoingSourceBlob(peer, System.currentTimeMillis(), if (auditDone) null else auditAfter, authorize)
                if (next == null) {
                    // A bounded batch may have only rejected corrupt entries,
                    // or an archive may still be rechecking in the background.
                    if (!auditDone) auditDone = !repository.hasOutgoingSourceBlobAudit(peer, auditAfter, authorize)
                    retryAt = now + 5000; return
                }
                transfer = next.transfer; auditing = next.audit
            }
            val ref = requireNotNull(transfer)
            if (!repository.mayShareOutgoingSourceBlob(peer, ref, authorize)) { pause("withheld", now); return }
            if (pending == null) {
                original = repository.readOutgoingSourceBlob(peer, ref, authorize)
                queue("offer")
            }
            if (requestStartedAt == null) requestStartedAt = now
            if (requestExpired(now) || attempts >= 6) { pause("no_response", now); return }
            // Cached retransmissions are checked by the same live policy above.
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
                "event_missing", "storage_error", "withheld", "unsupported", "conflict" -> pause(state, now)
                else -> error("invalid outgoing blob state")
            }
            return
        }
        require(string(j, "type") == "buffer.events.blob.received" && j.keys().asSequence().toSet() ==
            setOf("type", "protocol", "exchange_id", "source_id", "source_sequence", "record_sha256", "blob_sha256", "total", "next_offset", "state"))
        require(string(j, "source_id") == ref.source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == ref.sequence &&
            string(j, "record_sha256") == ref.recordHash && string(j, "blob_sha256") == ref.blobHash &&
            number(j, "total", 1, BufferSourceBlobs.MAX_BYTES.toLong()) == ref.total.toLong())
        val next = number(j, "next_offset", 0, ref.total.toLong()).toInt()
        when (string(j, "state")) {
            "ready" -> { require(kind == "offer" && next == 0); offset = 0; queue("put") }
            "partial" -> { require(kind == "put" && next == end && next < ref.total); offset = next; queue("put") }
            "restart" -> {
                require(kind == "put" && next == 0); restarts++
                if (restarts >= 8) pause("storage_error", now) else { offset = 0; queue("offer") }
            }
            "stored" -> {
                require(next == ref.total)
                confirming = true; pending?.fill(0); pending = null; kind = ""; original?.fill(0); original = null
                confirm(now)
            }
            else -> error("invalid outgoing blob state")
        }
    }
    override fun close() { closed = true; clear() }
}
