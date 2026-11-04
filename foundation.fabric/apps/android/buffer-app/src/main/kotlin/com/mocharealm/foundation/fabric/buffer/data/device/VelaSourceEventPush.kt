package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Own-source delivery only. The authenticated receiver's cursor determines
 * restart position; per-event durable receipts are recovered by a hash proof,
 * and never authorize deleting the local immutable journal. Worker owned. */
internal class VelaSourceEventPush(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var source = ""
    private var head = 0L
    private var remoteNext = 1L
    private var scanAfter = 0L
    private var exchange = ""
    private var pending: ByteArray? = null
    private var kind = ""
    private var attempts = 0
    private var retryAt = 0L
    private var record: BufferSourceEvent? = null
    private var bytes: ByteArray? = null
    private var fingerprint = ""
    private var offset = 0
    private var end = 0
    private var receiptNext: Long? = null
    private var closed = false
    var problem: String? = null
        private set

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }
    private fun number(j: JSONObject, key: String, min: Long, max: Long): Long {
        val v = j.get(key); require(v is Number && v.toDouble().isFinite() && v.toDouble() == v.toLong().toDouble() && v.toLong() in min..max)
        return v.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key).also { require(it is String) }) as String
    private fun base(type: String) = JSONObject().put("type", type).put("protocol", 1)
    private fun queue(requestKind: String, j: JSONObject) {
        exchange = UUID.randomUUID().toString().replace("-", "")
        val frame = j.put("exchange_id", exchange).toString().toByteArray(Charsets.UTF_8)
        require(frame.size in 1..maximum)
        pending?.fill(0); pending = frame; kind = requestKind; attempts = 0; retryAt = 0
    }
    private fun clearRecord() {
        bytes?.fill(0); bytes = null; record = null; fingerprint = ""; offset = 0; end = 0; receiptNext = null
    }
    private fun note(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun pause(state: String, now: Long, delay: Long = 30_000) {
        pending?.fill(0); pending = null; kind = ""; clearRecord(); retryAt = now + delay; note(state)
    }
    private fun offer() {
        check(!repository.sourceVisibilitySendEnabled(peer, authorize)) { "visibility profile required" }
        val manifest = repository.sourceEventManifest(authorize)
        val current = string(manifest, "source_id")
        require(current.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        if (current != source) { source = current; scanAfter = 0 }
        head = number(manifest, "last_sequence", 0, BufferSourceEvent.MAX_COUNTER - 1)
        queue("offer", base("buffer.events.offer").put("source_id", source).put("last_sequence", head))
    }
    fun idle(): Boolean = pending == null && receiptNext == null
    private fun restart(now: Long) {
        // Discard the encoded fragment before dropping the object used for
        // live sharing checks, including when querying a new manifest fails.
        pending?.fill(0); pending = null; kind = ""; clearRecord()
        try { offer() }
        catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000) }
    }
    private fun select(now: Long) {
        val missing = repository.sourceEventWithoutReceipt(peer, scanAfter, authorize) ?: (head + 1)
        val sequence = minOf(remoteNext, missing)
        if (sequence > head) {
            scanAfter = maxOf(scanAfter, head); pending?.fill(0); pending = null; kind = ""; retryAt = now + 5000; return
        }
        val raw = repository.sourceEventPage(sequence - 1, 1, authorize).singleOrNull()
            ?: error("committed source original is missing")
        val selected = BufferSourceEvent.decode(raw)
        check(selected.source == source && selected.sequence == sequence) { "source stream has a local gap" }
        clearRecord(); record = selected; bytes = raw.toByteArray(Charsets.UTF_8); fingerprint = selected.fingerprint
        if (!repository.mayShareSourceEvent(selected, peer)) { pause("withheld", now); return }
        if (sequence < remoteNext) queue("proof", base("buffer.events.proof").put("source_id", source)
            .put("source_sequence", sequence).put("record_sha256", fingerprint).put("total", requireNotNull(bytes).size))
        else fragment()
    }
    private fun fragment() {
        val selected = requireNotNull(record); val original = requireNotNull(bytes)
        val capacity = minOf(3072, ((maximum - 512) / 4) * 3)
        check(capacity > 0 && offset < original.size)
        end = minOf(offset + capacity, original.size)
        val part = original.copyOfRange(offset, end)
        val encoded = try { Base64.getEncoder().encodeToString(part) } finally { part.fill(0) }
        queue("put", base("buffer.events.put").put("source_id", source).put("source_sequence", selected.sequence)
            .put("offset", offset).put("total", original.size).put("record_sha256", fingerprint).put("record_base64", encoded))
    }
    private fun saveReceipt(now: Long) {
        val next = receiptNext ?: return
        val selected = requireNotNull(record)
        try { repository.sourceEventDelivered(peer, selected.raw, authorize) }
        catch (_: Exception) { retryAt = now + 5000; note("local_storage"); return }
        if (!authorize()) { close(); return }
        remoteNext = next; scanAfter = maxOf(scanAfter, selected.sequence); clearRecord(); problem = null
        select(now)
    }
    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        try {
            if (receiptNext != null) { saveReceipt(now); return }
            val selected = record
            check(pending == null || kind == "offer" || selected != null)
            // Recheck live sharing policy for every send, including a cached
            // retry frame and hash-only proof. A cursor is never consent.
            if (selected != null && !repository.mayShareSourceEvent(selected, peer)) { pause("withheld", now); return }
            if (pending == null) offer()
            if (attempts >= 6) { pause("no_response", now, 60_000); return }
            if (authorize() && send(requireNotNull(pending))) { attempts++; retryAt = now + 5000 } else retryAt = now + 100
        } catch (_: Exception) {
            if (!authorize()) close() else pause("local_storage", now, 5000)
        }
    }
    fun receive(frame: ByteArray, now: Long) {
        if (closed || !authorize()) return
        require(frame.size in 1..maximum)
        val raw = frame.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val j = BufferSourceEvent.parseObject(raw, maximum)
        if (j.opt("exchange_id") != exchange || pending == null) return
        require(number(j, "protocol", 1, 1) == 1L)
        when (string(j, "type")) {
            "buffer.events.ready" -> {
                require(kind == "offer" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "source_id", "next_sequence"))
                require(string(j, "source_id") == source)
                val next = number(j, "next_sequence", 1, BufferSourceEvent.MAX_COUNTER)
                if (next > head + 1) { pause("history_rollback", now); return }
                remoteNext = next; pending?.fill(0); pending = null; problem = null
                try { select(now) } catch (_: Exception) { pause("local_storage", now, 5000) }
            }
            "buffer.events.receipt" -> receipt(j, now)
            "buffer.events.error" -> {
                require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
                val delay = number(j, "retry_after_ms", 100, 60_000)
                when (val state = string(j, "state")) {
                    "busy", "storage_error" -> { attempts = 0; retryAt = now + delay.coerceAtLeast(1000); note(state) }
                    "restart" -> restart(now)
                    "unsupported" -> pause(state, now, 60_000)
                    "conflict", "history_rollback", "source_owner", "invalid" -> pause(state, now)
                    else -> error("invalid source event state")
                }
            }
            else -> error("invalid source event reply")
        }
    }
    private fun receipt(j: JSONObject, now: Long) {
        require((kind == "put" || kind == "proof") && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id",
            "source_id", "source_sequence", "record_sha256", "next_offset", "total", "state", "next_sequence"))
        val selected = requireNotNull(record); val size = requireNotNull(bytes).size
        require(string(j, "source_id") == source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == selected.sequence &&
            string(j, "record_sha256") == fingerprint && number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()) == size.toLong())
        val next = number(j, "next_sequence", 1, BufferSourceEvent.MAX_COUNTER)
        val position = number(j, "next_offset", 0, size.toLong()).toInt()
        when (string(j, "state")) {
            "partial" -> {
                require(kind == "put" && position in end until size && next == selected.sequence)
                offset = position; fragment()
            }
            "stored", "duplicate" -> {
                require(position == size && next in selected.sequence + 1..head + 1)
                receiptNext = next; pending?.fill(0); pending = null; kind = ""
                saveReceipt(now)
            }
            "gap" -> restart(now)
            "conflict" -> pause("conflict", now)
            else -> error("invalid source receipt state")
        }
    }
    override fun close() { closed = true; pending?.fill(0); pending = null; clearRecord(); exchange = "" }
}
