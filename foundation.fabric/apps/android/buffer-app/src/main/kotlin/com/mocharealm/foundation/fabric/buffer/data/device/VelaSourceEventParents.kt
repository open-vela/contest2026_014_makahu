package com.mocharealm.foundation.fabric.buffer.data.device

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.SourceParentLocation
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import org.json.JSONObject
import java.util.UUID

/** Resolve missing IDs with the peer that supplied the child's original.
 * A location is only a hint to refresh the normal contiguous source pull. */
internal class VelaSourceEventParents(
    private val repository: BufferRepository,
    private val peer: String,
    private val maximum: Int,
    private val authorize: () -> Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onAvailable: () -> Unit,
    private val onProblem: (String) -> Unit,
) : AutoCloseable {
    private var exchange = ""
    private var parent = ""
    private var pending: ByteArray? = null
    private var attempts = 0
    private var retryAt = 0L
    private var startedAt = 0L
    private var closed = false
    private var problem: String? = null

    init { require(peer.matches(Regex("[0-9a-f]{64}")) && maximum in 1024..12288) }

    private fun number(j: JSONObject, key: String, minimum: Long, maximum: Long): Long {
        val n = j.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in minimum..maximum)
        return n.toLong()
    }
    private fun string(j: JSONObject, key: String) = (j.get(key) as? String) ?: error("invalid parent reply")
    private fun report(state: String) { if (problem != state) { problem = state; onProblem(state) } }
    private fun clear() { pending?.fill(0); pending = null; parent = ""; exchange = ""; attempts = 0; startedAt = 0 }

    private fun expired(now: Long) = pending != null && (now < startedAt || now - startedAt >= 60_000)

    fun tick(now: Long) {
        if (closed) return
        if (!authorize()) { close(); return }
        if (now < retryAt) return
        // Busy replies and carrier backpressure must not monopolize discovery
        // forever. Persist a retry for this ID, then let other parents proceed.
        if (expired(now)) { finish("no_response", null, now); return }
        if (pending == null) {
            val next = try { repository.nextSourceParentQuery(peer, System.currentTimeMillis(), authorize) }
            catch (_: Exception) {
                if (!authorize()) close() else { report("local_storage"); retryAt = now + 5000 }
                return
            }
            if (next == null) { retryAt = now + 5000; return }
            parent = next; exchange = UUID.randomUUID().toString().replace("-", "")
            pending = JSONObject().put("type", "buffer.events.parent.get").put("protocol", 1).put("exchange_id", exchange)
                .put("event_id", parent).toString().toByteArray(Charsets.UTF_8).also { require(it.size in 1..maximum) }
            attempts = 0; startedAt = now
        }
        if (attempts >= 6) { finish("no_response", null, now); return }
        if (authorize() && send(requireNotNull(pending))) { attempts++; retryAt = now + 5000 }
        else retryAt = now + 100
    }

    fun receive(frame: ByteArray, now: Long) {
        if (closed || !authorize()) return
        require(frame.size in 1..maximum)
        val raw = frame.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(frame))
        val j = BufferSourceEvent.parseObject(raw, maximum)
        if (pending == null || j.opt("exchange_id") != exchange) return
        if (expired(now)) { finish("no_response", null, now); return }
        number(j, "protocol", 1, 1)
        when (string(j, "type")) {
            "buffer.events.parent" -> {
                require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "event_id", "source_id",
                    "source_sequence", "record_sha256", "last_sequence"))
                require(string(j, "event_id") == parent)
                val source = string(j, "source_id"); require(source.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
                val sequence = number(j, "source_sequence", 1, BufferSourceEvent.MAX_COUNTER - 1)
                val head = number(j, "last_sequence", sequence, BufferSourceEvent.MAX_COUNTER - 1)
                val hash = string(j, "record_sha256"); require(hash.matches(Regex("[0-9a-f]{64}")))
                finish("available", SourceParentLocation(source, sequence, hash, head), now)
            }
            "buffer.events.error" -> {
                require(j.keys().asSequence().toSet() == setOf("type", "protocol", "exchange_id", "state", "retry_after_ms"))
                val delay = number(j, "retry_after_ms", 100, 60_000)
                when (val state = string(j, "state")) {
                    "busy", "storage_error" -> {
                        report(state); attempts = 0
                        retryAt = minOf(now + delay.coerceAtLeast(5000), startedAt + 60_000)
                    }
                    "withheld", "not_found", "unsupported", "conflict" -> finish(state, null, now)
                    else -> error("invalid parent state")
                }
            }
            else -> error("invalid parent reply")
        }
    }

    private fun finish(state: String, location: SourceParentLocation?, now: Long) {
        val outcome = try { repository.sourceParentQueryResult(peer, parent, state, location, System.currentTimeMillis(), authorize) }
        catch (_: Exception) {
            if (!authorize()) close() else { report("local_storage"); attempts = 0; retryAt = now + 5000 }
            return
        }
        if (!authorize()) { close(); return }
        clear(); retryAt = now + 100
        if (outcome == "available") { problem = null; onAvailable() }
        else if (outcome != "resolved" && outcome != "obsolete") report(outcome)
    }

    override fun close() { closed = true; clear() }
}
