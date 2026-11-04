package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.local.MeshGroupPlan
import com.mocharealm.foundation.fabric.buffer.data.local.MeshScopedCancellation
import com.mocharealm.foundation.fabric.buffer.data.local.BufferMeshTaskCancellation
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferConstants
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MeshTaskReply(val body: JSONObject, val started: Long, val current: () -> Boolean)

/** Bounded control RPCs. Consumers recheck [MeshTaskReply.current] under the
 * repository monitor immediately before consuming a grant/result. Audio and
 * model calls do not run on the heartbeat worker. */
internal class BufferMeshTaskRpc(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val slots = Semaphore(4)
    private val inputSlot = Semaphore(1)
    suspend fun job(local: String, plan: MeshGroupPlan, peer: String, request: JSONObject): MeshTaskReply = exchange(local, plan, peer, request, false)
    suspend fun input(local: String, plan: MeshGroupPlan, peer: String, request: JSONObject): MeshTaskReply = exchange(local, plan, peer, request, true)
    suspend fun facts(local: String, plan: MeshGroupPlan, peer: String, request: JSONObject): MeshTaskReply = exchange(local, plan, peer, request, false, facts = true)
    suspend fun cancel(local: String, cancellation: MeshScopedCancellation): MeshTaskReply = withContext(Dispatchers.IO) {
        withTimeout(11_000) { slots.withPermit {
            require(cancellation.source == local)
            val hub = requireNotNull(client()).also { check(it.isConnected) }; val epoch = hub.connectionGeneration
            val peer = cancellation.coordinator
            fun current() = connected(hub, epoch, local, peer)
            check(current()); currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime(); val request = cancellation.message(UUID.randomUUID().toString())
            val reply = if (peer == local) synchronized(repository) {
                check(current()); repository.exchangeMeshCancellation(local, local, request)
            } else {
                val payload = request.toString().toByteArray(Charsets.UTF_8); require(payload.size <= 8192)
                val response = invoke(hub, peer, payload, 8192)
                val raw = response.toString(Charsets.UTF_8); require(raw.toByteArray(Charsets.UTF_8).contentEquals(response))
                BufferSourceEvent.parseObject(raw, 8192)
            }
            check(current() && SystemClock.elapsedRealtime() - started < 11_000)
            BufferMeshTaskCancellation.accept(reply, request, peer)
            MeshTaskReply(reply, started, ::current)
        } }
    }

    private fun connected(hub: FabricClient, epoch: Long, local: String, peer: String): Boolean = try {
        client() === hub && hub.isConnected && hub.connectionGeneration == epoch &&
            hub.localDeviceId.joinToString("") { "%02x".format(it) } == local &&
            (peer == local || hub.devices().any { row -> row.split('|', limit = 4).let {
                it.size == 4 && it[0] == peer && it[1] == "trusted" && it[2] == "true"
            } })
    } catch (_: Exception) { false }

    private suspend fun invoke(hub: FabricClient, peer: String, payload: ByteArray, limit: Int, session: String? = null): ByteArray =
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val settled = AtomicBoolean(false)
            continuation.invokeOnCancellation { settled.set(true) }
            try {
                val callback = FabricClient.ResultCallback { bytes, error ->
                    if (settled.compareAndSet(false, true) && continuation.isActive) {
                        if (error != null || bytes == null || bytes.size !in 1..limit) {
                            if (session != null) repository.removeMeshChannel(peer, session)
                            continuation.resumeWithException(IllegalStateException("协作任务请求未返回有效响应"))
                        } else continuation.resume(bytes.copyOf())
                    }
                }
                if (session == null) hub.invoke(peer, BufferConstants.ability, payload, callback)
                else hub.invokeSession(session, peer, BufferConstants.ability, payload, callback)
            } catch (error: Exception) {
                if (session != null) repository.removeMeshChannel(peer, session)
                if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resumeWithException(error)
            }
        }
    private suspend fun exchange(local: String, plan: MeshGroupPlan, peer: String, request: JSONObject, input: Boolean, facts: Boolean = false): MeshTaskReply =
        withContext(Dispatchers.IO) {
            withTimeout(11_000) { (if (input) inputSlot else slots).withPermit {
                val hub = requireNotNull(client()).also { check(it.isConnected) }
                val epoch = hub.connectionGeneration
                val channel = if (peer == local) null else requireNotNull(repository.meshApplicationChannel(local, plan.binding, peer)) { "授权通道尚未建立" }
                fun current() = connected(hub, epoch, local, peer) && (peer == local || (channel!!.current() &&
                    repository.meshApplicationChannelCurrent(local, plan.binding, peer, channel.id)))
                check(current())
                val started = SystemClock.elapsedRealtime()
                if (peer == local) {
                    require(!facts)
                    val result = synchronized(repository) {
                        check(current())
                        if (input) repository.meshLocalInput(local, plan.binding, request) else repository.meshLocalTask(local, plan.binding, request)
                    }
                    check(current())
                    return@withPermit MeshTaskReply(result, started, ::current)
                }
                val nonce = UUID.randomUUID().toString()
                val message = if (facts) repository.meshFactsRequest(local, plan.binding, peer, nonce, request)
                    else if (input) repository.meshInputRequest(local, plan.binding, peer, nonce, request)
                    else repository.meshTaskRequest(local, plan.binding, peer, nonce, request)
                val payload = message.toString().toByteArray(Charsets.UTF_8)
                // Audio remains 16 KiB per source frame. Classification record
                // tools may return a bounded set of text records in one reply.
                val limit = 256 * 1024
                require(payload.size <= limit)
                check(current())
                currentCoroutineContext().ensureActive()
                val response = invoke(hub, peer, payload, limit, channel?.id)
                check(current() && SystemClock.elapsedRealtime() - started < 11_000)
                val raw = response.toString(Charsets.UTF_8)
                require(raw.toByteArray(Charsets.UTF_8).contentEquals(response))
                val reply = BufferSourceEvent.parseObject(raw, limit)
                val body = synchronized(repository) {
                    check(current())
                    if (facts) repository.acceptMeshFactsReply(local, plan.binding, peer, nonce, reply)
                    else if (input) repository.acceptMeshInputReply(local, plan.binding, peer, nonce, reply)
                    else repository.acceptMeshTaskReply(local, plan.binding, peer, nonce, reply)
                }
                check(current())
                MeshTaskReply(body, started, ::current)
            } }
        }
}
