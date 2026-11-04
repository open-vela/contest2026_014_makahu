package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.mocharealm.foundation.fabric.buffer.data.local.*
import com.mocharealm.foundation.fabric.buffer.data.llm.*
import com.mocharealm.foundation.fabric.sdk.FabricClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

private class MeshExecutionAttemptFailure(val reason: String) : Exception(reason)
private class MeshExecutionLeaseLost : IllegalStateException("协作任务租约或模型配置已变化")

/** One model call per executor, independent lease renewal/control coroutines.
 * Results are local evidence until the source accepts a coordinator receipt. */
internal class BufferMeshExecutionWorker(context: Context, private val repository: BufferRepository, private val client: () -> FabricClient?) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rpc = BufferMeshTaskRpc(repository, client)
    private val probe = BufferModelReadiness(transcriptionOnly = true)
    private val classificationProbe = BufferModelReadiness(classificationOnly = true)
    private val answerProbe = BufferModelReadiness(chatOnly = true)
    @Volatile private var active: Job? = null
    @Volatile private var activeKey = ""
    @Volatile private var activeStage = "transcribe"
    @Volatile private var polling: Job? = null
    @Volatile private var retryAt = 0L
    @Volatile private var codecBlockedUntil = 0L
    @Volatile private var storageBlockedUntil = 0L
    @Volatile private var generationBlockedUntil = 0L
    @Volatile private var lastReadiness: Pair<String, JSONObject>? = null
    private var started = false
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                app.cacheDir.listFiles { file -> file.name.startsWith("buffer-mesh-asr-") && file.name.endsWith(".tmp") }
                    ?.forEach { it.delete() }
            }
            while (isActive) {
                try { tick() } catch (error: CancellationException) { throw error } catch (_: Exception) { withdraw(); active?.cancel(); polling?.cancel() }
                delay(1000)
            }
        }
    }
    private fun withdraw() { lastReadiness?.let { runCatching { repository.withdrawMeshExecution(it.first, it.second) } } }
    fun stop() { scope.cancel(); withdraw() }
    private fun profile(): BufferLlmConfig = repository.llmConfig().let { BufferLlmConfig(it.baseUrl, BufferMiMo.asrModel, it.apiKey) }
    private suspend fun tick() {
        val hub = client()?.takeIf { it.isConnected }
        if (hub == null) { withdraw(); active?.cancel(); polling?.cancel(); return }
        val epoch = hub.connectionGeneration
        val local = hub.localDeviceId.joinToString("") { "%02x".format(it) }
        check(client() === hub && hub.connectionGeneration == epoch)
        fun connected() = client() === hub && hub.isConnected && hub.connectionGeneration == epoch
        val plan = repository.meshGroupPlan(local)
        val key = "$local:${plan.binding.getString("revision")}:$epoch"
        if (repository.meshTaskSnapshot(local, plan.binding) == null) { active?.cancel(); polling?.cancel(); return }
        val config = profile()
        val classificationConfig = repository.llmConfig()
        val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        val visible = process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val storage = SystemClock.elapsedRealtime() >= storageBlockedUntil && app.cacheDir.isDirectory &&
            app.cacheDir.canWrite() && app.cacheDir.usableSpace >= 16L * 1024 * 1024
        if (visible && storage) { probe.request(config); classificationProbe.request(classificationConfig); answerProbe.request(classificationConfig) }
        val capabilities = buildSet {
            if (visible && storage && !(active?.let { !it.isActive && !it.isCompleted } ?: false)) {
                if (probe.ready(config) && SystemClock.elapsedRealtime() >= codecBlockedUntil) add(CoordinatorCapability.TRANSCRIBE)
                if (classificationProbe.ready(classificationConfig)) add(CoordinatorCapability.CLASSIFY)
                if (answerProbe.ready(classificationConfig)) {
                    add(CoordinatorCapability.ANSWER)
                    if (SystemClock.elapsedRealtime() >= generationBlockedUntil) add(CoordinatorCapability.GENERATE)
                }
            }
        }
        repository.meshExecutionReadiness(local, plan.binding, CoordinatorReadiness(config.ready || classificationConfig.ready,
            capabilities.isNotEmpty(), visible, storage, true, capabilities), ::connected)
        lastReadiness = local to plan.binding
        if (capabilities.isEmpty() || (active?.isCompleted == false &&
            (activeKey != key || BufferMeshTaskContract.capability(activeStage) !in capabilities))) { active?.cancel(); polling?.cancel(); return }
        if (active?.isCompleted == false || polling?.isCompleted == false || SystemClock.elapsedRealtime() < retryAt) return
        val coordinator = repository.meshTaskSnapshot(local, plan.binding)?.coordinatorDeviceId ?: return
        polling = scope.launch {
            try {
                val reply = rpc.job(local, plan, coordinator, JSONObject().put("action", "assigned"))
                require(reply.body.keys().asSequence().toSet() == setOf("status", "items") && reply.body.get("status") == "assigned")
                val items = reply.body.getJSONArray("items"); require(items.length() <= 4)
                if (items.length() == 0) return@launch
                val item = items.getJSONObject(0)
                require(item.keys().asSequence().toSet() == setOf("status", "contract", "grant") && item.get("status") == "leased")
                val contract = item.getJSONObject("contract"); BufferMeshTaskContract.validate(contract)
                val stage = contract.getString("stage")
                require(BufferMeshTaskContract.capability(stage) in capabilities)
                val handle = repository.acceptMeshExecution(local, plan.binding, coordinator, contract, item.getJSONObject("grant"), reply.started, reply.current)
                try {
                    currentCoroutineContext().ensureActive()
                    check(reply.current()); activeKey = key; activeStage = stage
                    active = scope.launch {
                        try { execute(local, plan, coordinator, contract, handle, reply.current, if (stage != "transcribe") classificationConfig else config) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { retryAt = SystemClock.elapsedRealtime() + 5000 }
                    }.also { task -> task.invokeOnCompletion { repository.releaseMeshExecution(handle) } }
                } catch (error: Exception) {
                    repository.releaseMeshExecution(handle)
                    throw error
                }
            } catch (_: TimeoutCancellationException) { retryAt = SystemClock.elapsedRealtime() + 5000 }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { retryAt = SystemClock.elapsedRealtime() + 5000 }
        }
    }

    private suspend fun execute(local: String, plan: MeshGroupPlan, coordinator: String, contract: JSONObject,
                                initial: MeshExecutionLease, connectionCurrent: () -> Boolean, config: BufferLlmConfig) {
        val mutex = Mutex(); var handle = initial
        fun checkLease() {
            val valid = runCatching {
                val current = if (contract.get("stage") != "transcribe") repository.llmConfig() else profile()
                current.baseUrl == config.baseUrl && current.model == config.model && current.apiKey == config.apiKey &&
                    connectionCurrent() && repository.meshExecutionCurrent(local, plan.binding, handle)
            }.getOrDefault(false)
            if (!valid) throw MeshExecutionLeaseLost()
        }
        try { coroutineScope {
            val renewal = launch {
                while (isActive) {
                    val wait = mutex.withLock { minOf(3000L, (handle.lease.localDeadlineElapsedRealtimeMillis - SystemClock.elapsedRealtime() - 6000).coerceAtLeast(100)) }
                    delay(wait)
                    mutex.withLock {
                        checkLease()
                        val grant = handle.lease.grant
                        val request = JSONObject().put("action", "renew").put("job_id", grant.jobId).put("lease_id", grant.leaseId)
                            .put("term", grant.coordinatorTerm).put("owner_incarnation_id", grant.ownerIncarnationId).put("renewal_sequence", grant.renewalSequence)
                        val reply = rpc.job(local, plan, coordinator, request)
                        require(reply.body.get("status") == "leased" && BufferMeshTaskContract.digest(reply.body.getJSONObject("contract")) == BufferMeshTaskContract.digest(contract))
                        handle = repository.acceptMeshExecution(local, plan.binding, coordinator, contract, reply.body.getJSONObject("grant"), reply.started, reply.current)
                    }
                }
            }
            try {
                var result = mutex.withLock { checkLease(); cache { repository.meshExecutionResult(local, plan.binding, handle, contract, connectionCurrent = connectionCurrent) } }
                if (result == null) {
                    val bytes = ByteArray(contract.getInt("input_bytes"))
                    try {
                        withTimeout(120_000) {
                            var offset = 0
                            while (offset < bytes.size) {
                                mutex.withLock { checkLease() }
                                val leaseId = mutex.withLock { handle.lease.grant.leaseId }
                                try {
                                    val reply = rpc.input(local, plan, contract.getString("source_device"), JSONObject()
                                        .put("job_id", contract.getString("job_id")).put("lease_id", leaseId).put("offset", offset))
                                    val part = reply.body
                                    val format = if (contract.get("stage") != "transcribe") "input_format" else "audio_format"
                                    require(reply.current() && part.keys().asSequence().toSet() == setOf("job_id", "lease_id", "offset", "total", "input_sha256", format, "data_b64"))
                                    require(part.get("job_id") == contract.get("job_id") && part.get("lease_id") == leaseId &&
                                        BufferMeshTaskContract.integer(part, "offset", 0L..bytes.size.toLong()) == offset.toLong() &&
                                        BufferMeshTaskContract.integer(part, "total", 1L..bytes.size.toLong()) == bytes.size.toLong() &&
                                        part.get("input_sha256") == contract.get("input_sha256") && part.get(format) == contract.get(format))
                                    val encoded = part.get("data_b64"); require(encoded is String && encoded.length <= 24 * 1024)
                                    val data = Base64.decode(encoded, Base64.NO_WRAP)
                                    try {
                                        require(data.size == minOf(16 * 1024, bytes.size - offset) && Base64.encodeToString(data, Base64.NO_WRAP) == encoded)
                                        mutex.withLock { checkLease(); data.copyInto(bytes, offset) }; offset += data.size
                                    } finally { data.fill(0) }
                                } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); delay(500) }
                                catch (error: CancellationException) { throw error }
                                catch (_: Exception) { delay(500) }
                            }
                        }
                        require(BufferSourceBlobs.originalDigest(bytes) == contract.getString("input_sha256"))
                        mutex.withLock { checkLease() }
                        val outcome = runInterruptible(Dispatchers.IO) {
                            if (contract.get("stage") == "generate") {
                                val raw = bytes.toString(Charsets.UTF_8)
                                require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                                val input = com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent.parseObject(raw, BufferWeeklyCodec.MAX_INPUT_BYTES)
                                val facts = BufferWeeklyCodec.validateInput(contract, input)
                                runBlocking { mutex.withLock { checkLease() } }
                                val output = operation("service_unavailable") { BufferWeeklyCodec.generate(config, facts) }
                                runBlocking { mutex.withLock { checkLease() } }
                                output
                            } else if (contract.get("stage") == "answer") {
                                val raw = bytes.toString(Charsets.UTF_8)
                                require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                                val input = com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent.parseObject(raw, 128 * 1024)
                                val question = BufferAnswerCodec.validateInput(contract, input)
                                runBlocking { mutex.withLock { checkLease() } }
                                val output = operation("service_unavailable") { BufferAnswerCodec.encode(BufferLlmClient.answer(config, question)) }
                                runBlocking { mutex.withLock { checkLease() } }
                                output
                            } else if (contract.get("stage") == "classify") {
                                val raw = bytes.toString(Charsets.UTF_8)
                                require(raw.toByteArray(Charsets.UTF_8).contentEquals(bytes))
                                val input = com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent.parseObject(raw, BufferMeshTaskContract.MAX_CLASSIFICATION_INPUT_BYTES)
                                val context = BufferRemoteClassificationContext(contract, input,
                                    active = { runBlocking { mutex.withLock { checkLease() } } },
                                    exchange = { action, argument -> runBlocking {
                                        val lease = mutex.withLock { checkLease(); handle.lease.grant.leaseId }
                                        val reply = rpc.input(local, plan, contract.getString("source_device"), JSONObject()
                                            .put("job_id", contract.getString("job_id")).put("lease_id", lease).put("action", action).put("argument", argument))
                                        mutex.withLock { checkLease(); check(reply.current()) }
                                        reply.body
                                    } })
                                operation("service_unavailable") {
                                    // The remote callback makes a pure routing
                                    // decision. Only the source can submit it
                                    // to its authenticated Vela connection.
                                    val decision: (() -> Unit)? = if (context.allowAssistantDecision) ({ context.checkActive() }) else null
                                    BufferClassificationCodec.encode(BufferCaptureAgent.classifyOutcome(config, context.job, context, decision), context.allowAssistantDecision)
                                }
                            } else compute(config, contract, bytes, connectionCurrent)
                        }
                        result = mutex.withLock { checkLease(); cache { repository.meshExecutionResult(local, plan.binding, handle, contract,
                            outcome, connectionCurrent) } }
                    } finally { bytes.fill(0) }
                }
                val output = requireNotNull(result)
                if (output.has("failure") && contract.getString("skill_version") == "buffer-asr-v1")
                    throw MeshExecutionAttemptFailure("legacy_failure_unsupported")
                withTimeout(60_000) {
                    while (true) {
                        try {
                            val reply = mutex.withLock {
                                checkLease(); val grant = handle.lease.grant
                                rpc.job(local, plan, coordinator, JSONObject().put("action", "result").put("job_id", grant.jobId)
                                    .put("lease_id", grant.leaseId).put("term", grant.coordinatorTerm).put("owner_incarnation_id", grant.ownerIncarnationId).put("result", output))
                            }
                            require(reply.current() && reply.body.get("status") == "completed" && reply.body.get("job_id") == contract.get("job_id") &&
                                reply.body.getString("result_sha256") == BufferMeshTaskContract.digest(output))
                            require(reply.body.keys().asSequence().toSet() == setOf("status", "job_id", "receipt_event_id", "result_sha256", "result", "grant"))
                            BufferMeshExecution.validateResult(contract, reply.body.getJSONObject("result"))
                            val receiptGrant = BufferMeshSourceTasks.grant(reply.body.getJSONObject("grant"))
                            mutex.withLock {
                                require(receiptGrant.copy(ttlMillis = handle.lease.grant.ttlMillis) == handle.lease.grant &&
                                    BufferMeshTaskContract.digest(reply.body.getJSONObject("result")) == BufferMeshTaskContract.digest(output))
                            }
                            require(reply.body.getString("receipt_event_id").matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
                            break
                        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); delay(500) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { mutex.withLock { checkLease() }; delay(500) }
                    }
                }
            } catch (error: MeshExecutionAttemptFailure) {
                try {
                    mutex.withLock {
                        if (connectionCurrent() && repository.meshExecutionCurrent(local, plan.binding, handle))
                            repository.recordMeshExecutionFailure(local, plan.binding, handle, contract, error.reason, connectionCurrent)
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { } // A storage fault may also prevent recording its own audit event.
                finally {
                    if (currentCoroutineContext().isActive) when (error.reason) {
                        "service_unavailable" -> when (contract.getString("stage")) {
                            "classify" -> classificationProbe.invalidate(config)
                            "answer" -> answerProbe.invalidate(config)
                            "generate" -> generationBlockedUntil = SystemClock.elapsedRealtime() + 60_000
                            else -> probe.invalidate(config)
                        }
                        "codec_unavailable" -> codecBlockedUntil = SystemClock.elapsedRealtime() + 60_000
                        "storage_unavailable" -> storageBlockedUntil = SystemClock.elapsedRealtime() + 60_000
                        "legacy_failure_unsupported" -> codecBlockedUntil = SystemClock.elapsedRealtime() + 60_000
                        else -> Unit
                    }
                }
                throw error
            } finally { renewal.cancelAndJoin() }
        } } finally { repository.releaseMeshExecution(handle) }
    }

    private fun <T> operation(reason: String, block: () -> T): T = try { block() }
        catch (error: MeshExecutionLeaseLost) { throw error }
        catch (error: BufferInvalidAudioException) { throw error }
        catch (error: BufferNoAudioSignalException) { throw error }
        catch (error: BufferNoTranscriptException) { throw error }
        catch (error: Exception) {
            if (Thread.currentThread().isInterrupted || error is CancellationException) throw error
            throw MeshExecutionAttemptFailure(reason)
        }

    private fun <T> cache(block: () -> T): T = try { block() }
        catch (_: android.database.sqlite.SQLiteException) { throw MeshExecutionAttemptFailure("storage_unavailable") }

    private fun compute(config: BufferLlmConfig, contract: JSONObject, bytes: ByteArray, connectionCurrent: () -> Boolean): JSONObject {
        try {
            val file = operation("storage_unavailable") { File.createTempFile("buffer-mesh-asr-", ".tmp", app.cacheDir) }
            val wav = try {
                operation("storage_unavailable") {
                    android.system.Os.chmod(file.absolutePath, 0x180)
                    file.outputStream().use { it.write(bytes) }
                }
                operation("codec_unavailable") { BufferAsrAudio.wav(file, contract.getString("audio_format")) }
            } finally { file.delete() }
            try {
                BufferAsrAudio.requireAudioSignal(wav)
                check(!Thread.currentThread().isInterrupted && connectionCurrent())
                val text = operation("service_unavailable") { BufferMiMoAsrClient.transcribe(config, wav).trim() }
                return JSONObject().put("transcript", text)
            } finally { wav.fill(0) }
        } catch (_: BufferNoAudioSignalException) { return JSONObject().put("failure", "no_signal") }
        catch (_: BufferInvalidAudioException) { return JSONObject().put("failure", "invalid_audio") }
        catch (_: BufferNoTranscriptException) { return JSONObject().put("failure", "no_transcript") }
    }
}
