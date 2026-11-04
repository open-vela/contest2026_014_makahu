package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Own-source visibility-v1 sender. Reservations advance coverage only; exact
 * original receipts alone enable blob delivery. All state belongs to the
 * Enrollment worker, and reconnect starts from durable receiver evidence. */
internal class VelaVisibleSourceEventPush(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var legacy: VelaSourceEventPush? = null
    private var nextProbe = 0L
    private var negotiated = false
    private var closed = false
    private var source = ""
    private var head = 0L
    private var covered = 1L
    private var scanAfter = 0L
    private var fillAfter = 0L
    private var fillAt = 0L
    private var wantFill = true
    private var filling = false
    private var priorityFill = false
    private var preferParent = true
    private val parentFills = linkedSetOf<Long>()
    private var parentHash = ""
    private var parentOffset = 0
    private var parentProbeAt = 0L
    private var lastOffer = 0L
    private var record: BufferSourceEvent? = null
    private var token = ""
    private var bytes: ByteArray? = null
    private var offset = 0
    private var end = 0
    private var pending: ByteArray? = null
    private var exchange = ""
    private var kind = ""
    private var attempts = 0
    private var requestStartedAt: Long? = null
    private var retryAt = 0L
    private var commit: String? = null
    private var problem: String? = null

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }
    private fun number(j: JSONObject, key: String, min: Long, max: Long): Long {
        val n = j.get(key); require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in min..max)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("invalid visible reply")
    private fun report(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun base(suffix: String) = JSONObject().put("type", "buffer.events.visible.$suffix").put("protocol", 1).put("visibility_version", 1)
    private fun selected(suffix: String): JSONObject {
        val r = requireNotNull(record)
        return base(suffix).put("source_id", source).put("source_sequence", r.sequence).put("slot_token", token)
    }
    private fun queue(suffix: String, j: JSONObject) {
        exchange = UUID.randomUUID().toString().replace("-", "")
        val encoded = j.put("exchange_id", exchange).toString().toByteArray(Charsets.UTF_8); require(encoded.size in 1..maximum)
        pending?.fill(0); pending = encoded; kind = suffix; attempts = 0; retryAt = 0; requestStartedAt = null
    }
    private fun clear() {
        pending?.fill(0); pending = null; bytes?.fill(0); bytes = null; record = null; token = ""
        exchange = ""; kind = ""; offset = 0; end = 0; commit = null; requestStartedAt = null
    }
    private fun requestExpired(now: Long) = requestStartedAt?.let { now < it || now - it >= 60_000 } == true
    private fun pause(state: String, now: Long, delay: Long = 30_000) { clear(); report(state); retryAt = now + delay }
    private fun offer() {
        clear()
        val manifest = repository.sourceEventManifest(authorize)
        val stream = string(manifest, "source_id"); require(stream.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        if (source != stream) { source = stream; scanAfter = 0; fillAfter = 0; fillAt = 0; parentFills.clear(); parentHash = ""; parentOffset = 0; parentProbeAt = 0 }
        head = number(manifest, "last_sequence", 0, BufferSourceEvent.MAX_COUNTER - 1)
        queue("offer", base("offer").put("source_id", source).put("last_sequence", head))
    }
    private fun original(sequence: Long): BufferSourceEvent {
        val raw = repository.sourceEventPage(sequence - 1, 1, authorize).singleOrNull() ?: error("source original missing")
        return BufferSourceEvent.decode(raw).also { check(it.source == source && it.sequence == sequence) }
    }
    private fun reserve(r: BufferSourceEvent) {
        clear(); record = r
        token = repository.prepareSourceVisibility(peer, r.raw, 1, authorize).token
        queue("reserve", selected("reserve"))
    }
    private fun fragment() {
        val r = requireNotNull(record)
        if (bytes == null) bytes = r.raw.toByteArray(Charsets.UTF_8)
        val original = requireNotNull(bytes); check(offset in original.indices)
        end = minOf(original.size, offset + minOf(3072, ((maximum - 640) / 4) * 3))
        val part = original.copyOfRange(offset, end)
        val encoded = try { Base64.getEncoder().encodeToString(part) } finally { part.fill(0) }
        queue("put", selected("put").put("record_sha256", r.fingerprint).put("offset", offset)
            .put("total", original.size).put("record_base64", encoded))
    }
    private fun proof() {
        val r = requireNotNull(record)
        queue("proof", selected("proof").put("record_sha256", r.fingerprint).put("total", r.raw.toByteArray(Charsets.UTF_8).size))
    }
    private fun select(now: Long) {
        clear()
        if (now >= parentProbeAt) {
            parentProbeAt = now + 10_000
            queue("parents", JSONObject().put("type", "buffer.events.parents.get").put("protocol", 1)
                .put("after_hash", parentHash).put("parent_offset", parentOffset))
            return
        }
        if (now - lastOffer >= 5000) { offer(); return }
        priorityFill = false
        val missing = repository.sourceEventWithoutReceipt(peer, scanAfter, authorize) ?: (head + 1)
        val forward = minOf(covered, missing)
        if (now >= fillAt && (wantFill || forward > head)) {
            val prioritized = if (preferParent) parentFills.firstOrNull { it < covered } else null
            if (prioritized != null) {
                parentFills.remove(prioritized); preferParent = false
                val r = original(prioritized)
                if (repository.mayShareSourceEvent(r, peer)) {
                    priorityFill = true; filling = true; wantFill = false; reserve(r); return
                }
            }
            preferParent = true
            val held = repository.nextSourceVisibilityFill(peer, fillAfter, 1, authorize)
            if (held == null) { fillAfter = 0; fillAt = now + 30_000 }
            else if (held.sequence < covered) {
                // At most one old slot per selection, including one whose
                // policy still withholds it. Forward coverage gets the next turn.
                fillAfter = held.sequence; wantFill = false
                val r = original(held.sequence)
                if (repository.mayShareSourceEvent(r, peer)) { filling = true; reserve(r); return }
                if (forward > head) { retryAt = now + 100; return }
            }
        }
        if (forward > head) { retryAt = now + 1000; return }
        filling = false; reserve(original(forward))
    }
    private fun finish(now: Long) {
        val sequence = requireNotNull(record).sequence
        parentFills.remove(sequence)
        if (filling) { if (!priorityFill) fillAfter = sequence; wantFill = false }
        else { scanAfter = maxOf(scanAfter, sequence); wantFill = true }
        clear(); problem = null; retryAt = now; select(now)
    }
    private fun save(now: Long) {
        val state = commit ?: return
        try {
            val r = requireNotNull(record)
            if (state == "original") repository.sourceEventDelivered(peer, r.raw, authorize)
            else repository.sourceVisibilityReserved(peer, source, r.sequence, token, 1, authorize)
        } catch (_: Exception) { if (!authorize()) close() else { report("local_storage"); retryAt = now + 5000 }; return }
        commit = null
        if (state == "original") { finish(now); return }
        val r = requireNotNull(record)
        if (!repository.mayShareSourceEvent(r, peer)) { finish(now); return }
        if (state == "original_present") proof() else fragment()
    }
    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        try {
            val old = legacy
            if (old != null && pending == null) {
                if (now >= nextProbe && old.idle()) offer() else { old.tick(now); return }
            }
            if (commit != null) { save(now); return }
            if (pending == null) { if (negotiated) select(now) else offer(); if (pending == null) return }
            val r = record
            if ((kind == "put" || kind == "proof") && r != null && !repository.mayShareSourceEvent(r, peer)) {
                // The reservation was already committed. Replace a cached raw
                // fragment/proof with a slot-only retry after policy revocation.
                reserve(r)
            }
            if (requestStartedAt == null) requestStartedAt = now
            if (kind == "parents" && (attempts >= 2 || requestExpired(now))) {
                clear(); parentProbeAt = now + 60_000; retryAt = now; return
            }
            if (requestExpired(now)) { pause("no_response", now, 5000); return }
            if (attempts >= 6) {
                if (!negotiated && kind == "offer") { fallback(now); return }
                pause("no_response", now, 60_000); return
            }
            if (authorize() && send(requireNotNull(pending))) { attempts++; retryAt = now + 5000 } else retryAt = now + 100
        } catch (_: Exception) { if (!authorize()) close() else pause("local_storage", now, 5000) }
    }
    private fun fallback(now: Long) {
        clear()
        if (repository.sourceVisibilitySendEnabled(peer, authorize)) { pause("visibility_required", now, 60_000); return }
        if (legacy == null) legacy = VelaSourceEventPush(repository, peer, maximum, authorize, send, onProblem)
        nextProbe = now + 60_000; retryAt = now; report("legacy_profile")
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
        if (kind == "parents") {
            require(string(j, "type") == "buffer.events.parents" && j.keys().asSequence().toSet() ==
                setOf("type", "protocol", "exchange_id", "after_hash", "parent_offset", "event_ids"))
            val after = string(j, "after_hash"); require(after.isEmpty() || after.matches(Regex("[0-9a-f]{64}")))
            val position = number(j, "parent_offset", 0, 32).toInt()
            require(after.isNotEmpty() || position == 0)
            require(after.isEmpty() || if (parentOffset > 0) after == parentHash && (position == 0 || position > parentOffset)
                else after > parentHash)
            val ids = j.getJSONArray("event_ids"); require(ids.length() <= 3)
            val requested = (0 until ids.length()).map { ids.get(it).also { id ->
                require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
            } as String }
            require(requested.distinct().size == requested.size)
            val sequences = requested.mapNotNull { repository.sourceParentToSend(peer, it, authorize) }
            if (!authorize()) { close(); return }
            for (sequence in sequences) if (parentFills.size < 8) parentFills.add(sequence)
            parentHash = after; parentOffset = position
            clear(); retryAt = now; parentProbeAt = now + if (after.isEmpty()) 30_000 else 1000
            fillAt = 0
            return
        }
        number(j, "visibility_version", 1, 1)
        when (string(j, "type")) {
            "buffer.events.visible.ready" -> {
                require(kind == "offer" && j.keys().asSequence().toSet() == setOf("type", "protocol", "visibility_version", "exchange_id", "source_id", "covered_next", "original_next"))
                require(string(j, "source_id") == source)
                covered = number(j, "covered_next", 1, head + 1); number(j, "original_next", 1, covered)
                legacy?.close(); legacy = null; negotiated = true; lastOffer = now; clear(); select(now)
            }
            "buffer.events.visible.reserved" -> {
                require(kind == "reserve" && j.keys().asSequence().toSet() == setOf("type", "protocol", "visibility_version", "exchange_id",
                    "source_id", "source_sequence", "slot_token", "state", "covered_next", "original_next"))
                position(j); val state = string(j, "state"); progress(j, state == "gap" || state == "conflict")
                when (state) {
                    "held", "held_duplicate", "original_present" -> { commit = state; pending?.fill(0); pending = null; save(now) }
                    "gap" -> offer()
                    "conflict" -> pause("conflict", now)
                    else -> error("invalid reservation receipt")
                }
            }
            "buffer.events.visible.received" -> {
                require((kind == "put" || kind == "proof") && j.keys().asSequence().toSet() == setOf("type", "protocol", "visibility_version", "exchange_id",
                    "source_id", "source_sequence", "slot_token", "state", "covered_next", "original_next", "record_sha256", "total", "next_offset"))
                position(j); val r = requireNotNull(record); val size = r.raw.toByteArray(Charsets.UTF_8).size
                require(string(j, "record_sha256") == r.fingerprint && number(j, "total", 1, BufferSourceEvent.MAX_BYTES.toLong()) == size.toLong())
                val state = string(j, "state"); progress(j, state == "gap" || state == "conflict")
                val next = number(j, "next_offset", 0, size.toLong()).toInt()
                when (state) {
                    "partial" -> { require(kind == "put" && next in end until size); offset = next; fragment() }
                    "stored", "duplicate" -> { require(next == size); commit = "original"; pending?.fill(0); pending = null; save(now) }
                    "gap" -> offer()
                    "conflict" -> pause("conflict", now)
                    else -> error("invalid original receipt")
                }
            }
            else -> error("invalid visible reply")
        }
    }
    private fun position(j: JSONObject) {
        require(string(j, "source_id") == source && number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1) == requireNotNull(record).sequence && string(j, "slot_token") == token)
    }
    private fun progress(j: JSONObject, failed: Boolean) {
        covered = number(j, "covered_next", if (failed) 1 else requireNotNull(record).sequence + 1, head + 1)
        number(j, "original_next", 1, covered)
    }
    private fun errorReply(j: JSONObject, now: Long) {
        require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
        val delay = number(j, "retry_after_ms", 100, 60_000)
        val state = string(j, "state")
        if (kind == "parents") {
            require(state in setOf("unsupported", "busy", "storage_error", "source_owner", "conflict", "invalid"))
            clear(); retryAt = now; parentProbeAt = now + if (state == "unsupported") 300_000 else delay.coerceAtLeast(5000)
            return
        }
        when (state) {
            "unsupported" -> if (!negotiated && kind == "offer") fallback(now) else pause(state, now, 60_000)
            "busy", "storage_error" -> {
                attempts = 0
                retryAt = minOf(now + delay.coerceAtLeast(1000), (requestStartedAt ?: now) + 60_000)
                report(state)
            }
            "restart" -> offer()
            "history_rollback", "source_owner", "conflict", "invalid" -> pause(state, now)
            else -> error("invalid visible error")
        }
    }
    override fun close() { closed = true; clear(); parentFills.clear(); legacy?.close(); legacy = null }
}
