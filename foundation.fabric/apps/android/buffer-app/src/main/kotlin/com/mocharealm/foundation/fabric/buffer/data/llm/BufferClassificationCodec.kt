package com.mocharealm.foundation.fabric.buffer.data.llm

import com.mocharealm.foundation.fabric.buffer.domain.BufferCrdt
import com.mocharealm.foundation.fabric.buffer.domain.BufferSourceEvent
import com.mocharealm.foundation.fabric.buffer.domain.model.BufferCard
import org.json.JSONArray
import org.json.JSONObject

/** Bounded classification data, never a device command or executable UI.
 * Receivers supply their own trusted record IDs; peer-claimed observations
 * are deliberately absent from the wire result. */
internal object BufferClassificationCodec {
    private const val MAX_RESULT_BYTES = 192 * 1024
    private val base = setOf("text", "kind", "title", "summary", "health_category", "confidence", "related_card_ids")
    private fun objectCopy(value: JSONObject, maximum: Int) = BufferSourceEvent.parseObject(BufferCrdt.canonical(value), maximum)
    private fun raw(proposals: List<BufferClassificationProposal>): JSONObject = JSONObject().put("segments", JSONArray().also { segments ->
        proposals.forEach { proposal -> segments.put(JSONObject().put("text", proposal.text).put("kind", proposal.kind)
            .put("title", proposal.title).put("summary", proposal.summary).put("health_category", proposal.healthCategory ?: JSONObject.NULL)
            .put("confidence", proposal.confidence).put("related_card_ids", JSONArray(proposal.relatedCardIds)).apply {
                proposal.presentation?.let { put("presentation", BufferSourceEvent.parseObject(it, MAX_RESULT_BYTES)) }
                proposal.bedtime?.let { put("bedtime", BufferSourceEvent.parseObject(it, 4096)) }
            }) }
    })
    fun assistantDecision(value: JSONObject): Boolean = value.keys().asSequence().toSet() == setOf("route") && value.get("route") == "device_assistant"
    fun encode(outcome: BufferClassificationOutcome, allowAssistantDecision: Boolean = false): JSONObject {
        if (outcome.handedOff) {
            require(allowAssistantDecision && outcome.proposals.isEmpty()) { "当前输入未授权助手分流决定" }
            return JSONObject().put("route", "device_assistant")
        }
        val value = objectCopy(raw(outcome.proposals), MAX_RESULT_BYTES)
        decode(value, outcome.observedRecordIds)
        return value
    }
    fun decode(value: JSONObject, trustedRecordIds: Set<String>): List<BufferClassificationProposal> {
        val copy = objectCopy(value, MAX_RESULT_BYTES)
        require(copy.keys().asSequence().toSet() == setOf("segments"))
        val segments = copy.getJSONArray("segments"); require(segments.length() in 1..8)
        for (index in 0 until segments.length()) {
            val segment = segments.getJSONObject(index)
            val optional = setOf("presentation", "bedtime").filter { segment.has(it) }.toSet()
            require(segment.keys().asSequence().toSet() == base + optional)
            optional.forEach { segment.getJSONObject(it) }
        }
        val parsed = BufferCaptureAgent.parse(copy, trustedRecordIds)
        require(BufferCrdt.canonical(raw(parsed)) == BufferCrdt.canonical(copy)) { "分类结果未规范化" }
        return parsed
    }

    /** Coordinator/cache validation only. IDs in a result establish its shape,
     * never permission to associate source records. The source must separately
     * call decode with IDs backed by its own actual disclosure evidence. */
    fun validateStructure(value: JSONObject) {
        val copy = objectCopy(value, MAX_RESULT_BYTES)
        val segments = copy.getJSONArray("segments"); require(segments.length() in 1..8)
        val mentioned = linkedSetOf<String>()
        for (index in 0 until segments.length()) {
            val ids = segments.getJSONObject(index).getJSONArray("related_card_ids")
            require(ids.length() <= 8)
            for (position in 0 until ids.length()) {
                val id = ids.get(position)
                require(id is String && id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
                mentioned.add(id)
            }
        }
        decode(copy, mentioned)
    }

    fun record(card: BufferCard, detail: Boolean): JSONObject = objectCopy(BufferCaptureAgent.record(card, detail)
        .put("capture_id", card.captureId ?: JSONObject.NULL), 128 * 1024)

    fun readRecord(value: JSONObject, detail: Boolean): BufferCard {
        val copy = objectCopy(value, 128 * 1024)
        require(copy.keys().asSequence().toSet() == setOf("card_id", "capture_id", "title", "kind", "state", "summary", "research_notes", "tags", "created_at"))
        fun text(name: String, maximum: Int): String {
            val field = copy.get(name); require(field is String && field.length <= maximum); return field
        }
        val id = text("card_id", 160); require(id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")))
        val capture = if (copy.isNull("capture_id")) null else text("capture_id", 80).also { require(it.matches(Regex("[A-Za-z0-9._-]{1,80}"))) }
        val kind = text("kind", 80); val state = text("state", 80)
        require(kind.isNotBlank() && state.isNotBlank() && state !in setOf("pending_transcription", "deleted"))
        val tags = copy.getJSONArray("tags"); require(tags.length() <= 20)
        val labels = (0 until tags.length()).map { tags.get(it).also { item -> require(item is String) } as String }
        val stamp = copy.get("created_at")
        require(stamp is Number && stamp.toDouble().isFinite() && stamp.toDouble() == stamp.toLong().toDouble() && stamp.toLong() in 0L..BufferSourceEvent.MAX_COUNTER)
        return BufferCard(cardId = id, captureId = capture, kind = kind, title = text("title", 160),
            summary = text("summary", if (detail) 4000 else 400), state = state, actions = emptyList(), createdAt = stamp.toLong(),
            researchNotes = text("research_notes", if (detail) 3000 else 0), tags = labels)
    }
}
