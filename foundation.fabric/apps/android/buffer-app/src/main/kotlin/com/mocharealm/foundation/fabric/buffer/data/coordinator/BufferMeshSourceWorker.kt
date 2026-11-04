package com.mocharealm.foundation.fabric.buffer.data.coordinator

import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import android.os.SystemClock

/** Source-side orchestration only. The model executor has its own worker;
 * this loop cannot make an unready member eligible or execute received history. */
internal class BufferMeshSourceWorker(private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpc = BufferMeshTaskRpc(repository, client)
    private val due = ConcurrentHashMap<String, Long>()
    private val cancelDue = ConcurrentHashMap<String, Long>()
    private val scopedDue = ConcurrentHashMap<String, Long>()
    private var key = ""
    private var cancelAfter = ""
    private var captureAfter = ""
    private var classificationAfter = ""
    private var scopedAfter = ""
    private var answerAfter = ""
    private var weeklyAfter = ""
    private var running: Job? = null
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
        currentCoroutineContext().ensureActive()
        val hub = client()?.takeIf { it.isConnected } ?: return
        val epoch = hub.connectionGeneration
        val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
        check(client() === hub && hub.isConnected && hub.connectionGeneration == epoch)
        val plan = repository.meshGroupPlan(local)
        val current = "$local:${plan.binding.getString("revision")}:$epoch"
        if (key != current) { key = current; due.clear(); cancelDue.clear(); scopedDue.clear(); cancelAfter = ""; captureAfter = ""; classificationAfter = ""; scopedAfter = ""; answerAfter = ""; weeklyAfter = "" }
        val scoped = repository.meshScopedCancellations(local, scopedAfter).take(2)
        scopedAfter = scoped.lastOrNull()?.key ?: ""
        val snapshot = repository.meshTaskSnapshot(local, plan.binding)
        var pending = if (snapshot == null) emptyList() else repository.pendingMeshSourceTasks(local, plan.binding)
        val coordinator = snapshot?.coordinatorDeviceId
        val answers = repository.meshAnswerCandidates(local, answerAfter)
        answerAfter = answers.lastOrNull()?.getString("job_id") ?: ""
        if (coordinator != null && pending.size < 4) {
            val offered = pending.map { it.getString("job_id") }.toSet()
            for (answer in answers) {
                currentCoroutineContext().ensureActive()
                if (answer.getString("job_id") in offered) continue
                try { repository.prepareMeshAnswer(local, plan.binding, answer) } catch (_: Exception) { }
                pending = repository.pendingMeshSourceTasks(local, plan.binding)
                if (pending.size >= 4) break
            }
        }
        if (coordinator != null && pending.size < 4) {
            val subjects = repository.meshWeeklyCandidates(weeklyAfter)
            weeklyAfter = subjects.lastOrNull() ?: ""
            val offered = pending.filter { it.get("stage") == "generate" }.map { it.getString("subject_id") }.toSet()
            for (subject in subjects) {
                currentCoroutineContext().ensureActive()
                if (subject in offered) continue
                try { repository.prepareMeshWeekly(local, plan.binding, subject) } catch (_: Exception) { }
                pending = repository.pendingMeshSourceTasks(local, plan.binding)
                if (pending.size >= 4) break
            }
        }
        if (coordinator != null && pending.size < 4) {
            val candidates = repository.meshClassificationCandidates(classificationAfter)
            if (candidates.isEmpty()) classificationAfter = ""
            val captures = pending.mapNotNull { it.opt("capture_id") as? String }.toMutableSet()
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive(); classificationAfter = candidate.captureId
                if (candidate.captureId in captures) continue
                try {
                    if (repository.prepareMeshClassification(local, plan.binding, candidate) != null) captures.add(candidate.captureId)
                    else repository.abandonMeshVelaPreparation(candidate.captureId, "classify")
                } catch (_: Exception) { repository.abandonMeshVelaPreparation(candidate.captureId, "classify") }
                pending = repository.pendingMeshSourceTasks(local, plan.binding)
                if (pending.size >= 4) break
            }
        }
        if (coordinator != null && pending.size < 4) {
            val pendingCaptures = pending.mapNotNull { it.opt("capture_id") as? String }.toSet()
            val captures = repository.pendingAudioCaptures().filter {
                repository.meshManagesLocalTranscription(it.captureId) && it.captureId !in pendingCaptures && it.captureId > captureAfter
            }.sortedBy { it.captureId }.take(8)
            if (captures.isEmpty()) captureAfter = ""
            for (capture in captures) {
                currentCoroutineContext().ensureActive()
                captureAfter = capture.captureId
                try { repository.prepareMeshTranscription(local, plan.binding, capture.captureId) }
                catch (_: Exception) { repository.abandonMeshVelaPreparation(capture.captureId, "transcribe") }
                pending = repository.pendingMeshSourceTasks(local, plan.binding)
                if (pending.size >= 4) break
            }
        }
        due.keys.retainAll(pending.map { it.getString("job_id") }.toSet())
        val now = SystemClock.elapsedRealtime()
        val jobs = pending.filter { now >= (due[it.getString("job_id")] ?: 0L) }
            .sortedWith(compareBy({ when (it.get("stage")) { "answer" -> 0; "generate" -> 1; else -> 2 } },
                { due[it.getString("job_id")] ?: 0L }, { it.getString("job_id") })).take(2)
        val cancellations = if (snapshot == null) emptyList() else repository.meshSourceCancellations(local, plan.binding, cancelAfter).take(2)
        if (cancellations.isEmpty()) cancelAfter = "" else cancelAfter = cancellations.last().key
        if (cancelDue.size > 128) cancelDue.clear()
        if (scopedDue.size > 128) scopedDue.clear()
        coroutineScope {
            if (coordinator != null) jobs.forEach { contract -> launch {
                val id = contract.getString("job_id")
                try {
                    currentCoroutineContext().ensureActive()
                    val reply = rpc.job(local, plan, coordinator, repository.meshSourcePollRequest(local, plan.binding, contract))
                    synchronized(repository) {
                        check(reply.current())
                        when (reply.body.getString("status")) {
                            "leased" -> repository.pinMeshSourceLease(local, plan.binding, coordinator, reply.body, reply.started, reply.current)
                            "completed" -> repository.applyMeshSourceResult(local, plan.binding, coordinator, reply.body, reply.current)
                            "cancelled" -> repository.resolveMeshSourceCancellation(local, plan.binding, coordinator, id, reply.body, reply.current)
                            "waiting", "busy", "not_coordinator", "unknown", "configuration_changed", "expired" -> Unit
                            else -> error("invalid source task status")
                        }
                    }
                    due[id] = reply.started + 3000
                } catch (_: TimeoutCancellationException) { due[id] = SystemClock.elapsedRealtime() + 5000 }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { due[id] = SystemClock.elapsedRealtime() + 5000 }
            } }
            cancellations.forEach { cancellation ->
                if (cancellation.contract.getString("source_device") != local || now < (cancelDue[cancellation.key] ?: 0L)) return@forEach
                launch {
                    try {
                        val request = JSONObject().put("action", "cancel").put("job_id", cancellation.contract.getString("job_id"))
                            .put("contract", cancellation.contract)
                        val reply = rpc.job(local, plan, cancellation.coordinator, request)
                        synchronized(repository) {
                            check(reply.current())
                            repository.acknowledgeMeshSourceCancellation(local, plan.binding, cancellation, reply.body, reply.current)
                        }
                        cancelDue.remove(cancellation.key)
                    } catch (_: TimeoutCancellationException) { cancelDue[cancellation.key] = SystemClock.elapsedRealtime() + 5000 }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { cancelDue[cancellation.key] = SystemClock.elapsedRealtime() + 5000 }
                }
            }
            scoped.forEach { cancellation ->
                if (now < (scopedDue[cancellation.key] ?: 0L)) return@forEach
                launch {
                    try {
                        val reply = rpc.cancel(local, cancellation)
                        synchronized(repository) {
                            check(reply.current())
                            repository.acknowledgeMeshScopedCancellation(local, cancellation, reply.body, reply.current)
                        }
                        scopedDue.remove(cancellation.key)
                    } catch (_: TimeoutCancellationException) { scopedDue[cancellation.key] = SystemClock.elapsedRealtime() + 5000 }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { scopedDue[cancellation.key] = SystemClock.elapsedRealtime() + 5000 }
                }
            }
        }
    }
}
