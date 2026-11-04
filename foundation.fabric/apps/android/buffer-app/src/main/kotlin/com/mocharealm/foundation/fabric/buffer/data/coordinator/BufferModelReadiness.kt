package com.mocharealm.foundation.fabric.buffer.data.coordinator

import android.os.SystemClock
import android.util.Log
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmClient
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferLlmConfig
import com.mocharealm.foundation.fabric.buffer.data.llm.BufferMiMo
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/** A saved profile is not proof of model availability. Probe public synthetic
 * input off the BLE worker, cache success, and back off failures. No microphone,
 * user capture, response body or credential is logged. */
internal class BufferModelReadiness(private val transcriptionOnly: Boolean = false, private val classificationOnly: Boolean = false, private val chatOnly: Boolean = false) {
    init { require(listOf(transcriptionOnly, classificationOnly, chatOnly).count { it } <= 1) }
    @Volatile private var verified = ""
    @Volatile private var validUntil = 0L
    @Volatile private var probing = false
    @Volatile private var nextProbe = 0L
    private var generation = 0L

    fun ready(config: BufferLlmConfig): Boolean = config.ready &&
        SystemClock.elapsedRealtime() < validUntil && verified == fingerprint(config)

    @Synchronized fun invalidate(config: BufferLlmConfig) {
        generation = Math.addExact(generation, 1L)
        if (verified == fingerprint(config)) { verified = ""; validUntil = 0 }
        nextProbe = SystemClock.elapsedRealtime() + 60_000
    }

    @Synchronized fun request(config: BufferLlmConfig) {
        val now = SystemClock.elapsedRealtime()
        // Refresh before the verified window expires. A healthy phone should
        // not lose its jobs merely while its periodic service probe is running.
        if (!config.ready || (ready(config) && now < validUntil - 60_000) || probing || now < nextProbe) return
        probing = true; nextProbe = now + 60_000
        val requestGeneration = generation
        Thread({
            var stage = if (transcriptionOnly) "asr" else "llm"
            try {
                if (!transcriptionOnly) {
                    val body = JSONObject().put("model", config.model).put("stream", false)
                        .put("max_completion_tokens", 128).put("messages", JSONArray().put(JSONObject()
                            .put("role", "user").put("content", "Reply OK. Service configuration verification; no personal data.")))
                    if (config.model.startsWith("mimo-v2.5")) body.put("thinking", JSONObject().put("type", "disabled"))
                    if (classificationOnly) {
                        val parameters = JSONObject().put("type", "object").put("properties", JSONObject())
                            .put("additionalProperties", false).put("required", JSONArray())
                        val function = JSONObject().put("name", "buffer_probe").put("parameters", parameters)
                            .put("description", "Synthetic configuration probe; no personal data.")
                        body.put("tool_choice", "auto").put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", function)))
                    }
                    val chat = BufferLlmClient.requestChoice(config, body)
                    check(chat.getString("finish_reason") == "stop" && chat.getJSONObject("message").getString("content").isNotBlank())
                }
                if (!classificationOnly && !chatOnly) {
                val wav = ByteBuffer.allocate(3244).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(3236); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
                    put("data".toByteArray()); putInt(3200)
                }.array()
                val audio = JSONObject().put("model", BufferMiMo.asrModel).put("stream", false)
                    .put("asr_options", JSONObject().put("language", "auto"))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject()
                        .put("type", "input_audio").put("input_audio", JSONObject()
                            .put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)))))))
                stage = "asr"
                val asr = BufferLlmClient.requestChoice(BufferLlmConfig(config.baseUrl, BufferMiMo.asrModel, config.apiKey), audio)
                check(asr.getString("finish_reason") == "stop" && asr.getJSONObject("message").get("content") is String)
                }
                val accepted = synchronized(this@BufferModelReadiness) {
                    if (generation != requestGeneration) false else {
                        verified = fingerprint(config); validUntil = SystemClock.elapsedRealtime() + 10 * 60_000; true
                    }
                }
                if (accepted) Log.i("BufferModelReadiness", "Model services verified")
            } catch (error: Exception) {
                synchronized(this@BufferModelReadiness) { if (generation == requestGeneration) { verified = ""; validUntil = 0 } }
                Log.w("BufferModelReadiness", "Probe failed stage=$stage kind=${error.javaClass.simpleName}")
            }
            finally { probing = false }
        }, "BufferModelReadiness").apply { isDaemon = true; start() }
    }

    private fun fingerprint(config: BufferLlmConfig): String = MessageDigest.getInstance("SHA-256")
        .digest((config.baseUrl + '\u0000' + config.model + '\u0000' + config.apiKey).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
