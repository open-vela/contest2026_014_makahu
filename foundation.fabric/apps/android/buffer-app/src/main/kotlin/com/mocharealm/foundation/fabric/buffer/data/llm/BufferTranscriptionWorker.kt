package com.mocharealm.foundation.fabric.buffer.data.llm
import com.mocharealm.foundation.fabric.buffer.data.local.BufferRepository
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
                try {
                    val blob = capture.blobPath?.let(::File) ?: error("录音文件不存在")
                    val transcript = BufferMiMoAsrClient.transcribe(config, BufferAsrAudio.wav(blob, capture.audioFormat))
                    repository.completeAudioTranscription(capture.captureId, transcript)
                    onTranscribed()
                    retryAt.remove(capture.captureId); failures = 0
                } catch (_: Exception) {
                    failures = (failures + 1).coerceAtMost(6)
                    retryAt[capture.captureId] = System.currentTimeMillis() + failures * 60_000L
                    Log.w("BufferTranscription", "语音转写暂未完成，将自动重试")
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
        val text = BufferLlmClient.requestText(asr, body, 6 * 1024 * 1024)
        require(text.isNotBlank() && text.length <= 32_000) { "语音转写结果为空或过长" }
        return text
    }
}
