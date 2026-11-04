package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.MeshGroupPlan
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import org.json.JSONObject

/** Independent, bounded history transfer. Never competes for model execution
 * ownership or interprets received facts as requests, grants or live results. */
internal class BufferMeshFactsWorker(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpc = BufferMeshTaskRpc(repository, client)
    private var running: Job? = null
    private var key = ""
    private var afterPeer = ""
    private val due = mutableMapOf<String, Long>()
    @Volatile var states: Map<String, String> = emptyMap()
        private set

    fun start() {
        if (running != null) return
        running = scope.launch {
            while (isActive) {
                try { tick() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                delay(1000)
            }
        }
    }
    fun stop() { scope.cancel() }

    private suspend fun tick() {
        val hub = client()?.takeIf { it.isConnected } ?: run { states = emptyMap(); return }
        val epoch = hub.connectionGeneration
        val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
        val plan = repository.meshGroupPlan(local)
        if (repository.meshTaskSnapshot(local, plan.binding) == null) { states = emptyMap(); return }
        val current = "$local:${plan.binding.getString("revision")}:$epoch"
        if (key != current) { key = current; afterPeer = ""; due.clear(); states = emptyMap() }
        fun connected() = client() === hub && hub.isConnected && hub.connectionGeneration == epoch
        check(connected())
        val peers = plan.peers.sorted()
        due.keys.retainAll(peers.toSet())
        states = states.filterKeys { it in peers }
        val available = peers.filter { SystemClock.elapsedRealtime() >= (due[it] ?: 0L) }
        val peer = available.firstOrNull { it > afterPeer } ?: available.firstOrNull() ?: return
        afterPeer = peer
        try {
            states = states + (peer to "syncing")
            val more = withTimeout(120_000) { pull(local, plan, peer, ::connected) }
            due[peer] = SystemClock.elapsedRealtime() + if (more) 0 else 10_000
            states = states + (peer to if (more) "syncing" else "idle")
        } catch (_: TimeoutCancellationException) { due[peer] = SystemClock.elapsedRealtime() + 30_000; states = states + (peer to "retrying") }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { due[peer] = SystemClock.elapsedRealtime() + 30_000; states = states + (peer to "retrying") }
    }

    private suspend fun pull(local: String, plan: MeshGroupPlan, peer: String, connected: () -> Boolean): Boolean {
        check(connected())
        val resume = repository.meshFactsResume(local, plan.binding, peer)
        val reply = rpc.facts(local, plan, peer, JSONObject().put("action", "page").put("source_id", resume.source).put("after", resume.after))
        check(reply.current())
        val page = reply.body
        require(page.keys().asSequence().toSet() == setOf("source_id", "after", "through", "head", "entries"))
        val source = page.get("source_id")
        require(source is String && source.matches(Regex("[A-Za-z0-9_.-]{1,96}")))
        val after = BufferMeshTaskContract.integer(page, "after", 0L until BufferSourceEvent.MAX_COUNTER)
        require(after == if (source == resume.source) resume.after else 0L)
        val through = BufferMeshTaskContract.integer(page, "through", after until BufferSourceEvent.MAX_COUNTER)
        val head = BufferMeshTaskContract.integer(page, "head", through until BufferSourceEvent.MAX_COUNTER)
        val entries = page.getJSONArray("entries")
        require(entries.length() <= 2 && (entries.length() > 0 || through == head))
        val originals = mutableListOf<String>()
        var previous = after
        for (n in 0 until entries.length()) {
            currentCoroutineContext().ensureActive(); check(connected() && reply.current())
            val entry = entries.getJSONObject(n)
            require(entry.keys().asSequence().toSet() == setOf("publication_id", "event_id", "source_sequence", "fingerprint", "bytes"))
            val publication = BufferMeshTaskContract.integer(entry, "publication_id", previous + 1..through)
            BufferMeshTaskContract.integer(entry, "source_sequence", 1L until BufferSourceEvent.MAX_COUNTER)
            require(entry.get("event_id") is String && entry.getString("event_id").matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
            require(entry.get("fingerprint") is String && entry.getString("fingerprint").matches(Regex("[0-9a-f]{64}")))
            val bytes = ByteArray(BufferMeshTaskContract.integer(entry, "bytes", 1L..BufferSourceEvent.MAX_BYTES.toLong()).toInt())
            try {
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive(); check(connected() && reply.current())
                    val frame = rpc.facts(local, plan, peer, JSONObject().put("action", "read").put("source_id", source)
                        .put("publication_id", publication).put("event_id", entry.get("event_id")).put("fingerprint", entry.get("fingerprint")).put("offset", offset))
                    check(frame.current() && reply.current())
                    val body = frame.body
                    require(body.keys().asSequence().toSet() == entry.keys().asSequence().toSet() + setOf("source_id", "offset", "data_b64"))
                    require(body.get("source_id") == source && BufferMeshTaskContract.integer(body, "offset", 0L until bytes.size.toLong()) == offset.toLong())
                    val declared = JSONObject(body.toString()).apply { remove("source_id"); remove("offset"); remove("data_b64") }
                    require(BufferCrdt.canonical(declared) == BufferCrdt.canonical(entry))
                    val encoded = body.get("data_b64"); require(encoded is String && encoded.length <= 24 * 1024)
                    val part = Base64.decode(encoded, Base64.NO_WRAP)
                    try {
                        require(part.size == minOf(16 * 1024, bytes.size - offset) && Base64.encodeToString(part, Base64.NO_WRAP) == encoded)
                        part.copyInto(bytes, offset); offset += part.size
                    } finally { part.fill(0) }
                }
                val raw = bytes.toString(Charsets.UTF_8)
                require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                val original = BufferSourceEvent.decode(raw)
                require(original.source == source && original.eventId == entry.get("event_id") && original.sequence == entry.getLong("source_sequence") &&
                    original.fingerprint == entry.get("fingerprint"))
                originals.add(raw)
            } finally { bytes.fill(0) }
            previous = publication
        }
        require(entries.length() == 0 || previous == through)
        repository.commitMeshFacts(local, plan.binding, peer, resume, page, originals) { connected() && reply.current() }
        return through < head
    }
}
