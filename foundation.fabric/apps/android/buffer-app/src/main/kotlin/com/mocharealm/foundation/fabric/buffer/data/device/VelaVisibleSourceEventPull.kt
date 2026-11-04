package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.SourceVisibilityProgress
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Negotiated visibility-v1 pull. Reservations and original receipts remain
 * distinct. Only durable local imports earn ACKs. Fair held-slot probes never
 * suppress forward coverage, and neither path executes historical actions. */
internal class VelaVisibleSourceEventPull(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var legacy: VelaSourceEventPull? = null
    private var nextProbe = 0L
    private var negotiated = false
    private var closed = false
    private var pending: ByteArray? = null
    private var exchange = ""
    private var kind = ""
    private var attempts = 0
    private var requestStartedAt: Long? = null
    private var retryAt = 0L
    private var source = ""
    private var head = 0L
    private var senderCovered = 1L
    private var receiver = SourceVisibilityProgress(1, 1)
    private var forward = 1L
    private var sequence = 1L
    private var filling = false
    private var priorityFill = false
    private var preferParentFill = true
    private var wantFill = true
    private var fillAfter = 0L
    private var fillAt = 0L
    private var lastManifest = 0L
    private var senderChecking = false
    private var token = ""
    private var slotOnly = false
    private var assembly: ByteArray? = null
    private var used = 0
    private var wantedOffset = 0
    private var fingerprint = ""
    private var receiptBytes = 0
    private var problem: String? = null
    private var refreshRequested = false

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }
    private fun number(j: JSONObject, key: String, min: Long, max: Long): Long {
        val n = j.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("invalid visible reply")
    private fun report(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun base(suffix: String) = JSONObject().put("type", "buffer.events.visible.$suffix").put("protocol", 1).put("visibility_version", 1)
    private fun selected(suffix: String) = base(suffix).put("source_id", source).put("source_sequence", sequence).put("slot_token", token)
    private fun queue(suffix: String, j: JSONObject) {
        exchange = UUID.randomUUID().toString().replace("-", "")
        val bytes = j.put("exchange_id", exchange).toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..maximum)
        pending?.fill(0); pending = bytes; kind = suffix; attempts = 0; retryAt = 0; requestStartedAt = null
    }
    private fun clearAssembly() { assembly?.fill(0); assembly = null; used = 0; wantedOffset = 0; fingerprint = ""; receiptBytes = 0 }
    private fun clearRequest() { pending?.fill(0); pending = null; kind = ""; exchange = ""; clearAssembly(); token = ""; slotOnly = false; requestStartedAt = null }
    private fun requestExpired(now: Long) = requestStartedAt?.let { now < it || now - it >= 60_000 } == true
    private fun manifest() { clearRequest(); queue("manifest.get", base("manifest.get")) }
    private fun read() {
        wantedOffset = used
        queue("read", selected("read").put("offset", wantedOffset).put("record_sha256", fingerprint))
    }
    private fun originalAck(record: BufferSourceEvent) {
        check(record.source == source && record.sequence == sequence)
        clearAssembly(); fingerprint = record.fingerprint; receiptBytes = record.raw.toByteArray(Charsets.UTF_8).size
        queue("original.accepted", selected("original.accepted").put("record_sha256", fingerprint).put("total", receiptBytes))
    }
    private fun slotAck() { clearAssembly(); check(token.matches(Regex("[0-9a-f]{32}"))); queue("slot.accepted", selected("slot.accepted")) }
    private fun fallback(now: Long) {
        clearRequest()
        if (legacy == null) legacy = VelaSourceEventPull(repository, peer, maximum, authorize, send, onProblem)
        nextProbe = now + 60_000; retryAt = now; report("legacy_profile")
    }
    private fun pause(state: String, now: Long, delay: Long = 30_000) {
        if (filling && kind == "read") {
            if (!priorityFill) fillAfter = sequence
            wantFill = false
        }
        clearRequest(); retryAt = now + delay; report(state)
    }
    private fun select(now: Long) {
        clearRequest()
        if (refreshRequested) {
            // Discovery refreshes the manifest and wakes priority hints, but
            // does not restart the ordinary sweep at its first held slot.
            refreshRequested = false; fillAt = 0; manifest(); return
        }
        if (now - lastManifest >= (if (senderChecking) 1000 else 5000)) { manifest(); return }
        val canForward = forward <= head
        priorityFill = false
        val held = if (now >= fillAt && (wantFill || !canForward)) {
            val parent = if (preferParentFill) repository.nextSourceParentFill(peer, source, senderCovered,
                System.currentTimeMillis(), authorize) else null
            if (parent != null) {
                priorityFill = true; preferParentFill = false; parent
            } else {
                preferParentFill = true
                repository.nextHeldSourceEvent(peer, source, fillAfter, 1, authorize)
            }
        } else null
        if (held != null && held.sequence < senderCovered) {
            filling = true; sequence = held.sequence; token = held.token; read(); return
        }
        if (held == null && now >= fillAt && (wantFill || !canForward)) { fillAfter = 0; fillAt = now + 30_000 }
        if (!canForward) { retryAt = now + if (senderChecking) 1000 else 5000; return }
        filling = false; sequence = forward
        token = if (sequence < receiver.coveredNext) repository.sourceVisibilityToken(peer, source, sequence, authorize).orEmpty() else ""
        if (sequence < receiver.coveredNext) {
            val heldHere = repository.nextHeldSourceEvent(peer, source, sequence - 1, 1, authorize)?.sequence == sequence
            if (!heldHere) { originalAck(repository.receivedSourceEvent(peer, source, sequence, authorize)); return }
        }
        read()
    }
    private fun finish(now: Long) {
        if (filling) {
            // Prioritizing a causal dependency must not skip the ordinary
            // held-slot sweep or starve forward history delivery.
            if (!priorityFill) fillAfter = sequence
            wantFill = false
        }
        else { forward = sequence + 1; wantFill = true }
        clearRequest(); problem = null; retryAt = now
        select(now)
    }
    fun refresh() {
        if (closed) return
        refreshRequested = true
        legacy?.refresh()
        if (pending == null) { retryAt = 0; nextProbe = 0; lastManifest = 0 }
    }
    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        try {
            val old = legacy
            if (old != null && pending == null) {
                if (now >= nextProbe && old.idle()) manifest() else { old.tick(now); return }
            }
            if (pending == null) {
                if (negotiated && source.isNotEmpty()) select(now) else manifest()
                if (pending == null) return
            }
            if (requestStartedAt == null) requestStartedAt = now
            if (requestExpired(now)) { pause("no_response", now, 5000); return }
            if (attempts >= 6) {
                if (!negotiated && kind == "manifest.get") { fallback(now); return }
                pause("no_response", now, 60_000); return
            }
            if (authorize() && send(requireNotNull(pending))) { attempts++; retryAt = now + 5000 } else retryAt = now + 100
        } catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000) }
    }
    fun receive(frame: ByteArray, now: Long) {
        if (closed || !authorize()) return
        if (pending == null && legacy != null) { legacy?.receive(frame, now); return }
        require(frame.size in 1..maximum)
        val raw = frame.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val j = BufferSourceEvent.parseObject(raw, maximum)
        if (pending == null || j.opt("exchange_id") != exchange) return
        if (requestExpired(now)) { pause("no_response", now, 5000); return }
        number(j, "protocol", 1, 1)
        if (string(j, "type") == "buffer.events.error") { errorReply(j, now); return }
        number(j, "visibility_version", 1, 1)
        when (string(j, "type")) {
            "buffer.events.visible.manifest" -> receiveManifest(j, now)
            "buffer.events.visible.slot" -> receiveSlot(j, now)
            "buffer.events.visible.part" -> receivePart(j, now)
            "buffer.events.visible.receipt" -> receiveReceipt(j, now)
            else -> error("invalid visible reply")
        }
    }
    private fun receiveManifest(j: JSONObject, now: Long) {
        require(kind == "manifest.get" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "visibility_version",
            "source_id", "last_sequence", "covered_next", "original_next", "checking"))
        val stream = string(j, "source_id"); require(stream.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val last = number(j, "last_sequence", 0, BufferSourceEvent.MAX_COUNTER - 1)
        val covered = number(j, "covered_next", 1, last + 1); number(j, "original_next", 1, covered)
        val checking = j.get("checking"); require(checking is Boolean)
        val local = try { repository.sourceVisibilityProgress(peer, stream, 1, authorize) }
        catch (_: IllegalArgumentException) { pause("source_owner", now); return }
        catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000); return }
        if (last < local.coveredNext - 1) { pause("history_rollback", now); return }
        if (source != stream) { fillAfter = 0; fillAt = 0; preferParentFill = true }
        legacy?.close(); legacy = null; negotiated = true; source = stream; head = last
        senderCovered = covered; receiver = local; forward = minOf(local.coveredNext, covered); senderChecking = checking
        lastManifest = now; clearRequest(); problem = null; select(now)
    }
    private fun checkPosition(j: JSONObject) {
        require(string(j, "source_id") == source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == sequence)
    }
    private fun receiveSlot(j: JSONObject, now: Long) {
        require(kind == "read" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "visibility_version", "source_id", "source_sequence", "slot_token"))
        checkPosition(j)
        val reservation = string(j, "slot_token"); require(reservation.matches(Regex("[0-9a-f]{32}")) && (token.isEmpty() || token == reservation))
        val imported = try { repository.reserveSourceVisibility(peer, source, sequence, reservation, 1, authorize) }
        catch (_: IllegalArgumentException) { pause("source_owner", now); return }
        catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000); return }
        when (imported.state) {
            "held", "held_duplicate", "original_present" -> {
                receiver = imported.progress; clearAssembly(); token = reservation
                if (imported.state == "held" && !filling) fillAt = 0
                if (imported.state == "original_present" && !slotOnly) originalAck(repository.receivedSourceEvent(peer, source, sequence, authorize)) else slotAck()
            }
            "gap" -> manifest()
            "conflict" -> pause("conflict", now)
            else -> error("invalid reservation result")
        }
    }
    private fun receivePart(j: JSONObject, now: Long) {
        require(kind == "read" && j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "visibility_version",
            "source_id", "source_sequence", "slot_token", "record_sha256", "offset", "total", "record_base64"))
        checkPosition(j); require(string(j, "slot_token") == token)
        val hash = string(j, "record_sha256"); require(hash.matches(Regex("[0-9a-f]{64}")) && (fingerprint.isEmpty() || fingerprint == hash))
        val start = number(j, "offset", 0, BufferSourceEvent.MAX_BYTES.toLong() - 1).toInt()
        val total = number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()).toInt(); require(start == wantedOffset)
        val encoded = string(j, "record_base64"); require(encoded.length in 1..4096)
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size in 1..3072 && Base64.getEncoder().encodeToString(bytes) == encoded && start <= total - bytes.size)
            if (assembly == null) { require(start == 0); assembly = ByteArray(total); fingerprint = hash }
            val target = requireNotNull(assembly); require(target.size == total && start <= used)
            if (start < used) require(start + bytes.size <= used && target.copyOfRange(start, start + bytes.size).contentEquals(bytes))
            else { bytes.copyInto(target, start); used += bytes.size }
            if (used < total) { read(); return }
            val original = target.toString(Charsets.UTF_8); require(original.toByteArray(Charsets.UTF_8).contentEquals(target))
            val record = BufferSourceEvent.decode(original); require(record.source == source && record.sequence == sequence && record.fingerprint == hash)
            val imported = try { repository.importVisibleSourceEvent(peer, original, token, 1, authorize) }
            catch (_: Exception) {
                if (!authorize()) close() else { attempts = 0; retryAt = now + 5000; report("local_storage") }; return
            }
            when (imported.state) {
                "stored", "duplicate" -> { receiver = imported.progress; originalAck(record) }
                "gap" -> manifest()
                "conflict" -> pause("conflict", now)
                else -> error("invalid visible import")
            }
        } finally { bytes.fill(0) }
    }
    private fun receiveReceipt(j: JSONObject, now: Long) {
        val original = kind == "original.accepted"; require(original || kind == "slot.accepted")
        val fields = setOf("type", "protocol", "exchange_id", "visibility_version", "source_id", "source_sequence", "slot_token",
            "receipt_kind", "covered_next", "original_next", "checking") + if (original) setOf("record_sha256", "total") else emptySet()
        require(j.keys().asSequence().toSet() == fields); checkPosition(j)
        require(string(j, "slot_token") == token && string(j, "receipt_kind") == (if (original) "original" else "slot"))
        if (original) require(string(j, "record_sha256") == fingerprint && number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()) == receiptBytes.toLong())
        val covered = number(j, "covered_next", sequence + 1, BufferSourceEvent.MAX_COUNTER)
        number(j, "original_next", 1, covered); val checking = j.get("checking"); require(checking is Boolean)
        senderCovered = covered; senderChecking = checking; finish(now)
    }
    private fun errorReply(j: JSONObject, now: Long) {
        require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
        val delay = number(j, "retry_after_ms", 100, 60_000)
        when (val state = string(j, "state")) {
            "unsupported" -> if (!negotiated && kind == "manifest.get") fallback(now) else pause(state, now, 60_000)
            "busy", "storage_error" -> {
                attempts = 0
                retryAt = minOf(now + delay.coerceAtLeast(1000), (requestStartedAt ?: now) + 60_000)
                report(state)
            }
            "withheld" -> {
                if (kind == "original.accepted") { clearAssembly(); slotOnly = true; read() }
                else pause(state, now)
            }
            "receipt_gap" -> manifest()
            "not_found", "conflict", "source_owner" -> pause(state, now)
            else -> error("invalid visible error")
        }
    }
    override fun close() { closed = true; clearRequest(); legacy?.close(); legacy = null }
}
