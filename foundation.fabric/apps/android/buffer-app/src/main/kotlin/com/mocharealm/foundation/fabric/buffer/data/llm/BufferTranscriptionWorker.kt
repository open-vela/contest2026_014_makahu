package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
import com.mocharealm.foundation.fabric.buffer.data.coordinator.BufferCoordinatorJobGate
import com.mocharealm.foundation.fabric.buffer.data.coordinator.CoordinatorCapability
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Phone owns transcription for both phone recordings and received Vela recordings. */
class BufferTranscriptionWorker(
    private val repository: BufferRepository,
    private val onTranscribed: () -> Unit = {},
) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val retryAt = mutableMapOf<String, Long>()
    private var failures = 0
    fun start() { executor.scheduleWithFixedDelay(::process, 0, 5, TimeUnit.SECONDS) }
    fun stop() { executor.shutdownNow() }

    private fun process() {
        // Configuration/keystore failures must not cancel the scheduled worker permanently.
        try {
            val config = repository.llmConfig()
            if (!config.ready) return
            val captures = repository.pendingAudioCaptures()
            retryAt.keys.retainAll(captures.map { it.captureId }.toSet())
            for (capture in captures) {
                if (System.currentTimeMillis() < (retryAt[capture.captureId] ?: 0)) continue
                if (repository.meshManagesLocalTranscription(capture.captureId)) continue
                val needsCoordinatorFence = BufferCoordinatorJobGate.requiresFence(capture.sourceDevice)
                val coordinatorLease = if (needsCoordinatorFence) {
                    BufferCoordinatorJobGate.acquire(
                        captureId = capture.captureId,
                        sourceDeviceId = capture.sourceDevice,
                        stage = "transcribe",
                        inputRevision = capture.createdAt,
                        capability = CoordinatorCapability.TRANSCRIBE,
                        nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    ) ?: continue
                } else null
                try {
                    val blob = capture.blobPath?.let(::File) ?: error("录音文件不存在")
                    val transcript = BufferMiMoAsrClient.transcribe(config, BufferAsrAudio.wav(blob, capture.audioFormat))
                    if (coordinatorLease == null) {
                        repository.completeAudioTranscription(capture.captureId, transcript)
                    } else {
                        val committed = synchronized(repository) {
                            BufferCoordinatorJobGate.commitIfCurrent(
                                coordinatorLease,
                                SystemClock.elapsedRealtime(),
                            ) {
                                repository.completeAudioTranscription(capture.captureId, transcript)
                            }
                        }
                        if (committed == null) {
                            retryAt.remove(capture.captureId)
                            continue
                        }
                    }
                    onTranscribed()
                    retryAt.remove(capture.captureId); failures = 0
                } catch (_: Exception) {
                    failures = (failures + 1).coerceAtMost(6)
                    retryAt[capture.captureId] = System.currentTimeMillis() + failures * 60_000L
                    Log.w("BufferTranscription", "语音转写暂未完成，将自动重试")
                } finally {
                    coordinatorLease?.let(BufferCoordinatorJobGate::release)
                }
            }
        } catch (_: Exception) { Log.w("BufferTranscription", "语音服务尚不可用") }
    }
}

internal object BufferMiMoAsrClient {
    fun transcribe(config: BufferLlmConfig, wav: ByteArray): String {
        BufferAsrAudio.requireAudioSignal(wav)
        val asr = BufferLlmConfig(config.baseUrl, BufferMiMo.asrModel, config.apiKey)
        val body = JSONObject().put("model", asr.model).put("stream", false)
            .put("asr_options", JSONObject().put("language", "auto"))
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data",
                        "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP)))))))
        val choice = BufferLlmClient.requestChoice(asr, body, 6 * 1024 * 1024)
        require(choice.optString("finish_reason") !in setOf("length", "content_filter", "tool_calls")) { "语音服务未返回完整结果" }
        val raw = choice.getJSONObject("message").get("content"); require(raw is String) { "语音结果不是文字" }
        val text = raw.trim()
        if (text.isEmpty()) throw BufferNoTranscriptException()
        require(text.length <= 32_000) { "语音转写结果过长" }
        return text
    }
}
