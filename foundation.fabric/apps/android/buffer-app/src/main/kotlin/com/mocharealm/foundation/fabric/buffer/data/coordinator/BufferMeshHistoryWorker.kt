package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.MeshGroupPlan
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshHistory
import com.mocharealm.foundation.fabric.buffer.data.local.SourceParentLocation
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import org.json.JSONObject

/** One bounded original at a time, independently of model ownership. Durable
 * visibility slots resume after restart; old held slots and forward progress
 * alternate so withheld history cannot starve later shared originals. */
internal class BufferMeshHistoryWorker(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpc = BufferMeshTaskRpc(repository, client)
    private var running: Job? = null
    private var key = ""
    private var afterPeer = ""
    private data class Cursor(var source: String = "", var after: Long = 0, var fill: Boolean = true, var fillAt: Long = 0,
        var blobNext: Boolean = false, var blobAt: Long = 0, var auditAfter: String = "", var auditAt: Long = 0, var documentAfter: String = "",
        var parentNext: Boolean = true, var parentAt: Long = 0, var preferParentFill: Boolean = true)
    private val cursors = mutableMapOf<String, Cursor>()
    private val due = mutableMapOf<String, Long>()
    @Volatile var states: Map<String, String> = emptyMap()
        private set

    fun start() {
        if (running != null) return
        running = scope.launch {
            while (isActive) {
                try { tick() } catch (error: CancellationException) { throw error } catch (_: Exception) { states = emptyMap() }
                delay(250)
            }
        }
    }
    fun stop() { scope.cancel() }

    private suspend fun tick() {
        val hub = client()?.takeIf { it.isConnected } ?: run { states = emptyMap(); return }
        val epoch = hub.connectionGeneration
        val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
        val plan = repository.meshGroupPlan(local)
        val peers = repository.meshHistoryPeers(local, plan.binding).sorted()
        val current = "$local:${plan.binding.getString("revision")}:$epoch"
        if (key != current) { key = current; afterPeer = ""; due.clear(); cursors.clear(); states = emptyMap() }
        fun connected() = client() === hub && hub.isConnected && hub.connectionGeneration == epoch
        val members = peers.toSet()
        due.keys.retainAll(members); cursors.keys.retainAll(members); states = states.filterKeys { it in members }
        val available = peers.filter { SystemClock.elapsedRealtime() >= (due[it] ?: 0L) }
        val peer = available.firstOrNull { it > afterPeer } ?: available.firstOrNull() ?: return
        afterPeer = peer
        try {
            val cursor = cursors.getOrPut(peer) { Cursor() }
            val blob = cursor.blobNext && SystemClock.elapsedRealtime() >= cursor.blobAt
            cursor.blobNext = !blob
            states = states + (peer to if (blob) "audio" else "syncing")
            val more = withTimeout(120_000) {
                if (blob) {
                    if (!pullBlob(local, plan, peer, cursor, ::connected)) cursor.blobAt = SystemClock.elapsedRealtime() + 5000
                    true // Give original history the next turn even when no audio is due.
                } else {
                    val probe = cursor.parentNext && SystemClock.elapsedRealtime() >= cursor.parentAt
                    cursor.parentNext = !probe
                    if (probe) cursor.parentAt = SystemClock.elapsedRealtime() + 5000
                    if (probe && probeParent(local, plan, peer, ::connected)) true
                    else pull(local, plan, peer, cursor, ::connected)
                }
            }
            due[peer] = SystemClock.elapsedRealtime() + if (more) 0 else 5000
            states = states + (peer to if (more) "syncing" else "idle")
        } catch (error: TimeoutCancellationException) {
            due[peer] = SystemClock.elapsedRealtime() + 30_000; states = states + (peer to "retrying")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { due[peer] = SystemClock.elapsedRealtime() + 30_000; states = states + (peer to "retrying") }
    }

    private suspend fun request(local: String, plan: MeshGroupPlan, peer: String, body: JSONObject): MeshTaskReply =
        rpc.facts(local, plan, peer, JSONObject().put("action", "history").put("request", body))

    private fun keys(value: JSONObject, vararg names: String) =
        require(value.keys().asSequence().toSet() == names.toSet())

    private suspend fun probeParent(local: String, plan: MeshGroupPlan, peer: String, connected: () -> Boolean): Boolean {
        val channel = requireNotNull(repository.meshApplicationChannel(local, plan.binding, peer))
        fun authorized() = connected() && channel.current() && repository.meshApplicationChannelCurrent(local, plan.binding, peer, channel.id)
        val id = repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
            repository.nextSourceParentQuery(peer, System.currentTimeMillis(), guard)
        } ?: return false
        val reply = try { request(local, plan, peer, JSONObject().put("action", "parent").put("event_id", id)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            // A peer without this optional query still receives ordinary
            // forward/held turns; no failed probe advances any source cursor.
            repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
                repository.sourceParentQueryResult(peer, id, "no_response", null, System.currentTimeMillis(), guard)
            }
            return true
        }
        check(authorized() && reply.current())
        val body = reply.body
        require(body.get("event_id") == id)
        val state = body.get("state")
        require(state is String && state in setOf("available", "not_found", "withheld", "unsupported"))
        val location = if (state == "available") {
            keys(body, "event_id", "state", "source_id", "sequence", "fingerprint", "head")
            val source = body.get("source_id"); val hash = body.get("fingerprint")
            require(source is String && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")) && hash is String && hash.matches(Regex("[0-9a-f]{64}")))
            val sequence = BufferMeshTaskContract.integer(body, "sequence", 1L until BufferSourceEvent.MAX_COUNTER)
            SourceParentLocation(source, sequence, hash, BufferMeshTaskContract.integer(body, "head", sequence until BufferSourceEvent.MAX_COUNTER))
        } else { keys(body, "event_id", "state"); null }
        repository.withMeshHistory(local, plan.binding, peer, { authorized() && reply.current() }) { guard ->
            repository.sourceParentQueryResult(peer, id, state, location, System.currentTimeMillis(), guard)
        }
        return true
    }

    private suspend fun pullBlob(local: String, plan: MeshGroupPlan, peer: String, cursor: Cursor, connected: () -> Boolean): Boolean {
        val channel = requireNotNull(repository.meshApplicationChannel(local, plan.binding, peer))
        fun authorized() = connected() && channel.current() && repository.meshApplicationChannelCurrent(local, plan.binding, peer, channel.id)
        val auditing = SystemClock.elapsedRealtime() >= cursor.auditAt
        val candidate = repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
            repository.nextSourceBlob(peer, System.currentTimeMillis(), if (auditing) cursor.auditAfter else null, guard)
        } ?: run {
            if (auditing) { cursor.auditAfter = ""; cursor.auditAt = SystemClock.elapsedRealtime() + 60_000 }
            return false
        }
        val ref = candidate.transfer
        val encodedRef = BufferMeshHistory.reference(ref)
        fun selected(action: String) = JSONObject().put("action", action).put("ref", encodedRef)
        try {
            val stored = repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
                repository.resumeSourceBlob(peer, ref, guard)
            }
            if (!stored) {
                val bytes = ByteArray(ref.total)
                try {
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive(); check(authorized())
                        val frame = request(local, plan, peer, selected("blob_read").put("offset", offset))
                        check(authorized() && frame.current())
                        keys(frame.body, "ref", "offset", "data_b64")
                        require(BufferCrdt.canonical(frame.body.getJSONObject("ref")) == BufferCrdt.canonical(encodedRef) &&
                            BufferMeshTaskContract.integer(frame.body, "offset", offset.toLong()..offset.toLong()) == offset.toLong())
                        val encoded = frame.body.get("data_b64"); require(encoded is String && encoded.length <= 96 * 1024)
                        val part = Base64.decode(encoded, Base64.NO_WRAP)
                        try {
                            require(part.size == minOf(BufferMeshHistory.BLOB_CHUNK, bytes.size - offset) &&
                                Base64.encodeToString(part, Base64.NO_WRAP) == encoded)
                            part.copyInto(bytes, offset); offset += part.size
                        } finally { part.fill(0) }
                    }
                    repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
                        repository.storeSourceBlob(peer, ref, bytes, guard)
                    }
                } finally { bytes.fill(0) }
            }
            val receipt = request(local, plan, peer, selected("blob_ack"))
            check(authorized() && receipt.current())
            keys(receipt.body, "ref", "state")
            require(receipt.body.get("state") == "received" &&
                BufferCrdt.canonical(receipt.body.getJSONObject("ref")) == BufferCrdt.canonical(encodedRef))
            repository.withMeshHistory(local, plan.binding, peer, { authorized() && receipt.current() }) { guard ->
                repository.confirmSourceBlob(peer, ref, guard)
            }
            if (candidate.audit) cursor.auditAfter = ref.eventId
            return true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            runCatching { repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
                repository.deferSourceBlob(peer, ref, "no_response", System.currentTimeMillis(), guard)
            } }
            if (candidate.audit) cursor.auditAfter = ref.eventId
            throw error
        }
    }

    private suspend fun observe(local: String, plan: MeshGroupPlan, peer: String, cursor: Cursor,
        head: Long, connected: () -> Boolean): Boolean {
        // Export actual merged state as a local observation without minting
        // new CRDT operations or replacing the originals that produced it.
        val observed = request(local, plan, peer, JSONObject().put("action", "observe").put("after", cursor.documentAfter))
        check(connected() && observed.current())
        keys(observed.body, "after", "more", "head")
        val moreDocuments = observed.body.get("more"); require(moreDocuments is Boolean)
        val documentAfter = observed.body.get("after"); require(documentAfter is String &&
            (documentAfter.isEmpty() || (documentAfter.startsWith("crdt:") && documentAfter.length <= 165)))
        require(!moreDocuments || documentAfter > cursor.documentAfter)
        cursor.documentAfter = if (moreDocuments) documentAfter else ""
        val observedHead = BufferMeshTaskContract.integer(observed.body, "head", head until BufferSourceEvent.MAX_COUNTER)
        return moreDocuments || observedHead > head
    }

    private suspend fun pull(local: String, plan: MeshGroupPlan, peer: String, cursor: Cursor, connected: () -> Boolean): Boolean {
        check(connected())
        val manifest = request(local, plan, peer, JSONObject().put("action", "manifest"))
        check(manifest.current())
        val body = manifest.body
        keys(body, "source_id", "head", "visibility_version")
        require(body.get("visibility_version") == 1)
        val source = body.getString("source_id"); require(source.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val head = BufferMeshTaskContract.integer(body, "head", 0L until BufferSourceEvent.MAX_COUNTER)
        fun authorized() = connected() && manifest.current()
        val progress = repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
            repository.sourceVisibilityProgress(peer, source, 1, guard)
        }
        require(head >= progress.coveredNext - 1) { "source history moved backwards" }
        if (cursor.source != source) { cursor.source = source; cursor.after = 0; cursor.fill = true; cursor.fillAt = 0 }
        var sequence = progress.coveredNext
        if (SystemClock.elapsedRealtime() >= cursor.fillAt && (cursor.fill || sequence > head)) {
            val parent = if (cursor.preferParentFill) repository.withMeshHistory(local, plan.binding, peer, ::authorized) { guard ->
                repository.nextSourceParentFill(peer, source, progress.coveredNext, System.currentTimeMillis(), guard)
            } else null
            cursor.preferParentFill = !cursor.preferParentFill
            if (parent != null) {
                require(parent.sequence in 1L until progress.coveredNext && parent.sequence <= head)
                sequence = parent.sequence
            } else {
                val held = request(local, plan, peer, JSONObject().put("action", "held").put("source_id", source).put("after", cursor.after))
                check(authorized() && held.current())
                keys(held.body, "source_id", "sequence"); require(held.body.get("source_id") == source)
                val old = BufferMeshTaskContract.integer(held.body, "sequence", 0L until BufferSourceEvent.MAX_COUNTER)
                if (old == 0L) { cursor.after = 0; cursor.fillAt = SystemClock.elapsedRealtime() + 30_000 }
                else {
                    cursor.after = old
                    if (old <= head && old < sequence) sequence = old
                }
            }
            cursor.fill = false
        } else cursor.fill = true
        if (sequence > head) return observe(local, plan, peer, cursor, head, connected)
        val slot = request(local, plan, peer, JSONObject().put("action", "slot").put("source_id", source).put("sequence", sequence))
        check(authorized() && slot.current())
        val metadata = slot.body
        val visible = metadata.get("visible"); require(visible is Boolean)
        require(metadata.keys().asSequence().toSet() == setOf("source_id", "sequence", "token", "visible") +
            (if (visible) setOf("event_id", "fingerprint", "bytes") else emptySet()))
        require(metadata.get("source_id") == source && BufferMeshTaskContract.integer(metadata, "sequence", 1L..head) == sequence)
        val token = metadata.getString("token"); require(token.matches(Regex("[0-9a-f]{32}")))
        fun current() = authorized() && slot.current()
        val reservation = repository.withMeshHistory(local, plan.binding, peer, ::current) { guard ->
            repository.reserveSourceVisibility(peer, source, sequence, token, 1, guard).also {
                require(it.state in setOf("held", "held_duplicate", "original_present"))
            }
        }
        if (!visible) {
            val documents = observe(local, plan, peer, cursor, head, connected)
            return sequence < head || progress.coveredNext <= head || documents
        }
        val hash = metadata.getString("fingerprint"); require(hash.matches(Regex("[0-9a-f]{64}")))
        val event = metadata.getString("event_id"); require(event.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val total = BufferMeshTaskContract.integer(metadata, "bytes", 1L..BufferSourceEvent.MAX_BYTES.toLong()).toInt()
        fun selected(action: String) = JSONObject().put("action", action).put("source_id", source)
            .put("sequence", sequence).put("token", token).put("fingerprint", hash)
        if (reservation.state == "original_present") {
            // A legacy original may precede visibility tokens. Verify its real
            // bytes and acknowledge it without inventing/replacing a marker.
            repository.withMeshHistory(local, plan.binding, peer, ::current) { guard ->
                val existing = repository.receivedSourceEvent(peer, source, sequence, guard)
                require(existing.eventId == event && existing.fingerprint == hash)
                val bytes = existing.raw.toByteArray(Charsets.UTF_8)
                try { require(bytes.size == total) } finally { bytes.fill(0) }
            }
        } else {
            val bytes = ByteArray(total)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive(); check(current())
                    val frame = request(local, plan, peer, selected("read").put("offset", offset))
                    check(current() && frame.current())
                    val part = frame.body
                    keys(part, "source_id", "sequence", "token", "fingerprint", "offset", "total", "data_b64")
                    require(part.get("source_id") == source && part.get("token") == token && part.get("fingerprint") == hash &&
                        BufferMeshTaskContract.integer(part, "sequence", sequence..sequence) == sequence &&
                        BufferMeshTaskContract.integer(part, "offset", offset.toLong()..offset.toLong()) == offset.toLong() &&
                        BufferMeshTaskContract.integer(part, "total", bytes.size.toLong()..bytes.size.toLong()) == bytes.size.toLong())
                    val encoded = part.get("data_b64"); require(encoded is String && encoded.length <= 24 * 1024)
                    val decoded = Base64.decode(encoded, Base64.NO_WRAP)
                    try {
                        require(decoded.size == minOf(16 * 1024, bytes.size - offset) && Base64.encodeToString(decoded, Base64.NO_WRAP) == encoded)
                        decoded.copyInto(bytes, offset); offset += decoded.size
                    } finally { decoded.fill(0) }
                }
                val raw = bytes.toString(Charsets.UTF_8)
                require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                val original = BufferSourceEvent.decode(raw)
                require(original.source == source && original.sequence == sequence && original.eventId == event && original.fingerprint == hash)
                repository.withMeshHistory(local, plan.binding, peer, ::current) { guard ->
                    require(repository.importVisibleSourceEvent(peer, raw, token, 1, guard).state in setOf("stored", "duplicate"))
                }
            } finally { bytes.fill(0) }
        }
        val receipt = request(local, plan, peer, selected("ack"))
        check(current() && receipt.current())
        keys(receipt.body, "source_id", "sequence", "token", "fingerprint", "state")
        require(receipt.body.get("source_id") == source && receipt.body.get("token") == token && receipt.body.get("fingerprint") == hash &&
            BufferMeshTaskContract.integer(receipt.body, "sequence", sequence..sequence) == sequence && receipt.body.get("state") == "received")
        val documents = observe(local, plan, peer, cursor, head, connected)
        return sequence < head || progress.coveredNext <= head || documents
    }
}
