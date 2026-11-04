package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.MeshGroupPlan
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.sdk.FabricClient
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class MeshConfirmationProgress(val confirmed: Int = 0, val total: Int = 0, val connected: Boolean = false, val adopted: Boolean = false)

/** Checks approved peers individually through authenticated Hub RPC. All
 * callbacks re-enter one worker; a reply cannot cross a Hub/configuration
 * generation. Adopted views also exchange application coordinator heartbeats;
 * readiness is supplied by the execution worker. This worker never dispatches jobs. */
internal class BufferMeshConfirmationWorker(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val process = UUID.randomUUID().toString()
    private var owner: FabricClient? = null
    private var epoch = -1L
    private var channelBoot = ""
    private var key = ""
    private val due = linkedMapOf<String, Long>()
    private val offline = hashSetOf<String>()
    private val forceBinding = hashSetOf<String>()
    private data class Request(val id: String, val started: Long, val plan: MeshGroupPlan, val boot: String, val heartbeat: Boolean)
    private val requests = linkedMapOf<String, Request>()
    @Volatile var progress = MeshConfirmationProgress()
        private set
    fun start() { executor.scheduleWithFixedDelay(::tick, 0, 1000, TimeUnit.MILLISECONDS) }
    fun stop() { executor.shutdownNow(); progress = MeshConfirmationProgress() }
    private fun reset() {
        requests.clear(); due.clear(); offline.clear(); forceBinding.clear(); key = ""; progress = MeshConfirmationProgress()
        repository.resetMeshCoordinator()
    }
    private fun refreshProgress(local: String, plan: MeshGroupPlan, boot: String) {
        val now = SystemClock.elapsedRealtime()
        val count = repository.confirmedMeshPeers(local, plan.binding, boot, now)
        val adopted = repository.advanceMeshConfiguration(local, plan.binding, boot, now)
        progress = MeshConfirmationProgress(count, plan.peers.size, true, adopted)
    }
    private fun post(action: () -> Unit) { if (!executor.isShutdown) runCatching { executor.execute(action) } }
    private fun tick() {
        try {
            val hub = client()?.takeIf { it.isConnected }
            if (hub == null) { reset(); owner = null; return }
            val generation = hub.connectionGeneration
            if (owner !== hub || epoch != generation) {
                reset(); owner = hub; epoch = generation; channelBoot = "$process:${UUID.randomUUID()}"
            }
            val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
            if (hub.connectionGeneration != generation) { reset(); return }
            val plan = repository.meshGroupPlan(local)
            val current = "$local:${plan.binding.getString("revision")}:$channelBoot"
            if (key != current) { reset(); key = current }
            val boot = channelBoot
            val now = SystemClock.elapsedRealtime()
            val live = hub.devices().map { it.split('|', limit = 4) }.filter {
                it.size == 4 && it[1] == "trusted" && it[2] == "true"
            }.map { it[0] }.toSet()
            for (peer in plan.peers) {
                if (peer in live) offline.remove(peer)
                else {
                    val abandoned = requests.remove(peer)
                    if (offline.add(peer) || abandoned != null)
                        repository.failMeshPeer(peer, plan.binding.getString("revision"), boot, now)
                    due[peer] = now + 5000
                }
            }
            requests.toMap().forEach { (peer, request) ->
                if (now < request.started || now - request.started >= 12_000) {
                    repository.failMeshPeer(peer, request.plan.binding.getString("revision"), request.boot, request.started)
                    if (request.heartbeat) forceBinding.add(peer)
                    requests.remove(peer); due[peer] = now + 5000
                }
            }
            refreshProgress(local, plan, boot)
            val peers = plan.peers.filter { it in live && it !in requests && now >= (due[it] ?: 0) }.sortedWith(compareBy({ due[it] ?: 0 }, { it }))
            for (peer in peers.take((8 - requests.size).coerceAtLeast(0))) {
                val id = UUID.randomUUID().toString()
                val message = if (peer in forceBinding)
                    JSONObject().put("type", "buffer.mesh.binding.get").put("protocol", 1).put("device_id", local)
                else repository.meshCoordinatorRequest(local, plan.binding, peer, id)
                val request = Request(id, now, plan, boot, message.getString("type") == "buffer.mesh.heartbeat")
                requests[peer] = request
                val payload = message.toString().toByteArray(Charsets.UTF_8)
                try {
                    hub.invoke(peer, BufferConstants.ability, payload) { response, error ->
                        val limit = if (request.heartbeat) 24 * 1024 else 8192
                        val copy = response?.takeIf { it.size in 1..limit }?.copyOf()
                        post {
                            if (key != current || requests[peer]?.id != request.id || client() !== hub || !hub.isConnected ||
                                hub.connectionGeneration != generation) return@post
                            requests.remove(peer)
                            val received = SystemClock.elapsedRealtime()
                            try {
                                check(error == null && copy != null)
                                val raw = copy.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(copy))
                                val reply = BufferSourceEvent.parseObject(raw, limit)
                                // The Hub's authenticated current-device list
                                // also observes trust revoked during the RPC.
                                check(hub.devices().any { row -> row.split('|', limit = 4).let {
                                    it.size == 4 && it[0] == peer && it[1] == "trusted" && it[2] == "true"
                                } })
                                if (request.heartbeat) repository.acceptMeshCoordinatorReply(local, plan.binding, peer, reply,
                                    request.id, boot, request.started, received)
                                else repository.confirmMeshPeer(local, plan.binding, peer, reply, boot, request.started, received)
                                forceBinding.remove(peer)
                                due[peer] = request.started + 3000
                                refreshProgress(local, plan, boot)
                            } catch (_: Exception) {
                                runCatching { repository.failMeshPeer(peer, plan.binding.getString("revision"), boot, request.started) }
                                if (request.heartbeat) forceBinding.add(peer)
                                due[peer] = received + 5000
                                try { refreshProgress(local, plan, boot) } catch (_: Exception) { progress = MeshConfirmationProgress() }
                            }
                        }
                    }
                } catch (_: Exception) {
                    requests.remove(peer); due[peer] = now + 5000
                    if (request.heartbeat) forceBinding.add(peer)
                    repository.failMeshPeer(peer, plan.binding.getString("revision"), boot, request.started)
                }
            }
        } catch (_: Exception) { progress = MeshConfirmationProgress() }
    }
}
