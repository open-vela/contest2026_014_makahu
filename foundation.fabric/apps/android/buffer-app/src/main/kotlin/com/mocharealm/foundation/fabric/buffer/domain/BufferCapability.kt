package com.mocharealm.foundation.fabric.buffer.domain

import android.content.Context
import com.mocharealm.foundation.fabric.buffer.data.device.VelaProcessedCaptureProtocol
import org.json.JSONObject

/** Resource loading supplies a bounded prompt and contract, never permission.
 * An approved proposal binds this exact resource digest; changed resources
 * need a new proposal and explicit local approval. No script/tool loading. */
internal data class CapabilitySkill(val id: String, val version: Int, val digest: String, val prompt: String) {
    companion object {
        fun load(context: Context): CapabilitySkill {
            fun read(path: String, limit: Int): String = context.assets.open(path).use { stream ->
                val buffer = ByteArray(limit + 1); var used = 0
                while (used < buffer.size) { val n = stream.read(buffer, used, buffer.size - used); if (n < 0) break; check(n > 0); used += n }
                require(used in 1..limit) { "能力资源大小无效" }
                val bytes = buffer.copyOf(used)
                val text = bytes.toString(Charsets.UTF_8)
                require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes) && '\u0000' !in text) { "能力资源编码无效" }
                text
            }
            val manifest = BufferSourceEvent.parseObject(read("focus-care/spec.json", 4096), 4096)
            require(manifest.keys().asSequence().toSet() == setOf("id", "version", "prompt_sha256", "reads", "triggers", "actions", "tools"))
            require(manifest.get("id") == "focus-care" && manifest.get("version") == 1)
            require(manifest.getJSONArray("reads").toString() == "[\"focus_sessions\"]" &&
                manifest.getJSONArray("triggers").toString() == "[\"focus_elapsed\"]" &&
                manifest.getJSONArray("actions").toString() == "[\"notify_care\"]" && manifest.getJSONArray("tools").length() == 0)
            val prompt = read("focus-care/prompt.txt", 16384)
            require(manifest.getString("prompt_sha256") == VelaProcessedCaptureProtocol.fingerprint(prompt)) { "能力资源校验失败" }
            return CapabilitySkill("focus-care", 1, VelaProcessedCaptureProtocol.fingerprint(BufferCrdt.canonical(manifest) + "\n" + prompt), prompt)
        }
    }
}

internal data class CapabilityProposal(
    val id: String, val version: Int, val digest: String, val state: String,
    val body: JSONObject, val expiresAt: Long, val grantUntil: Long,
) {
    val title: String get() = body.getString("title")
    val minutes: Int get() = body.getInt("after_minutes")
    val explanation: String get() = body.getString("explanation")
}
internal data class CapabilityGeneration(val id: String, val lease: String, val facts: JSONObject, val skillDigest: String)
internal data class CapabilityNotice(val taskId: String, val id: String, val version: Int, val revision: String, val session: String, val message: String)

internal object CapabilityContract {
    const val DAY = 86_400_000L
    val messages = linkedMapOf("water" to "可以喝点水", "eyes" to "可以看看远处", "move" to "可以活动一下肩颈", "restroom" to "可以留意一下身体是否需要休息或去洗手间")
    fun number(value: JSONObject, key: String, range: LongRange): Long {
        val n = value.get(key)
        require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble() && n.toLong() in range)
        return n.toLong()
    }
    fun validate(suggestion: JSONObject, facts: JSONObject): JSONObject {
        require(suggestion.keys().asSequence().toSet() == setOf("title", "explanation", "evidence_ids", "after_minutes", "message_ids"))
        listOf("title" to 80, "explanation" to 1000).forEach { (key, limit) ->
            val s = suggestion.get(key); require(s is String && s.isNotBlank() && s.length <= limit && '\u0000' !in s)
        }
        number(suggestion, "after_minutes", 50L..120L)
        fun strings(key: String, maximum: Int): List<String> {
            val a = suggestion.getJSONArray(key); require(a.length() in 1..maximum)
            return (0 until a.length()).map { require(a.get(it) is String); a.getString(it) }.also { require(it.distinct() == it) }
        }
        require(strings("message_ids", 4).all { it in messages })
        val ids = strings("evidence_ids", 8); require(ids.size >= 3)
        val input = facts.getJSONArray("events")
        val available = (0 until input.length()).associate { input.getJSONObject(it).let { e -> e.getString("event_id") to e } }
        val sessions = ids.map { id ->
            val evidence = requireNotNull(available[id]) { "建议引用了不存在的记录" }
            val session = FocusSession.parse(evidence.getJSONObject("session"))
            require(!session.active && (session.endedAt - session.startedAt >= 50 * 60_000L || session.experience in setOf("tired", "hard")))
            session.id
        }
        require(sessions.distinct().size >= 3) { "至少需要三次不同专注的真实依据" }
        return JSONObject(BufferCrdt.canonical(suggestion))
    }
}
