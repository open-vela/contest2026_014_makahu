package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshChannel
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.sdk.FabricClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Provider-owned native Sessions follow the adopted application view. Control
 * discovery/confirmation and negative cancellation remain available before a
 * data Session exists. Network negotiation never runs on the heartbeat loop. */
internal class BufferMeshChannelWorker(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private data class Owned(val hub: FabricClient, val epoch: Long, val local: String, val peer: String, val channel: BufferMeshChannel)
    private val owned = linkedMapOf<String, Owned>()
    private val due = mutableMapOf<String, Long>()
    private var after = ""
    @Volatile private var stopped = false
    @Volatile var states: Map<String, String> = emptyMap()
        private set

    fun start() { executor.scheduleWithFixedDelay(::tick, 0, 1000, TimeUnit.MILLISECONDS) }
    fun stop() { if (stopped) return; stopped = true; executor.execute { closeAll() }; executor.shutdown() }
    private fun close(item: Owned) {
        owned.remove(item.peer)
        repository.removeMeshChannel(item.peer, item.channel.id)
        runCatching { item.hub.closeSession(item.channel.id) }
    }
    private fun closeAll() { owned.values.toList().forEach(::close); due.clear(); states = emptyMap() }

    private fun tick() {
        if (stopped) return
        try {
            val hub = client()?.takeIf { it.isConnected } ?: run { closeAll(); return }
            val epoch = hub.connectionGeneration
            val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
            val plan = repository.meshGroupPlan(local)
            if (repository.meshTaskSnapshot(local, plan.binding) == null) { closeAll(); return }
            val live = hub.devices().map { it.split('|', limit = 4) }.filter {
                it.size == 4 && it[1] == "trusted" && it[2] == "true"
            }.map { it[0] }.toSet()
            val wanted = plan.peers.filter { it in live }.mapNotNull { peer ->
                runCatching { peer to BufferCrdt.canonical(repository.meshChannelTarget(local, plan.binding, peer)) }.getOrNull()
            }.toMap()
            owned.values.toList().forEach { item ->
                if (item.hub !== hub || item.epoch != epoch || item.local != local ||
                    wanted[item.peer] != item.channel.binding || !item.channel.current() ||
                    !repository.meshApplicationChannelCurrent(local, plan.binding, item.peer, item.channel.id)) close(item)
            }
            due.keys.retainAll(wanted.keys)
            states = states.filterKeys { it in wanted }
            val now = SystemClock.elapsedRealtime()
            val candidates = wanted.keys.filter { now >= (due[it] ?: 0L) }.sorted()
            val peer = candidates.firstOrNull { it > after } ?: candidates.firstOrNull() ?: return
            after = peer
            try {
            val existing = owned[peer]
            if (existing != null) {
                if (!hub.applicationSessionActive(existing.channel.id)) {
                    close(existing); due[peer] = now + 5000; states = states + (peer to "retrying")
                } else { due[peer] = now + 2000; states = states + (peer to "active") }
                return
            }
            states = states + (peer to "opening")
            val binding = wanted.getValue(peer)
            val id = hub.openApplicationSession(arrayOf(peer), BufferConstants.ability, binding.toByteArray(Charsets.UTF_8))
            if (id.isEmpty()) { due[peer] = now + 5000; states = states + (peer to "retrying"); return }
            val channel = BufferMeshChannel(id, binding) {
                !stopped && client() === hub && hub.isConnected && hub.connectionGeneration == epoch && hub.applicationSessionCurrent(id)
            }
            try {
                check(channel.current() && hub.applicationSessionActive(id))
                repository.installMeshChannel(local, plan.binding, peer, channel)
                owned[peer] = Owned(hub, epoch, local, peer, channel)
                states = states + (peer to "active"); due[peer] = SystemClock.elapsedRealtime() + 2000
            } catch (error: Exception) {
                repository.removeMeshChannel(peer, id); runCatching { hub.closeSession(id) }; throw error
            }
            } catch (_: Exception) {
                owned[peer]?.let(::close)
                due[peer] = SystemClock.elapsedRealtime() + 5000; states = states + (peer to "retrying")
            }
        } catch (_: Exception) {
            // Every installed handle carries its own connection/configuration
            // fence. An unreadable view must never retain published channels.
            closeAll()
        }
    }
}
