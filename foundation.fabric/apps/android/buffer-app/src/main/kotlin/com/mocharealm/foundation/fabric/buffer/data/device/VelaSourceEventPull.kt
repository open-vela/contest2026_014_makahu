package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** One authorized source stream, pulled on the application worker. Transport
 * acceptance never advances progress: only repository's durable receive cursor
 * does. This controller archives facts; it never invokes an action/projector. */
internal class VelaSourceEventPull(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var exchange = ""
    private var attempts = 0
    private var pending: ByteArray? = null
    private var kind = ""
    private var retryAt = 0L
    private var source = ""
    private var sequence = 1L
    private var head = 0L
    private var receiverNext = 1L
    private var receipts = true
    private var receiptBytes = 0
    private var wantedOffset = 0
    private var assembly: ByteArray? = null
    private var assembled = 0
    private var fingerprint = ""
    private var closed = false
    var problem: String? = null
        private set

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }

    private fun number(j: JSONObject, key: String, minimum: Long, maximum: Long): Long {
        val n = j.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in minimum..maximum)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String): String = j.get(key).also { require(it is String) } as String
    private fun base(type: String) = JSONObject().put("type", type).put("protocol", 1).put("exchange_id", exchange)
    private fun queue(type: String, j: JSONObject) {
        exchange = UUID.randomUUID().toString().replace("-", "")
        j.put("exchange_id", exchange)
        val bytes = j.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..maximum)
        pending?.fill(0); pending = bytes; kind = type; retryAt = 0; attempts = 0
    }
    private fun manifest() {
        val request = base("buffer.events.manifest.get")
        if (receipts) request.put("receipt_version", 1)
        queue("manifest", request)
    }
    private fun read() {
        wantedOffset = assembled
        queue("read", base("buffer.events.read").put("source_id", source).put("source_sequence", sequence)
            .put("offset", wantedOffset).put("record_sha256", fingerprint))
    }
    private fun acknowledge(record: BufferSourceEvent) {
        require(record.source == source && record.sequence == sequence)
        clearAssembly()
        fingerprint = record.fingerprint; receiptBytes = record.raw.toByteArray(Charsets.UTF_8).size
        queue("accepted", base("buffer.events.accepted").put("source_id", source).put("source_sequence", sequence)
            .put("record_sha256", fingerprint).put("total", receiptBytes))
    }
    private fun continuePull(now: Long) {
        if (sequence > head) { retryAt = now + 5000; problem = null; return }
        if (receipts && sequence < receiverNext) {
            val record = try { repository.receivedSourceEvent(peer, source, sequence, authorize) }
            catch (_: IllegalArgumentException) { pause("source_owner", now, 30_000); return }
            catch (_: Exception) {
                if (!authorize()) close() else pause("local_storage", now, 5000)
                return
            }
            if (!authorize()) { close(); return }
            acknowledge(record)
        } else read()
    }
    private fun clearAssembly() { assembly?.fill(0); assembly = null; assembled = 0; fingerprint = ""; wantedOffset = 0; receiptBytes = 0 }
    private fun pause(state: String, now: Long, delay: Long) {
        clearAssembly(); pending?.fill(0); pending = null; kind = ""; retryAt = now + delay
        if (problem != state) { problem = state; onProblem(state) }
    }
    fun refresh() { if (!closed && pending == null) retryAt = 0 }
    fun idle(): Boolean = pending == null
    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        if (pending == null) manifest()
        if (attempts >= 6) {
            // Older source-pull peers only accept the three-field manifest.
            // Keep their original receive path, without inventing receipts.
            if (kind == "manifest" && receipts) { receipts = false; manifest() }
            else { pause("no_response", now, 60_000); return }
        }
        val request = requireNotNull(pending)
        if (authorize() && send(request)) { attempts++; retryAt = now + 5000 } else retryAt = now + 100
    }
    fun receive(frame: ByteArray, now: Long) {
        if (closed || !authorize()) return
        require(frame.size in 1..maximum)
        val raw = frame.toString(Charsets.UTF_8)
        require(raw.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val j = BufferSourceEvent.parseObject(raw, maximum)
        if (j.opt("exchange_id") != exchange) return
        require(number(j, "protocol", 1, 1) == 1L)
        if (pending == null) return
        when (string(j, "type")) {
            "buffer.events.manifest" -> {
                val fields = setOf("type", "protocol", "exchange_id", "source_id", "last_sequence") +
                    (if (receipts) setOf("receipt_version", "next_unacknowledged") else emptySet())
                require(kind == "manifest" && j.keys().asSequence().toSet() == fields)
                val stream = string(j, "source_id"); require(stream.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
                val visibility = try { repository.sourceVisibilityEnabled(peer, stream, authorize) }
                catch (_: IllegalArgumentException) { pause("source_owner", now, 30_000); return }
                catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000); return }
                if (visibility) { pause("visibility_required", now, 60_000); return }
                val last = number(j, "last_sequence", 0, BufferSourceEvent.MAX_COUNTER - 1)
                val nextReceipt = if (receipts) {
                    require(number(j, "receipt_version", 1, 1) == 1L)
                    number(j, "next_unacknowledged", 1, last + 1)
                } else last + 1
                val resume = try { repository.sourceEventResume(peer, stream, authorize) }
                catch (_: IllegalArgumentException) { pause("source_owner", now, 30_000); return }
                catch (_: Exception) {
                    if (!authorize()) close() else pause("local_storage", now, 5000)
                    return
                }
                if (last < resume - 1) { pause("history_rollback", now, 30_000); return }
                clearAssembly(); source = stream; sequence = minOf(resume, nextReceipt); receiverNext = resume; head = last
                pending?.fill(0); pending = null
                continuePull(now)
            }
            "buffer.events.part" -> receivePart(j, now)
            "buffer.events.accepted.reply" -> {
                require(receipts && kind == "accepted" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id",
                    "source_id", "source_sequence", "record_sha256", "total", "next_unacknowledged"))
                require(string(j, "source_id") == source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == sequence &&
                    string(j, "record_sha256") == fingerprint && number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()) == receiptBytes.toLong())
                number(j, "next_unacknowledged", sequence + 1, head + 1)
                sequence++; clearAssembly(); pending?.fill(0); pending = null; problem = null
                continuePull(now)
            }
            "buffer.events.error" -> {
                require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
                val delay = number(j, "retry_after_ms", 100, 60_000)
                when (val state = string(j, "state")) {
                    "busy", "storage_error" -> {
                        attempts = 0
                        retryAt = now + delay.coerceAtLeast(if (state == "storage_error") 5000 else 100)
                        if (problem != state) { problem = state; onProblem(state) }
                    }
                    "unsupported" -> pause(state, now, 60_000)
                    "receipt_gap" -> { clearAssembly(); manifest() }
                    "withheld", "not_found", "conflict" -> pause(state, now, 30_000)
                    else -> error("invalid source event state")
                }
            }
            else -> error("invalid source event reply")
        }
    }
    private fun receivePart(j: JSONObject, now: Long) {
        require(kind == "read" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id",
            "source_id", "source_sequence", "record_sha256", "offset", "total", "record_base64"))
        require(string(j, "source_id") == source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == sequence)
        val hash = string(j, "record_sha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
        val start = number(j, "offset", 0, BufferSourceEvent.MAX_BYTES.toLong()).toInt()
        val total = number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()).toInt()
        require(start == wantedOffset && (fingerprint.isEmpty() || fingerprint == hash))
        val encoded = string(j, "record_base64")
        val data = Base64.getDecoder().decode(encoded)
        try {
            require(data.size in 1..3072 && Base64.getEncoder().encodeToString(data) == encoded && start <= total - data.size)
            if (assembly == null) { require(start == 0); assembly = ByteArray(total); fingerprint = hash }
            val target = requireNotNull(assembly); require(target.size == total && start <= assembled)
            if (start < assembled) require(start + data.size <= assembled && target.copyOfRange(start, start + data.size).contentEquals(data))
            else { data.copyInto(target, start); assembled += data.size }
            if (assembled < total) { read(); return }
            val original = target.toString(Charsets.UTF_8)
            require(original.toByteArray(Charsets.UTF_8).contentEquals(target))
            val event = BufferSourceEvent.decode(original)
            require(event.source == source && event.sequence == sequence && event.fingerprint == fingerprint)
            check(authorize()) { "event receive authorization required" }
            val imported = try { repository.importSourceEvent(peer, original, authorize) }
            catch (_: Exception) {
                if (!authorize()) { close(); return }
                // Keep the complete assembly and retry its final request. A
                // local transaction failure is not a failed carrier response.
                attempts = 0; retryAt = now + 5000
                if (problem != "local_storage") { problem = "local_storage"; onProblem("local_storage") }
                return
            }
            if (!authorize()) { close(); return }
            when (imported.state) {
                "stored", "duplicate" -> {
                    check(imported.nextSequence in sequence + 1..BufferSourceEvent.MAX_COUNTER)
                    receiverNext = imported.nextSequence; problem = null
                    if (receipts) acknowledge(event)
                    else {
                        sequence = imported.nextSequence; clearAssembly(); pending?.fill(0); pending = null
                        continuePull(now)
                    }
                }
                "gap" -> { clearAssembly(); manifest() }
                "conflict" -> pause("conflict", now, 30_000)
                else -> error("invalid event import result")
            }
        } finally { data.fill(0) }
    }
    override fun close() { closed = true; pending?.fill(0); pending = null; exchange = ""; clearAssembly() }
}
